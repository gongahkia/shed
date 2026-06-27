import CoreGraphics
import Foundation
import ollyCore
import ollyKit

public enum EngineHostError: Error, Equatable, Sendable {
    case missingEngineConfig(LayoutEngineID)
    case registry(LayoutEngineRegistryError)
    case engineFactoryFailed(String)
}

public struct EngineHostResult: Equatable, Sendable {
    public let displayID: DisplayID
    public let engineID: LayoutEngineID
    public let placements: [Placement]
    public let appliedPlacements: [Placement]
    public let events: [EngineEvent]
}

public struct EngineHostPlacementSnapshot: Equatable, Sendable {
    public let window: WindowState
    public let placement: Placement
}

public struct EngineHostSnapshot: Equatable, Sendable {
    public let displayID: DisplayID
    public let engineID: LayoutEngineID
    public let placements: [EngineHostPlacementSnapshot]
}

public struct EngineHostWakeRestoreResult: Equatable, Sendable {
    public let displayID: DisplayID
    public let engineID: LayoutEngineID
    public let restoredPlacementCount: Int
    public let durationMilliseconds: Double
    public let targetMilliseconds: Double

    public var isWithinTarget: Bool {
        durationMilliseconds <= targetMilliseconds
    }
}

public typealias LayoutEngineConfigProvider = (LayoutEngineID) async -> Any?
public typealias EngineHostPlacementHandler = (WindowState, Placement) async -> Void
public typealias EngineEventPublisher = (EngineEvent) async -> Void

