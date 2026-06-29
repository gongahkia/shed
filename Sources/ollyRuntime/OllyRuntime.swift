import ApplicationServices
import CoreGraphics
import Foundation
import ollyCore
import ollyDSL
import ollyIPC
import ollyKit
import ollyLayouts

public struct OllyRuntimeMenuSnapshot: Equatable, Sendable {
    public let displayName: String
    public let displayID: DisplayID?
    public let activeTags: [UInt8]
    public let currentEngineID: LayoutEngineID
    public let axStatus: AXPermissionStatus
    public let isIPCServerRunning: Bool
    public let lastError: String?

    public init(
        displayName: String,
        displayID: DisplayID?,
        activeTags: [UInt8],
        currentEngineID: LayoutEngineID,
        axStatus: AXPermissionStatus,
        isIPCServerRunning: Bool,
        lastError: String?
    ) {
        self.displayName = displayName
        self.displayID = displayID
        self.activeTags = activeTags
        self.currentEngineID = currentEngineID
        self.axStatus = axStatus
        self.isIPCServerRunning = isIPCServerRunning
        self.lastError = lastError
    }
}

public enum OllyRuntimeError: Error, CustomStringConvertible {
    case displayUnavailable
    case engineUnavailable(LayoutEngineID)
    case windowUnavailable(WindowID)
    case missingFocusedWindow
    case missingDirectionalTarget(IPCDirection)
    case axOperationFailed(String, AXError)
    case unsupportedAXCommand(String)

    public var description: String {
        switch self {
        case .displayUnavailable:
            return "display unavailable"
        case let .engineUnavailable(engineID):
            return "engine unavailable: \(engineID.rawValue)"
        case let .windowUnavailable(windowID):
            return "window unavailable: \(windowID)"
        case .missingFocusedWindow:
            return "no focused window"
        case let .missingDirectionalTarget(direction):
            return "no window in direction: \(direction.rawValue)"
        case let .axOperationFailed(operation, error):
            return "\(operation) failed: \(error)"
        case let .unsupportedAXCommand(command):
            return "\(command) requires Accessibility permission"
        }
    }

    var code: String {
        switch self {
        case .displayUnavailable:
            return "display_unavailable"
        case .engineUnavailable:
            return "engine_unavailable"
        case .windowUnavailable:
            return "window_unavailable"
        case .missingFocusedWindow:
            return "missing_focused_window"
        case .missingDirectionalTarget:
            return "missing_directional_target"
        case .axOperationFailed:
            return "ax_operation_failed"
        case .unsupportedAXCommand:
            return "ax_unavailable"
        }
    }
}

