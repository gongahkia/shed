import Foundation
import ollyIPC
import ollyRuntime

final class RuntimeEventStatusController {
    private let runtime: OllyRuntime
    private let onSnapshot: @MainActor @Sendable (OllyRuntimeMenuSnapshot) -> Void
    private var task: Task<Void, Never>?

    init(
        runtime: OllyRuntime,
        onSnapshot: @escaping @MainActor @Sendable (OllyRuntimeMenuSnapshot) -> Void
    ) {
        self.runtime = runtime
        self.onSnapshot = onSnapshot
    }

    func start() {
        guard task == nil else {
            return
        }
        let runtime = runtime
        let onSnapshot = onSnapshot
        task = Task {
            let stream = await runtime.runtimeEventBus.subscribe()
            for await event in stream {
                guard Self.shouldRefreshStatus(for: event), !Task.isCancelled else {
                    continue
                }
                let snapshot = await runtime.menuSnapshot()
                await onSnapshot(snapshot)
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
    }

    private static func shouldRefreshStatus(for event: IPCEvent) -> Bool {
        switch event {
        case .axPermission, .engine, .focus, .focusBlocked, .fullscreen, .space:
            return true
        }
    }
}
