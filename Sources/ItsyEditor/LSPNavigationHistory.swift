import Foundation

public struct LSPNavigationLocation: Equatable, Sendable {
	public var uri: String
	public var selection: Range<Int>

	public init(uri: String, selection: Range<Int>) {
		self.uri = uri
		self.selection = selection
	}
}

public struct LSPNavigationHistory: Equatable, Sendable {
	private(set) var backStack: [LSPNavigationLocation] = []
	private(set) var forwardStack: [LSPNavigationLocation] = []

	public init() {}

	public mutating func recordJump(from origin: LSPNavigationLocation, to destination: LSPNavigationLocation) {
		guard origin != destination else {
			return
		}
		backStack.append(origin)
		forwardStack.removeAll()
	}

	public mutating func goBack(from current: LSPNavigationLocation) -> LSPNavigationLocation? {
		guard let target = backStack.popLast() else {
			return nil
		}
		forwardStack.append(current)
		return target
	}

	public mutating func goForward(from current: LSPNavigationLocation) -> LSPNavigationLocation? {
		guard let target = forwardStack.popLast() else {
			return nil
		}
		backStack.append(current)
		return target
	}
}
