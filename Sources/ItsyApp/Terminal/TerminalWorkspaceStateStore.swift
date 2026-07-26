import Foundation

struct TerminalWorkspaceRestoration {
	let state: TerminalWorkspaceState
	let requiresPersistence: Bool

	static func restore(data: Data, fallbackDirectoryURL: URL, fileManager: FileManager = .default) -> TerminalWorkspaceRestoration? {
		guard let state = try? JSONDecoder().decode(TerminalWorkspaceState.self, from: data) else { return nil }
		var didRepair = false
		let tabs = state.tabs.map {
			TerminalTabState(
				title: $0.title,
				activePaneIndex: $0.activePaneIndex,
				rootPane: repair($0.rootPane, fallbackDirectoryURL: fallbackDirectoryURL, fileManager: fileManager, didRepair: &didRepair)
			)
		}
		return TerminalWorkspaceRestoration(
			state: TerminalWorkspaceState(selectedTabIndex: state.selectedTabIndex, tabs: tabs),
			requiresPersistence: didRepair || TerminalWorkspaceState.requiresMigration(for: data)
		)
	}

	private static func repair(_ pane: TerminalPaneState, fallbackDirectoryURL: URL, fileManager: FileManager, didRepair: inout Bool) -> TerminalPaneState {
		switch pane.kind {
		case .leaf:
			let directoryURL = URL(fileURLWithPath: pane.currentDirectoryPath!, isDirectory: true)
			var isDirectory: ObjCBool = false
			if fileManager.fileExists(atPath: directoryURL.path, isDirectory: &isDirectory), isDirectory.boolValue {
				return pane
			}
			didRepair = true
			return .leaf(currentDirectoryPath: fallbackDirectoryURL.standardizedFileURL.path)
		case .split:
			return .split(
				orientation: pane.orientation!,
				children: pane.children!.map { repair($0, fallbackDirectoryURL: fallbackDirectoryURL, fileManager: fileManager, didRepair: &didRepair) }
			)
		}
	}
}

struct TerminalWorkspaceStateStore {
	let workspaceURL: URL
	private let fileManager: FileManager

	init(workspaceURL: URL, fileManager: FileManager = .default) {
		self.workspaceURL = workspaceURL
		self.fileManager = fileManager
	}

	var url: URL {
		workspaceURL.appendingPathComponent(".itsy", isDirectory: true).appendingPathComponent("terminal.json")
	}

	func restore(fallbackDirectoryURL: URL) -> TerminalWorkspaceRestoration? {
		guard let data = try? Data(contentsOf: url) else { return nil }
		return TerminalWorkspaceRestoration.restore(data: data, fallbackDirectoryURL: fallbackDirectoryURL, fileManager: fileManager)
	}

	func save(_ state: TerminalWorkspaceState) throws {
		try fileManager.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		let encoder = JSONEncoder()
		encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
		try encoder.encode(state).write(to: url, options: .atomic)
	}

	func remove() {
		guard fileManager.fileExists(atPath: url.path) else { return }
		try? fileManager.removeItem(at: url)
	}
}
