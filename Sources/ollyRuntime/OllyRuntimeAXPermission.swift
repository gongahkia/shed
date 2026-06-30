import ApplicationServices
import Foundation
import ollyDSL
import ollyIPC
import ollyKit

extension OllyRuntime {
    public static var defaultAXPermissionStream: @Sendable () -> AsyncStream<AXPermissionStatus> {
        { AXPermission.permissionStream() }
    }

    public static var defaultAXOnboarding: @MainActor @Sendable () async -> Void {
        { _ = await AXPermission.presentOnboardingSheetIfNeeded() }
    }

    func startAXPermissionObservation() {
        let stream = axPermissionStream
        let task = Task { [weak self] in
            for await status in stream() {
                guard let self, !Task.isCancelled else {
                    return
                }
                await self.handleAXPermissionChange(status)
            }
        }
        tasks.append(task)
    }

    func handleAXPermissionChange(_ status: AXPermissionStatus) async {
        guard axPermissionStatus != status else {
            return
        }
        axPermissionStatus = status
        switch status {
        case .missing:
            await handleAXRevoke()
        case .trusted:
            await handleAXGrant()
        }
    }

    private func handleAXRevoke() async {
        stopAXObservers()
        focusInputAttribution.stop()
        await windowMover.flushAndPause()
        await publishRuntimeEvent(.axPermission(IPCAXPermissionEvent(status: .missing)))
        await hookDispatcher.axPermissionChanged(AXPermissionHookContext(status: .missing))
        let presentOnboarding = presentAXOnboarding
        Task { @MainActor in
            await presentOnboarding()
        }
    }

    private func handleAXGrant() async {
        await windowMover.resume()
        await refreshAllWindows()
        startApplicationObservation()
        startNativeSpaceObservation()
        focusInputAttribution.start()
        for display in displayProvider() {
            try? await applyAndArrange(displayID: display.id)
        }
        await publishRuntimeEvent(.axPermission(IPCAXPermissionEvent(status: .trusted)))
        await hookDispatcher.axPermissionChanged(AXPermissionHookContext(status: .trusted))
    }

    func handleAXReadWriteError(_ error: AXError) async {
        guard error.isAXPermissionRevocationSignal else {
            return
        }
        for _ in 0..<3 {
            try? await Task.sleep(nanoseconds: 250_000_000)
            if AXPermission.status(prompt: false) == .trusted {
                return
            }
        }
        await handleAXPermissionChange(.missing)
    }
}
