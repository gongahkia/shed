import Foundation

public struct WorkspaceDescriptor: Codable, Equatable, Sendable {
	public var roots: [String]

	public init(roots: [String]) {
		self.roots = roots
	}
}

public struct WorkspaceWindowFileState: Codable, Equatable, Sendable {
	public var path: String
	public var selectionAnchor: Int
	public var selectionHead: Int
	public var foldedRanges: [WorkspaceRangeState]

	public init(path: String, selectionAnchor: Int, selectionHead: Int, foldedRanges: [WorkspaceRangeState] = []) {
		self.path = path
		self.selectionAnchor = selectionAnchor
		self.selectionHead = selectionHead
		self.foldedRanges = foldedRanges
	}
}

public struct WorkspaceRangeState: Codable, Equatable, Sendable {
	public var lowerBound: Int
	public var upperBound: Int

	public init(lowerBound: Int, upperBound: Int) {
		self.lowerBound = lowerBound
		self.upperBound = upperBound
	}
}

public struct WorkspacePaneState: Codable, Equatable, Sendable {
	public var openPaths: [String]
	public var selectedPath: String?

	public init(openPaths: [String] = [], selectedPath: String? = nil) {
		self.openPaths = openPaths
		self.selectedPath = selectedPath
	}
}

public struct WorkspaceWindowState: Codable, Equatable, Sendable {
	public var paneLayout: String
	public var selectedPath: String?
	public var openFiles: [WorkspaceWindowFileState]
	public var paneStates: [WorkspacePaneState]?
	public var focusedPaneIndex: Int?

	public init(
		paneLayout: String = "L",
		selectedPath: String? = nil,
		openFiles: [WorkspaceWindowFileState] = [],
		paneStates: [WorkspacePaneState]? = nil,
		focusedPaneIndex: Int? = nil
	) {
		self.paneLayout = paneLayout
		self.selectedPath = selectedPath
		self.openFiles = openFiles
		self.paneStates = paneStates
		self.focusedPaneIndex = focusedPaneIndex
	}
}

public struct WorkspaceStateStore {
	public var fileManager: FileManager

	public init(fileManager: FileManager = .default) {
		self.fileManager = fileManager
	}

	public func workspaceDirectory(for root: URL) -> URL {
		root.appendingPathComponent(".itsy", isDirectory: true)
	}

	public func descriptorURL(for root: URL) -> URL {
		workspaceDirectory(for: root).appendingPathComponent("workspace.json")
	}

	public func windowStateURL(for root: URL) -> URL {
		workspaceDirectory(for: root).appendingPathComponent("state.json")
	}

	public func loadDescriptor(for root: URL) -> WorkspaceDescriptor? {
		decode(WorkspaceDescriptor.self, from: descriptorURL(for: root))
	}

	public func saveDescriptor(_ descriptor: WorkspaceDescriptor, for root: URL) throws {
		try encode(descriptor, to: descriptorURL(for: root))
	}

	public func loadWindowState(for root: URL) -> WorkspaceWindowState? {
		decode(WorkspaceWindowState.self, from: windowStateURL(for: root))
	}

	public func saveWindowState(_ state: WorkspaceWindowState, for root: URL) throws {
		try encode(state, to: windowStateURL(for: root))
	}

	private func decode<T: Decodable>(_ type: T.Type, from url: URL) -> T? {
		guard let data = try? Data(contentsOf: url) else {
			return nil
		}
		return try? JSONDecoder().decode(type, from: data)
	}

	private func encode(_ value: some Encodable, to url: URL) throws {
		try fileManager.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		let encoder = JSONEncoder()
		encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
		try encoder.encode(value).write(to: url, options: .atomic)
	}
}
