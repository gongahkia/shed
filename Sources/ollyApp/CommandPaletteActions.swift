import Foundation
import ollyIPC
import ollyLayouts

struct CommandPaletteAction: Equatable {
    let id: String
    let title: String
    let detail: String
    let keywords: [String]
    let command: IPCCommand
}

struct CommandPaletteActionCatalog {
    func actions() -> [CommandPaletteAction] {
        var actions = baseActions()
        actions.append(contentsOf: directionalActions(prefix: "focus", title: "Focus", command: IPCCommand.focus))
        actions.append(contentsOf: directionalActions(
            prefix: "move",
            title: "Move Window",
            command: IPCCommand.moveWindow
        ))
        actions.append(contentsOf: directionalActions(prefix: "swap", title: "Swap Window", command: IPCCommand.swap))
        actions.append(contentsOf: tagActions())
        actions.append(contentsOf: engineActions())
        return actions
    }

    private func baseActions() -> [CommandPaletteAction] {
        [
            action("state", "Show State", "Print current olly state", ["inspect"], .state(IPCStateCommand())),
            action("reload", "Reload Config", "Reload Config.swift", ["dsl"], .reload(IPCReloadCommand())),
            action(
                "restore-windows",
                "Restore Windows",
                "Restore windows parked or hidden by olly",
                ["recovery"],
                .restoreWindows(IPCRestoreWindowsCommand())
            ),
            action("version", "Show IPC Version", "Print protocol version", ["debug"], .version(IPCVersionCommand())),
            action(
                "cycle-engine",
                "Cycle Engine",
                "Cycle active tag through layout engines",
                ["layout", "Nehir"],
                .cycleEngine(IPCCycleEngineCommand())
            ) // Nehir prior art: IPC command bar UX
        ]
    }

    private func directionalActions(
        prefix: String,
        title: String,
        command: (IPCDirectionalCommand) -> IPCCommand
    ) -> [CommandPaletteAction] {
        directionValues().map { label, direction in
            action(
                "\(prefix)-\(direction.rawValue)",
                "\(title) \(label)",
                "\(prefix) \(direction.rawValue)",
                [direction.rawValue],
                command(IPCDirectionalCommand(direction: direction))
            )
        }
    }

    private func tagActions() -> [CommandPaletteAction] {
        (0..<9).flatMap { index -> [CommandPaletteAction] in
            guard let tag = try? IPCTagIndex(validating: index) else {
                return []
            }
            let label = String(index + 1)
            return [
                action(
                    "switch-tag-\(index)",
                    "Switch to Tag \(label)",
                    "Set active view to tag \(label)",
                    ["tag", "workspace"],
                    .switchTag(IPCTagCommand(tag: tag))
                ),
                action(
                    "toggle-tag-\(index)",
                    "Toggle Tag \(label)",
                    "Add or remove tag \(label)",
                    ["tag", "workspace"],
                    .toggleTag(IPCTagCommand(tag: tag))
                ),
                action(
                    "tag-add-\(index)",
                    "Add Tag \(label)",
                    "Add tag \(label) to active view",
                    ["tag", "workspace"],
                    .tagAdd(IPCTagCommand(tag: tag))
                ),
                action(
                    "tag-remove-\(index)",
                    "Remove Tag \(label)",
                    "Remove tag \(label) from active view",
                    ["tag", "workspace"],
                    .tagRemove(IPCTagCommand(tag: tag))
                ),
                action(
                    "move-to-tag-\(index)",
                    "Move Window to Tag \(label)",
                    "Move focused window to tag \(label)",
                    ["tag", "window"],
                    .moveToTag(IPCMoveToTagCommand(tag: tag))
                )
            ]
        }
    }

    private func engineActions() -> [CommandPaletteAction] {
        [
            ("floating", "Set Engine: Floating", FloatingLayoutEngine.engineID),
            ("niri-scroll", "Set Engine: NiriScroll", NiriScrollLayoutEngine.engineID),
            ("bsp", "Set Engine: BSP", BSPLayoutEngine.engineID),
            ("manual", "Set Engine: Manual", ManualLayoutEngine.engineID),
            ("master-stack", "Set Engine: MasterStack", MasterStackLayoutEngine.engineID)
        ].map { id, title, engineID in
            action(
                "set-engine-\(id)",
                title,
                "Bind \(engineID.rawValue) to active tag",
                ["layout", "engine"],
                .setEngine(IPCSetEngineCommand(engineID: engineID))
            )
        }
    }

    private func directionValues() -> [(String, IPCDirection)] {
        [
            ("Left", .left),
            ("Right", .right),
            ("Up", .upward),
            ("Down", .downward),
            ("Next", .next),
            ("Previous", .previous)
        ]
    }

    private func action(
        _ id: String,
        _ title: String,
        _ detail: String,
        _ keywords: [String],
        _ command: IPCCommand
    ) -> CommandPaletteAction {
        CommandPaletteAction(id: id, title: title, detail: detail, keywords: keywords, command: command)
    }
}

struct CommandPaletteExecutor {
    let socketPath: IPCSocketPath
    let queue: DispatchQueue

    init(socketPath: IPCSocketPath = .resolved(), queue: DispatchQueue = .global(qos: .userInitiated)) {
        self.socketPath = socketPath
        self.queue = queue
    }

    func execute(
        _ action: CommandPaletteAction,
        completion: @escaping (CommandPaletteExecutionResult) -> Void
    ) {
        queue.async {
            completion(send(action.command, socketPath: socketPath))
        }
    }

    private func send(_ command: IPCCommand, socketPath: IPCSocketPath) -> CommandPaletteExecutionResult {
        do {
            let request = IPCRequestEnvelope(command: command)
            let data = try JSONEncoder().encode(request)
            let responseLine = try UnixDomainSocketClient(socketPath: socketPath).sendLine(data)
            let response = try JSONDecoder().decode(IPCResponseEnvelope.self, from: responseLine)
            if let error = response.error {
                return .failure("\(error.code): \(error.message)")
            }
            return response.status == .success ? .success : .failure("command failed")
        } catch {
            return .failure(String(describing: error))
        }
    }
}

enum CommandPaletteMatcher {
    static func filter(_ actions: [CommandPaletteAction], query: String) -> [CommandPaletteAction] {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !needle.isEmpty else {
            return actions
        }
        let scored: [(CommandPaletteAction, Int)] = actions.compactMap { action in
            guard let score = score(action, query: needle) else {
                return nil
            }
            return (action, score)
        }
        let sorted = scored.sorted { lhs, rhs in
            lhs.1 == rhs.1 ? lhs.0.title < rhs.0.title : lhs.1 > rhs.1
        }
        return sorted.map(\.0)
    }

    private static func score(_ action: CommandPaletteAction, query: String) -> Int? {
        let terms = ([action.title, action.detail] + action.keywords)
        let haystack = terms.joined(separator: " ").lowercased()
        if let range = haystack.range(of: query) {
            return 1_000 - haystack.distance(from: haystack.startIndex, to: range.lowerBound)
        }
        return isSubsequence(query, of: haystack) ? query.count : nil
    }

    private static func isSubsequence(_ query: String, of haystack: String) -> Bool {
        var current = haystack.startIndex
        for character in query {
            guard let match = haystack[current...].firstIndex(of: character) else {
                return false
            }
            current = haystack.index(after: match)
        }
        return true
    }
}