// swiftlint:disable:next type_body_length
public actor OllyRuntime {
    private let socketPath: IPCSocketPath
    let configLoader: ConfigLoader
    let displayProvider: @Sendable () -> [Display]
    private let scanAXOnStart: Bool
    let applicationMonitor: ApplicationMonitor
    let snapshotCache: WindowSnapshotCache
    let windowStore = WindowStore()
    let tagStore = TagStore(defaultActiveTags: OllyRuntime.defaultActiveTags)
    let focusStack = FocusStack()
    let configStore = RuntimeConfigStore()
    let eventHub = RuntimeEventHub()
    let windowTargets = RuntimeWindowTargets()
    let statePersistence: WindowTagPersistence
    let recoveryJournal: WindowRecoveryJournal
    let windowMover: WindowMover
    let assignment: WindowTagAssignment
    let dispatcher: TagDispatcher
    let engineHost: EngineHost
    private let registry: LayoutEngineRegistry
    private var server: UnixDomainSocketServer?
    var applicationsByProcessID: [pid_t: Application] = [:]
    var axObserversByProcessID: [pid_t: AXObserverBridge] = [:]
    var tasks: [Task<Void, Never>] = []
    var focusedWindowID: WindowID?
    var lastError: String?

    public init(
        socketPath: IPCSocketPath = .resolved(),
        configLoader: ConfigLoader = ConfigLoader(),
        displayProvider: @escaping @Sendable () -> [Display] = { DisplayMonitor().displays() },
        applicationMonitor: ApplicationMonitor = ApplicationMonitor(),
        snapshotCache: WindowSnapshotCache = WindowSnapshotCache(),
        statePersistence: WindowTagPersistence = WindowTagPersistence(),
        recoveryJournal: WindowRecoveryJournal = WindowRecoveryJournal(),
        scanAXOnStart: Bool = true
    ) {
        self.socketPath = socketPath
        self.configLoader = configLoader
        self.displayProvider = displayProvider
        self.applicationMonitor = applicationMonitor
        self.snapshotCache = snapshotCache
        self.statePersistence = statePersistence
        self.recoveryJournal = recoveryJournal
        self.scanAXOnStart = scanAXOnStart
        self.windowMover = WindowMover()
        self.assignment = WindowTagAssignment(windowStore: windowStore)
        self.registry = Self.makeRegistry()
        self.dispatcher = TagDispatcher(
            windowStore: windowStore,
            tagStore: tagStore,
            windowMover: windowMover,
            displayProvider: { displayProvider() },
            targetResolver: { [windowTargets] window in
                windowTargets.target(for: window)
            }
        )
        self.engineHost = EngineHost(
            windowStore: windowStore,
            tagStore: tagStore,
            registry: registry,
            configProvider: { [configStore] engineID in
                await configStore.config(for: engineID)
            },
            applyPlacement: { [windowMover, windowTargets, recoveryJournal] window, placement in
                if placement.hidden {
                    try? await recoveryJournal.record(window: window, parkedFrame: placement.frame)
                } else {
                    try? await recoveryJournal.remove(windowID: window.id)
                }
                guard let target = windowTargets.target(for: window) else {
                    return
                }
                let displayTarget = target.withFallbackDisplayID(window.displayID)
                await windowMover.setPosition(placement.frame.origin, for: displayTarget)
                await windowMover.setSize(placement.frame.size, for: displayTarget)
            },
            publishEvent: { [eventHub] event in
                await eventHub.publish(.engine(event))
            }
        )
    }

    public func start() async throws {
        guard server == nil else {
            return
        }
        try await loadConfig(useDefaultWhenMissing: true)
        await initializeDisplays()
        if scanAXOnStart, AXPermission.isTrusted {
            await refreshAllWindows()
            startApplicationObservation()
            await refreshFocusedWindowFromSystem()
        }
        let server = UnixDomainSocketServer(socketPath: socketPath) { [weak self] connection, line in
            guard let self else {
                return
            }
            await self.handle(line: line, connection: connection)
        }
        try server.start()
        self.server = server
    }

    public func stop() async {
        tasks.forEach { $0.cancel() }
        tasks.removeAll()
        stopAXObservers()
        _ = await restoreJournaledWindows()
        server?.stop()
        server = nil
        windowTargets.removeAll()
    }

    public func menuSnapshot() async -> OllyRuntimeMenuSnapshot {
        let display = selectedDisplay(nil)
        let displayID = display?.id
        let tagState: DisplayTagState?
        if let displayID {
            tagState = await tagStore.state(for: displayID)
        } else {
            tagState = nil
        }
        let activeTags = tagState?.activeTags.tags.map(\.index) ?? [0]
        let engineID = tagState.flatMap { state in
            state.activeTags.tags.compactMap { state.tagToEngine[$0] }.first
        } ?? FloatingLayoutEngine.engineID
        return OllyRuntimeMenuSnapshot(
            displayName: display?.localizedName ?? "No display",
            displayID: displayID,
            activeTags: activeTags,
            currentEngineID: engineID,
            axStatus: AXPermission.status(prompt: false),
            isIPCServerRunning: server?.isRunning == true,
            lastError: lastError
        )
    }

    private func handle(line: Data, connection: UnixDomainSocketServerConnection) async {
        do {
            let request = try JSONDecoder().decode(IPCRequestEnvelope.self, from: line)
            let response = try await response(for: request, connection: connection)
            if let response {
                try connection.sendLine(JSONEncoder().encode(response))
            }
        } catch let error as OllyRuntimeError {
            lastError = error.description
            let response = IPCResponseEnvelope.failure(
                error: IPCErrorPayload(code: error.code, message: error.description)
            )
            if let data = try? JSONEncoder().encode(response) {
                connection.sendLine(data)
            }
        } catch {
            lastError = String(describing: error)
            let response = IPCResponseEnvelope.failure(
                error: IPCErrorPayload(code: "runtime_error", message: String(describing: error))
            )
            if let data = try? JSONEncoder().encode(response) {
                connection.sendLine(data)
            }
        }
    }

    private func response(
        for request: IPCRequestEnvelope,
        connection: UnixDomainSocketServerConnection
    ) async throws -> IPCResponseEnvelope? {
        switch request.command {
        case .state, .listWindows, .listDisplays, .version, .subscribeEvents:
            return try await queryResponse(for: request, connection: connection)
        case .switchTag, .toggleTag, .tagAdd, .tagRemove, .moveToTag, .moveToDisplay:
            return try await tagResponse(for: request)
        case .setEngine, .cycleEngine:
            return try await engineResponse(for: request)
        case .focus, .moveWindow, .swap, .toggleFloating, .reload, .restoreWindows:
            return try await controlResponse(for: request)
        }
    }

    private func queryResponse(
        for request: IPCRequestEnvelope,
        connection: UnixDomainSocketServerConnection
    ) async throws -> IPCResponseEnvelope? {
        switch request.command {
        case let .state(command):
            return .ok(id: request.id, result: .state(await stateSnapshot(displayID: command.displayID)))
        case let .listWindows(command):
            return .ok(id: request.id, result: .state(await windowListSnapshot(command)))
        case let .listDisplays(command):
            return .ok(id: request.id, result: .state(await displayListSnapshot(command)))
        case .version:
            return .ok(id: request.id, result: .version(IPCVersionInfo()))
        case let .subscribeEvents(command):
            let id = await eventHub.subscribe(connection: connection, kinds: command.eventKinds)
            connection.onClose { [eventHub] in
                Task {
                    await eventHub.unsubscribe(id)
                }
            }
            if command.replayCurrentState {
                await replayCurrentState(to: connection, kinds: command.eventKinds)
            }
            return .ok(id: request.id, result: .subscribed(IPCSubscriptionInfo(eventKinds: command.eventKinds)))
        default:
            preconditionFailure("invalid query command")
        }
    }

    private func tagResponse(for request: IPCRequestEnvelope) async throws -> IPCResponseEnvelope? {
        switch request.command {
        case let .switchTag(command):
            try await switchTag(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "tag switched")))
        case let .toggleTag(command):
            try await toggleTag(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "tag toggled")))
        case let .tagAdd(command):
            try await addTag(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "tag added")))
        case let .tagRemove(command):
            try await removeTag(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "tag removed")))
        case let .moveToTag(command):
            try await moveToTag(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "window moved to tag")))
        case let .moveToDisplay(command):
            try await moveToDisplay(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "window moved to display")))
        default:
            preconditionFailure("invalid tag command")
        }
    }

    private func engineResponse(for request: IPCRequestEnvelope) async throws -> IPCResponseEnvelope? {
        switch request.command {
        case let .setEngine(command):
            try await setEngine(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "engine set")))
        case let .cycleEngine(command):
            try await cycleEngine(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "engine cycled")))
        default:
            preconditionFailure("invalid engine command")
        }
    }

    private func controlResponse(for request: IPCRequestEnvelope) async throws -> IPCResponseEnvelope? {
        switch request.command {
        case let .focus(command):
            try await focus(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "focus changed")))
        case let .moveWindow(command):
            try await moveWindow(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "window moved")))
        case let .swap(command):
            try await swapWindow(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "window swapped")))
        case let .toggleFloating(command):
            try await toggleFloating(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "floating toggled")))
        case .reload:
            try await reloadConfig()
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "config reloaded")))
        case let .restoreWindows(command):
            let info = await restoreWindows(command)
            return .ok(id: request.id, result: .restoredWindows(info))
        default:
            preconditionFailure("invalid control command")
        }
    }
}
