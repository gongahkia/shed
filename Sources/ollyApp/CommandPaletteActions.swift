import Foundation
import ollyDSL
import ollyIPC
import ollyLayouts
import ollyRuntime

struct CommandPaletteAction: Equatable {
    let id: String
    let title: String
    let detail: String
    let keywords: [String]
    let command: IPCCommand
}

struct CommandPaletteActionCatalog {
    private let shellActions: [ShellAction]
    private let macroDirectory: URL

    init(config: Config? = nil, macroDirectory: URL = MacroRecorder.defaultDirectory) {
        self.shellActions = config?.commandPaletteShellActions ?? []
        self.macroDirectory = macroDirectory
    }

    func actions() -> [CommandPaletteAction] {
        var actions = baseActions()
        actions.append(contentsOf: directionalActions(prefix: "focus", title: "Focus", command: IPCCommand.focus))
        actions.append(contentsOf: directionalActions(
            prefix: "move",
            title: "Move Window",
            command: IPCCommand.moveWindow
        ))
        actions.append(contentsOf: directionalActions(prefix: "swap", title: "Swap Window", command: IPCCommand.swap))
        actions.append(contentsOf: snapActions())
        actions.append(contentsOf: manualPreselectActions())
        actions.append(contentsOf: bspTreeActions())
        actions.append(contentsOf: tagActions())
        actions.append(contentsOf: engineActions())
        actions.append(contentsOf: macroActions())
        actions.append(contentsOf: rawActions())
        return actions
    }

    private func baseActions() -> [CommandPaletteAction] {
        [
            action("state", "Show State", "Print current olly state", ["inspect"], .state(IPCStateCommand())),
            action(
                "list-windows",
                "List Windows",
                "Print known windows",
                ["inspect"],
                .listWindows(IPCWindowQueryCommand())
            ),
            action(
                "list-displays",
                "List Displays",
                "Print known displays",
                ["inspect"],
                .listDisplays(IPCDisplayQueryCommand())
            ),
            action(
                "explain-focused-window",
                "Explain Focused Window",
                "Trace rule matches for the focused window",
                ["inspect", "rules"],
                .explainWindow(IPCExplainWindowCommand())
            ),
            action(
                "toggle-floating",
                "Toggle Floating",
                "Toggle focused window tiling",
                ["window"],
                .toggleFloating(IPCFloatingCommand())
            ),
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

    private func rawActions() -> [CommandPaletteAction] {
        shellActions.map { shellAction in
            action(
                "raw-action-\(shellAction.label)",
                "Run \(shellAction.label)",
                shellAction.command,
                ["raw", "shell", shellAction.label],
                .runRawAction(IPCRunRawActionCommand(label: shellAction.label))
            )
        }
    }

    private func macroActions() -> [CommandPaletteAction] {
        (try? MacroRecorder.macroInfos(in: macroDirectory))?.map { macro in
            action(
                "macro-\(macro.name)",
                "Run Macro: \(macro.name)",
                "\(macro.commandCount) commands",
                ["macro", "replay", macro.name],
                .macroRun(IPCMacroRunCommand(name: macro.name))
            )
        } ?? []
    }

    private func snapActions() -> [CommandPaletteAction] {
        [
            ("left-half", "Snap Left Half", IPCSnapPosition.leftHalf),
            ("right-half", "Snap Right Half", IPCSnapPosition.rightHalf),
            ("top-half", "Snap Top Half", IPCSnapPosition.topHalf),
            ("bottom-half", "Snap Bottom Half", IPCSnapPosition.bottomHalf),
            ("center", "Center Window", IPCSnapPosition.center),
            ("maximize", "Maximize Window", IPCSnapPosition.maximize)
        ].map { id, title, position in
            action(
                "snap-window-\(id)",
                title,
                "Snap focused window",
                ["window", "snap", "zone"],
                .snapWindow(IPCSnapWindowCommand(position: position))
            )
        }
    }

    private func manualPreselectActions() -> [CommandPaletteAction] {
        [
            ("left", "Manual Preselect Left", ManualPreselectDirection.left),
            ("right", "Manual Preselect Right", ManualPreselectDirection.right),
            ("up", "Manual Preselect Up", ManualPreselectDirection.up),
            ("down", "Manual Preselect Down", ManualPreselectDirection.down)
        ].map { id, title, direction in
            action(
                "manual-preselect-\(id)",
                title,
                "Set manual split direction",
                ["layout", "manual"],
                .manualPreselect(IPCManualPreselectCommand(direction: direction))
            )
        }
    }

    private func bspTreeActions() -> [CommandPaletteAction] {
        [
            ("rotate", "BSP Rotate Children", BSPTreeAction.rotateChildren),
            ("flip", "BSP Flip Axis", BSPTreeAction.flipAxis),
            ("balance", "BSP Balance Tree", BSPTreeAction.balanceTree)
        ].map { id, title, treeAction in
            action(
                "bsp-tree-\(id)",
                title,
                "Mutate active BSP tree",
                ["layout", "bsp"],
                .bspTree(IPCBSPTreeCommand(action: treeAction))
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

private extension Config {
    var commandPaletteShellActions: [ShellAction] {
        shellActions.filter { permissions.shellExec.allows(label: $0.label) }
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