public actor EngineHost {
    private let windowStore: WindowStore
    private let tagStore: TagStore
    private let registry: LayoutEngineRegistry
    private let configProvider: LayoutEngineConfigProvider
    private let applyPlacement: EngineHostPlacementHandler
    private let publishEvent: EngineEventPublisher
    private var previousPlacementsByWindowID: [WindowID: Placement] = [:]
    private var snapshotsByDisplayID: [DisplayID: EngineHostSnapshot] = [:]
    private var placementArena = PlacementArena()

    public init(
        windowStore: WindowStore,
        tagStore: TagStore,
        registry: LayoutEngineRegistry,
        configProvider: @escaping LayoutEngineConfigProvider,
        applyPlacement: @escaping EngineHostPlacementHandler,
        publishEvent: @escaping EngineEventPublisher = { _ in }
    ) {
        self.windowStore = windowStore
        self.tagStore = tagStore
        self.registry = registry
        self.configProvider = configProvider
        self.applyPlacement = applyPlacement
        self.publishEvent = publishEvent
    }

    public init(
        windowStore: WindowStore,
        tagStore: TagStore,
        registry: LayoutEngineRegistry,
        windowMover: WindowMover,
        configProvider: @escaping LayoutEngineConfigProvider,
        targetResolver: @escaping WindowMoveTargetResolver,
        publishEvent: @escaping EngineEventPublisher = { _ in }
    ) {
        self.init(
            windowStore: windowStore,
            tagStore: tagStore,
            registry: registry,
            configProvider: configProvider,
            applyPlacement: { window, placement in
                guard let target = targetResolver(window) else {
                    return
                }
                let displayTarget = target.withFallbackDisplayID(window.displayID)
                await windowMover.setPosition(placement.frame.origin, for: displayTarget)
                await windowMover.setSize(placement.frame.size, for: displayTarget)
            },
            publishEvent: publishEvent
        )
    }

    public nonisolated func start(
        displayID: DisplayID,
        bounds: CGRect,
        focus: WindowID? = nil
    ) -> Task<Void, Never> {
        Task {
            await runSubscriptions(displayID: displayID, bounds: bounds, focus: focus)
        }
    }

    public nonisolated func start(
        display: Display,
        safeZones: SafeZoneCalculator = SafeZoneCalculator(),
        focus: WindowID? = nil
    ) -> Task<Void, Never> {
        start(displayID: display.id, bounds: safeZones.layoutFrame(for: display), focus: focus)
    }

    public nonisolated func startWakeRestore(
        displayID: DisplayID,
        wakeEvents: AsyncStream<Void> = WakeNotificationMonitor().wakes()
    ) -> Task<Void, Never> {
        Task {
            for await _ in wakeEvents {
                _ = await restoreAfterWake(displayID: displayID)
            }
        }
    }

    public func snapshot(displayID: DisplayID) -> EngineHostSnapshot? {
        snapshotsByDisplayID[displayID]
    }

    @discardableResult
    public func restoreAfterWake(
        displayID: DisplayID,
        targetMilliseconds: Double = 500
    ) async -> EngineHostWakeRestoreResult? {
        guard let snapshot = snapshotsByDisplayID[displayID] else {
            return nil
        }

        let start = ContinuousClock.now
        for item in snapshot.placements {
            await applyPlacement(item.window, item.placement)
        }
        return EngineHostWakeRestoreResult(
            displayID: displayID,
            engineID: snapshot.engineID,
            restoredPlacementCount: snapshot.placements.count,
            durationMilliseconds: Self.milliseconds(from: start.duration(to: ContinuousClock.now)),
            targetMilliseconds: targetMilliseconds
        )
    }

    public func arrange(displayID: DisplayID, bounds: CGRect, focus: WindowID? = nil) async throws -> EngineHostResult {
        try await PerformanceSignpost.interval("layout.arrange") {
            try await arrangeWithSignpost(displayID: displayID, bounds: bounds, focus: focus)
        }
    }

    private func arrangeWithSignpost(
        displayID: DisplayID,
        bounds: CGRect,
        focus: WindowID?
    ) async throws -> EngineHostResult {
        let tagState = await tagStore.state(for: displayID)
        let engine = try await resolveEngine(for: tagState)

        let windows = await windowStore.windows(onDisplay: displayID).filter {
            !$0.isFloating && TagSet(rawValue: $0.tagMask).intersects(tagState.activeTags)
        }
        let snapshots = windows.map(WindowSnapshot.init)
        let placements = engine.arrange(windows: snapshots, in: bounds, focus: focus)
        placementArena.collectChangedPlacements(
            from: placements,
            previousPlacementsByWindowID: previousPlacementsByWindowID
        )
        let windowsByID = Dictionary(uniqueKeysWithValues: windows.map { ($0.id, $0) })
        snapshotsByDisplayID[displayID] = EngineHostSnapshot(
            displayID: displayID,
            engineID: engine.id,
            placements: placements.compactMap { placement in
                guard let window = windowsByID[placement.windowID] else {
                    return nil
                }
                return EngineHostPlacementSnapshot(window: window, placement: placement)
            }
        )

        for placement in placementArena.placements {
            if let window = windowsByID[placement.windowID] {
                await applyPlacement(window, placement)
            }
            previousPlacementsByWindowID[placement.windowID] = placement
        }
        let appliedPlacements = placementArena.toArray()

        removeStalePlacements(keeping: Set(placements.map(\.windowID)))
        let event = EngineEvent.arranged(
            EngineArrangedEvent(
                displayID: displayID,
                engineID: engine.id,
                placementCount: placements.count,
                appliedPlacementCount: appliedPlacements.count
            )
        )
        await publishEvent(event)
        return EngineHostResult(
            displayID: displayID,
            engineID: engine.id,
            placements: placements,
            appliedPlacements: appliedPlacements,
            events: [event]
        )
    }

    public func arrange(
        display: Display,
        safeZones: SafeZoneCalculator = SafeZoneCalculator(),
        focus: WindowID? = nil
    ) async throws -> EngineHostResult {
        try await arrange(displayID: display.id, bounds: safeZones.layoutFrame(for: display), focus: focus)
    }

    private func runSubscriptions(displayID: DisplayID, bounds: CGRect, focus: WindowID?) async {
        await arrangeIgnoringErrors(displayID: displayID, bounds: bounds, focus: focus)
        await withTaskGroup(of: Void.self) { group in
            group.addTask { [weak self] in
                guard let self else {
                    return
                }
                let deltas = await self.windowDeltas()
                for await delta in deltas where delta.affects(displayID: displayID) {
                    await self.arrangeIgnoringErrors(displayID: displayID, bounds: bounds, focus: focus)
                }
            }
            group.addTask { [weak self] in
                guard let self else {
                    return
                }
                let deltas = await self.tagDeltas()
                for await delta in deltas where delta.displayID == displayID {
                    await self.arrangeIgnoringErrors(displayID: displayID, bounds: bounds, focus: focus)
                }
            }
        }
    }

    private func arrangeIgnoringErrors(displayID: DisplayID, bounds: CGRect, focus: WindowID?) async {
        _ = try? await arrange(displayID: displayID, bounds: bounds, focus: focus)
    }

    private func resolveEngine(for tagState: DisplayTagState) async throws -> AnyLayoutEngine {
        for tag in tagState.activeTags.tags {
            if let engineID = tagState.tagToEngine[tag] {
                return try await makeEngine(id: engineID)
            }
        }
        return AnyLayoutEngine(FloatingLayoutEngine())
    }

    private func makeEngine(id engineID: LayoutEngineID) async throws -> AnyLayoutEngine {
        guard let config = await configProvider(engineID) else {
            throw EngineHostError.missingEngineConfig(engineID)
        }

        do {
            return try await registry.makeEngine(id: engineID, config: config)
        } catch let error as LayoutEngineRegistryError {
            throw EngineHostError.registry(error)
        } catch {
            throw EngineHostError.engineFactoryFailed(String(describing: error))
        }
    }

    private func windowDeltas() async -> AsyncStream<WindowStoreDelta> {
        await windowStore.deltas()
    }

    private func tagDeltas() async -> AsyncStream<TagStoreDelta> {
        await tagStore.deltas()
    }

    private func removeStalePlacements(keeping liveWindowIDs: Set<WindowID>) {
        previousPlacementsByWindowID = previousPlacementsByWindowID.filter { liveWindowIDs.contains($0.key) }
    }

    private static func milliseconds(from duration: Duration) -> Double {
        let components = duration.components
        return Double(components.seconds) * 1_000 + Double(components.attoseconds) / 1_000_000_000_000_000
    }
}

private extension WindowStoreDelta {
    func affects(displayID: DisplayID) -> Bool {
        switch self {
        case let .added(state), let .removed(state):
            return state.displayID == displayID
        case let .updated(previous, current):
            return previous.displayID == displayID || current.displayID == displayID
        }
    }
}
