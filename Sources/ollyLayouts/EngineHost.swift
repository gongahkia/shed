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
                await windowMover.setPosition(placement.frame.origin, for: target)
                await windowMover.setSize(placement.frame.size, for: target)
                await windowMover.flushNow()
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

    public func arrange(displayID: DisplayID, bounds: CGRect, focus: WindowID? = nil) async throws -> EngineHostResult {
        let tagState = await tagStore.state(for: displayID)
        let engine = try await resolveEngine(for: tagState)

        let windows = await windowStore.windows(onDisplay: displayID).filter {
            TagSet(rawValue: $0.tagMask).intersects(tagState.activeTags)
        }
        let snapshots = windows.map(WindowSnapshot.init)
        let placements = engine.arrange(windows: snapshots, in: bounds, focus: focus)
        let changedPlacements = placements.filter {
            previousPlacementsByWindowID[$0.windowID] != $0
        }
        let windowsByID = Dictionary(uniqueKeysWithValues: windows.map { ($0.id, $0) })

        for placement in changedPlacements {
            if let window = windowsByID[placement.windowID] {
                await applyPlacement(window, placement)
            }
            previousPlacementsByWindowID[placement.windowID] = placement
        }

        removeStalePlacements(keeping: Set(placements.map(\.windowID)))
        let event = EngineEvent.arranged(
            EngineArrangedEvent(
                displayID: displayID,
                engineID: engine.id,
                placementCount: placements.count,
                appliedPlacementCount: changedPlacements.count
            )
        )
        await publishEvent(event)
        return EngineHostResult(
            displayID: displayID,
            engineID: engine.id,
            placements: placements,
            appliedPlacements: changedPlacements,
            events: [event]
        )
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
