import ApplicationServices
import CoreGraphics
import Foundation
import ollyCore
import ollyDSL
import ollyIPC
import ollyKit
import ollyLayouts

public typealias AXSubroleReader = @Sendable (AXUIElement) async throws -> String?
public typealias DisplayChangeStreamProvider = @Sendable () -> AsyncStream<DisplayChange>
public typealias ActiveSpaceWindowIDProvider = @Sendable () -> Set<WindowID>?
public typealias NativeSpaceChangeStreamProvider = @Sendable () -> AsyncStream<Void>

// swiftlint:disable:next type_body_length
public actor OllyRuntime {
    let socketPath: IPCSocketPath
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
    public let runtimeEventBus: RuntimeEventBus
    public let dragSession: AXDragSession
    let fullscreenTracker = FullscreenTracker()
    let windowTargets = RuntimeWindowTargets()
    let focusRateLimiter = FocusRateLimiter()
    let hookDispatcher = HookDispatcher()
    let rawActionExecutor = RawActionExecutor()
    let macroRecorder: MacroRecorder
    let statePersistence: WindowTagPersistence
    let recoveryJournal: WindowRecoveryJournal
    let windowMover: WindowMover
    let assignment: WindowTagAssignment
    let dispatcher: TagDispatcher
    let engineHost: EngineHost
    private let registry: LayoutEngineRegistry
    let axPermissionStream: @Sendable () -> AsyncStream<AXPermissionStatus>
    let axSubroleReader: AXSubroleReader
    let displayChangeStream: DisplayChangeStreamProvider
    let activeSpaceWindowIDs: ActiveSpaceWindowIDProvider
    let nativeSpaceChangeStream: NativeSpaceChangeStreamProvider
    let focusInputAttribution: FocusInputAttribution
    let presentAXOnboarding: @MainActor @Sendable () async -> Void
    let fullscreenDebounceNanoseconds: UInt64
    let nativeSpaceDebounceNanoseconds: UInt64
    private var server: UnixDomainSocketServer?
    var applicationsByProcessID: [pid_t: Application] = [:]
    var axObserversByProcessID: [pid_t: AXObserverBridge] = [:]
    var tasks: [Task<Void, Never>] = []
    var applicationObservationTask: Task<Void, Never>?
    var displayObservationTask: Task<Void, Never>?
    var nativeSpaceObservationTask: Task<Void, Never>?
    var nativeSpaceVerificationTask: Task<Void, Never>?
    var axPermissionStatus: AXPermissionStatus?
    var nativeSpaceDriftPolicy: NativeSpaceDriftPolicy = .followWindow
    var focusPolicy = FocusPolicy()
    var focusedWindowID: WindowID?
    var lastError: String?
    var fullscreenTasksByWindowID: [WindowID: Task<Void, Never>] = [:]

    public init(
        socketPath: IPCSocketPath = .resolved(),
        configLoader: ConfigLoader = ConfigLoader(),
        displayProvider: @escaping @Sendable () -> [Display] = { DisplayMonitor().displays() },
        applicationMonitor: ApplicationMonitor = ApplicationMonitor(),
        snapshotCache: WindowSnapshotCache = WindowSnapshotCache(),
        statePersistence: WindowTagPersistence = WindowTagPersistence(),
        recoveryJournal: WindowRecoveryJournal = WindowRecoveryJournal(),
        macroRecorder: MacroRecorder = MacroRecorder(),
        scanAXOnStart: Bool = true,
        runtimeEventBus: RuntimeEventBus = RuntimeEventBus(),
        dragSession: AXDragSession = AXDragSession(),
        axPermissionStream: @escaping @Sendable () -> AsyncStream<AXPermissionStatus> =
            OllyRuntime.defaultAXPermissionStream,
        axSubroleReader: @escaping AXSubroleReader = OllyRuntime.defaultAXSubroleReader,
        displayChangeStream: @escaping DisplayChangeStreamProvider = OllyRuntime.defaultDisplayChangeStream,
        activeSpaceWindowIDs: @escaping ActiveSpaceWindowIDProvider = OllyRuntime.defaultActiveSpaceWindowIDs,
        nativeSpaceChangeStream: @escaping NativeSpaceChangeStreamProvider = OllyRuntime.defaultNativeSpaceChangeStream,
        focusInputAttribution: FocusInputAttribution = .shared,
        fullscreenDebounceNanoseconds: UInt64 = 100_000_000,
        nativeSpaceDebounceNanoseconds: UInt64 = 2_000_000_000,
        presentAXOnboarding: @escaping @MainActor @Sendable () async -> Void =
            OllyRuntime.defaultAXOnboarding
    ) {
        self.socketPath = socketPath; self.configLoader = configLoader
        self.displayProvider = displayProvider; self.applicationMonitor = applicationMonitor
        self.snapshotCache = snapshotCache; self.statePersistence = statePersistence; self.macroRecorder = macroRecorder
        self.recoveryJournal = recoveryJournal; self.scanAXOnStart = scanAXOnStart
        self.runtimeEventBus = runtimeEventBus; self.dragSession = dragSession
        self.axPermissionStream = axPermissionStream
        self.axSubroleReader = axSubroleReader; self.fullscreenDebounceNanoseconds = fullscreenDebounceNanoseconds
        self.displayChangeStream = displayChangeStream; self.activeSpaceWindowIDs = activeSpaceWindowIDs
        self.nativeSpaceChangeStream = nativeSpaceChangeStream
        self.nativeSpaceDebounceNanoseconds = nativeSpaceDebounceNanoseconds
        self.focusInputAttribution = focusInputAttribution
        self.presentAXOnboarding = presentAXOnboarding
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
            publishEvent: { [eventHub, runtimeEventBus] event in
                let ipcEvent = IPCEvent.engine(event)
                await eventHub.publish(ipcEvent)
                await runtimeEventBus.publish(ipcEvent)
            }
        )
    }

    public func start() async throws {
        guard server == nil else {
            return
        }
        try await loadConfig(useDefaultWhenMissing: true)
        await initializeDisplays()
        await windowMover.setAXErrorHandler { [weak self] error in Task { await self?.handleAXReadWriteError(error) } }
        axPermissionStatus = AXPermission.status(prompt: false)
        startAXPermissionObservation()
        startDisplayObservation()
        if scanAXOnStart, AXPermission.isTrusted {
            await refreshAllWindows()
            startApplicationObservation()
            startNativeSpaceObservation()
            focusInputAttribution.start()
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
        fullscreenTasksByWindowID.values.forEach { $0.cancel() }
        fullscreenTasksByWindowID.removeAll()
        applicationObservationTask = nil
        displayObservationTask?.cancel()
        displayObservationTask = nil
        nativeSpaceObservationTask?.cancel()
        nativeSpaceObservationTask = nil
        nativeSpaceVerificationTask?.cancel()
        nativeSpaceVerificationTask = nil
        focusInputAttribution.stop()
        await windowMover.setAXErrorHandler(nil)
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
            LayoutEnginePolicy.resolvedEngineID(activeTags: state.activeTags, tagToEngine: state.tagToEngine)
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
            await macroRecorder.record(request.command)
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
        case .state, .listWindows, .listDisplays, .listCooperativeApps, .explainWindow, .explainRule,
             .version, .subscribeEvents:
            return try await queryResponse(for: request, connection: connection)
        case .switchTag, .toggleTag, .tagAdd, .tagRemove, .moveToTag, .moveToDisplay:
            return try await tagResponse(for: request)
        case .setEngine, .cycleEngine, .manualPreselect, .bspTree:
            return try await engineResponse(for: request)
        case .macroStart, .macroStop, .macroRun, .macroList, .macroDelete:
            return try await macroResponse(for: request)
        case .focus, .moveWindow, .swap, .toggleFloating, .toggleSticky, .togglePinned, .snapWindow,
             .dispatchGesture, .reload, .restoreWindows, .runRawAction, .setSpacePolicy, .setFocusPolicy:
            return try await controlResponse(for: request)
        case let .reserved(command):
            return .failure(
                id: request.id,
                error: IPCErrorPayload(
                    code: "unknown_command",
                    message: "command is reserved but not implemented: \(command.name.rawValue)"
                )
            )
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
        case .listCooperativeApps:
            return .ok(id: request.id, result: .cooperativeApps(await cooperativeAppsInfo()))
        case let .explainWindow(command):
            return .ok(id: request.id, result: .ruleExplanation(try await explainWindow(command)))
        case let .explainRule(command):
            return .ok(id: request.id, result: .ruleExplanation(try await explainRule(command)))
        case .version:
            return .ok(id: request.id, result: .version(IPCVersionInfo()))
        case let .subscribeEvents(command):
            let eventKinds = command.negotiatedEventKinds(forProtocolVersion: request.version)
            let eventProtocolVersion = min(max(request.version, 1), OllyIPC.protocolVersion)
            let id = await eventHub.subscribe(
                connection: connection,
                kinds: eventKinds,
                protocolVersion: eventProtocolVersion
            )
            connection.onClose { [eventHub] in
                Task {
                    await eventHub.unsubscribe(id)
                }
            }
            if command.replayCurrentState {
                await replayCurrentState(
                    to: connection,
                    kinds: eventKinds,
                    protocolVersion: eventProtocolVersion
                )
            }
            return .ok(id: request.id, result: .subscribed(IPCSubscriptionInfo(eventKinds: eventKinds)))
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
        case let .manualPreselect(command):
            try await manualPreselect(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "manual preselected")))
        case let .bspTree(command):
            try await mutateBSPTree(command)
            return .ok(id: request.id, result: .acknowledged(IPCAcknowledgement(message: "bsp tree changed")))
        default:
            preconditionFailure("invalid engine command")
        }
    }
}
