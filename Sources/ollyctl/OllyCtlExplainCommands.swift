import ArgumentParser
import Foundation
import ollyIPC
import ollyKit

struct ExplainWindow: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "explain-window",
        abstract: "Explain rule matches for a window."
    )

    @OptionGroup
    var options: ClientOptions

    @Argument(help: "Window ID; focused window is used when omitted.")
    var windowID: WindowID?

    func run() throws {
        try OllyCtlRunner(options: options).send(.explainWindow(IPCExplainWindowCommand(windowID: windowID)))
    }
}

struct ExplainRule: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "explain-rule",
        abstract: "Explain one rule against the focused window."
    )

    @OptionGroup
    var options: ClientOptions

    @Argument(help: "Rule UUID.")
    var ruleID: String

    func run() throws {
        try OllyCtlRunner(options: options).send(.explainRule(IPCExplainRuleCommand(ruleID: try parseRuleID(ruleID))))
    }
}

func parseRuleID(_ rawValue: String) throws -> UUID {
    guard let ruleID = UUID(uuidString: rawValue) else {
        throw ValidationError("rule ID must be a UUID")
    }
    return ruleID
}
