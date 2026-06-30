import Foundation
import ollyDSL
import ollyIPC
import ollyKit

extension OllyRuntime {
    func explainWindow(_ command: IPCExplainWindowCommand) async throws -> IPCRuleExplanation {
        let windowID = try command.windowID ?? focusedWindowID.requiredFocusedWindow()
        guard let window = await windowStore.state(for: windowID) else {
            throw OllyRuntimeError.windowUnavailable(windowID)
        }
        return await explain(window: window, requestedRuleID: nil)
    }

    func explainRule(_ command: IPCExplainRuleCommand) async throws -> IPCRuleExplanation {
        let windowID = try focusedWindowID.requiredFocusedWindow()
        guard let window = await windowStore.state(for: windowID) else {
            throw OllyRuntimeError.windowUnavailable(windowID)
        }
        let explanation = await explain(window: window, requestedRuleID: command.ruleID)
        guard explanation.traces.contains(where: { $0.ruleID == command.ruleID }) else {
            throw OllyRuntimeError.ruleUnavailable(command.ruleID)
        }
        return explanation
    }

    private func explain(window: WindowState, requestedRuleID: UUID?) async -> IPCRuleExplanation {
        let config = await configStore.current()
        let explanation = config.resolvedExplanation(for: RuleContext(window: window))
        let traces = explanation.traces
            .filter { requestedRuleID == nil || $0.ruleID == requestedRuleID }
            .map(IPCRuleMatchTrace.init)
        return IPCRuleExplanation(
            windowID: window.id,
            ruleID: requestedRuleID,
            traces: traces,
            finalApply: IPCRuleApply(explanation.finalApply)
        )
    }
}

private extension RuleContext {
    init(window: WindowState) {
        self.init(
            bundleID: window.bundleID,
            title: window.title,
            role: window.role,
            subrole: window.subrole,
            windowSize: window.frame.size
        )
    }
}

private extension IPCRuleMatchTrace {
    init(_ trace: RuleMatchTrace) {
        self.init(
            ruleID: trace.ruleID,
            matched: trace.matched,
            bundleIDMatched: trace.bundleIDMatched,
            titleMatched: trace.titleMatched,
            roleMatched: trace.roleMatched,
            subroleMatched: trace.subroleMatched,
            predicateMatched: trace.predicateMatched
        )
    }
}

private extension IPCRuleApply {
    init(_ apply: RuleApply) {
        self.init(
            tagMask: apply.tags?.rawValue,
            engineOverride: apply.engineOverride,
            floating: apply.floating,
            sticky: apply.sticky,
            pinned: apply.pinned
        )
    }
}
