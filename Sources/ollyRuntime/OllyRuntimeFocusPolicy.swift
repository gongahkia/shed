import Foundation
import ollyDSL
import ollyIPC
import ollyKit

extension OllyRuntime {
    func setFocusPolicy(_ command: IPCSetFocusPolicyCommand) async {
        let updated = FocusPolicy(
            allowedBundleIDs: command.allowedBundleIDs ?? focusPolicy.allowedBundleIDs,
            maxEventsPerSecond: command.maxEventsPerSecond ?? focusPolicy.maxEventsPerSecond,
            minHumanIntervalMilliseconds: command.minHumanIntervalMilliseconds
                ?? focusPolicy.minHumanIntervalMilliseconds
        )
        focusPolicy = updated
        await focusRateLimiter.update(settings: updated.rateLimitSettings)
    }

    func shouldAcceptFocusChange(processID: pid_t, bundleID: String?) async -> Bool {
        guard !focusPolicy.allowsStealing(bundleID: bundleID) else {
            return true
        }
        let userInitiated = focusInputAttribution.hasRecentInput(pid: processID)
        let accepted = await focusRateLimiter.shouldAccept(
            processID: processID,
            isUserInitiated: userInitiated
        )
        guard accepted else {
            await publishRuntimeEvent(.focusBlocked(IPCFocusBlockedEvent(
                processID: processID,
                bundleID: bundleID
            )))
            return false
        }
        return true
    }
}

extension FocusPolicy {
    var rateLimitSettings: FocusRateLimitSettings {
        FocusRateLimitSettings(
            maxEventsPerSecond: maxEventsPerSecond,
            minHumanIntervalMilliseconds: minHumanIntervalMilliseconds
        )
    }
}
