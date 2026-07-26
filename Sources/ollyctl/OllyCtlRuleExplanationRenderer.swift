import Foundation
import ollyIPC

struct OllyCtlRuleExplanationRenderer {
    func render(_ explanation: IPCRuleExplanation) -> String {
        var lines = [
            "window \(explanation.windowID.map(String.init) ?? "-")",
            "final \(renderApply(explanation.finalApply))"
        ]
        guard !explanation.traces.isEmpty else {
            return (lines + ["no rule traces"]).joined(separator: "\n")
        }
        lines.append(contentsOf: explanation.traces.map(renderTrace))
        return lines.joined(separator: "\n")
    }

    private func renderTrace(_ trace: IPCRuleMatchTrace) -> String {
        [
            "rule \(trace.ruleID.uuidString)",
            trace.matched ? "matched" : "rejected",
            renderField("bundleID", trace.bundleIDMatched),
            renderField("title", trace.titleMatched),
            renderField("role", trace.roleMatched),
            renderField("subrole", trace.subroleMatched),
            renderField("predicate", trace.predicateMatched)
        ].joined(separator: " ")
    }

    private func renderField(_ name: String, _ value: Bool?) -> String {
        guard let value else {
            return "\(name)=-"
        }
        return "\(name)=\(value ? "yes" : "no")"
    }

    private func renderApply(_ apply: IPCRuleApply) -> String {
        [
            apply.tagMask.map { "tags=\(renderTagMask($0))" },
            apply.engineOverride.map { "engine=\($0.rawValue)" },
            apply.floating.map { "floating=\($0)" },
            apply.sticky.map { "sticky=\($0)" },
            apply.pinned.map { "pinned=\($0)" }
        ].compactMap { $0 }.joined(separator: " ").emptyFallback("-")
    }

    private func renderTagMask(_ tagMask: UInt64) -> String {
        let tags = (0..<64).compactMap { index in
            tagMask & (UInt64(1) << UInt64(index)) == 0 ? nil : String(index)
        }
        return tags.isEmpty ? "-" : tags.joined(separator: ",")
    }
}

private extension String {
    func emptyFallback(_ fallback: String) -> String {
        isEmpty ? fallback : self
    }
}
