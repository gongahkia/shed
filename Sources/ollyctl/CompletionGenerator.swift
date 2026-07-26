import ArgumentParser
import Foundation

enum CompletionShellName: String, ExpressibleByArgument {
    case zsh
    case bash
    case fish

    var completionShell: CompletionShell {
        switch self {
        case .zsh: return .zsh
        case .bash: return .bash
        case .fish: return .fish
        }
    }
}

enum OllyCtlCompletionGenerator {
    static func script(for shell: CompletionShellName) -> String {
        OllyCtl.completionScript(for: shell.completionShell)
    }
}

struct Manpage: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "manpage",
        abstract: "Print the ollyctl manual page."
    )

    func run() throws {
        print(try OllyCtlManpageGenerator().render())
    }
}

struct OllyCtlManpageGenerator {
    var date = Date()

    func render(dumpHelpJSON: String = OllyCtl._dumpHelp()) throws -> String {
        let data = Data(dumpHelpJSON.utf8)
        let tool = try JSONDecoder().decode(OllyCtlToolInfo.self, from: data)
        return render(command: tool.command)
    }

    private func render(command: OllyCtlCommandInfo) -> String {
        var lines = [
            ".TH \(roff(command.commandName.uppercased())) 1 \"\(dateString)\" \"olly\" \"Olly Manual\"",
            ".SH NAME",
            "\(roff(command.commandName)) \\- \(roff(command.abstract ?? "control olly"))",
            ".SH SYNOPSIS",
            ".B \(roff(command.commandName))",
            "[OPTIONS] [COMMAND]",
            ".SH DESCRIPTION",
            roff(command.abstract ?? "")
        ]
        appendArguments(command.arguments, title: "GLOBAL OPTIONS", to: &lines)
        appendCommands(command.subcommands ?? [], to: &lines)
        appendCommandOptions(command.subcommands ?? [], prefix: command.commandName, to: &lines)
        return lines.joined(separator: "\n")
    }

    private var dateString: String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }

    private func appendArguments(
        _ arguments: [OllyCtlArgumentInfo]?,
        title: String,
        to lines: inout [String]
    ) {
        let visible = (arguments ?? []).filter(\.isVisible)
        guard !visible.isEmpty else {
            return
        }
        lines.append(".SH \(title)")
        for argument in visible {
            lines.append(".TP")
            lines.append(".B \(roff(argument.displayName))")
            lines.append(roff(argument.abstract ?? ""))
        }
    }

    private func appendCommands(
        _ commands: [OllyCtlCommandInfo],
        to lines: inout [String]
    ) {
        let visible = flattened(commands).filter(\.command.isVisible)
        guard !visible.isEmpty else {
            return
        }
        lines.append(".SH COMMANDS")
        for item in visible {
            lines.append(".TP")
            lines.append(".B \(roff(item.path.joined(separator: " ")))")
            lines.append(roff(item.command.abstract ?? ""))
        }
    }

    private func appendCommandOptions(
        _ commands: [OllyCtlCommandInfo],
        prefix: String,
        to lines: inout [String]
    ) {
        let visible = flattened(commands).filter { item in
            item.command.isVisible && item.command.visibleArguments.isEmpty == false
        }
        guard !visible.isEmpty else {
            return
        }
        lines.append(".SH COMMAND OPTIONS")
        for item in visible {
            lines.append(".SS \(roff(([prefix] + item.path).joined(separator: " ")))")
            for argument in item.command.visibleArguments {
                lines.append(".TP")
                lines.append(".B \(roff(argument.displayName))")
                lines.append(roff(argument.abstract ?? ""))
            }
        }
    }

    private func flattened(
        _ commands: [OllyCtlCommandInfo],
        prefix: [String] = []
    ) -> [(path: [String], command: OllyCtlCommandInfo)] {
        commands.flatMap { command in
            let path = prefix + [command.commandName]
            return [(path, command)] + flattened(command.subcommands ?? [], prefix: path)
        }
    }

    private func roff(_ text: String) -> String {
        text.replacingOccurrences(of: "\\", with: "\\\\")
    }
}

private struct OllyCtlToolInfo: Decodable {
    let command: OllyCtlCommandInfo
}

private struct OllyCtlCommandInfo: Decodable {
    let commandName: String
    let shouldDisplay: Bool?
    let abstract: String?
    let subcommands: [OllyCtlCommandInfo]?
    let arguments: [OllyCtlArgumentInfo]?

    var isVisible: Bool {
        shouldDisplay ?? true
    }

    var visibleArguments: [OllyCtlArgumentInfo] {
        (arguments ?? []).filter(\.isVisible)
    }
}

private struct OllyCtlArgumentInfo: Decodable {
    enum Kind: String, Decodable {
        case positional
        case option
        case flag
    }

    let kind: Kind
    let shouldDisplay: Bool?
    let names: [OllyCtlNameInfo]?
    let preferredName: OllyCtlNameInfo?
    let valueName: String?
    let abstract: String?

    var isVisible: Bool {
        shouldDisplay ?? true
    }

    var displayName: String {
        switch kind {
        case .positional:
            return "<\(valueName ?? "value")>"
        case .option:
            return optionNames + " <\(valueName ?? "value")>"
        case .flag:
            return optionNames
        }
    }

    private var optionNames: String {
        let rendered = (names ?? preferredName.map { [$0] } ?? []).map(\.displayName)
        return rendered.joined(separator: ", ")
    }
}

private struct OllyCtlNameInfo: Decodable {
    enum Kind: String, Decodable {
        case long
        case short
        case longWithSingleDash
    }

    let kind: Kind
    let name: String

    var displayName: String {
        switch kind {
        case .long: return "--\(name)"
        case .short: return "-\(name)"
        case .longWithSingleDash: return "-\(name)"
        }
    }
}
