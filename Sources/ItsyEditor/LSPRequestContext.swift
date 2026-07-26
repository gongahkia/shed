import Foundation

public struct LSPRequestContext: Equatable, Sendable {
	public var uri: String
	public var documentVersion: Int
	public var content: String
	public var cursorOffset: Int

	public init(uri: String, documentVersion: Int, content: String, cursorOffset: Int) {
		self.uri = uri
		self.documentVersion = documentVersion
		self.content = content
		self.cursorOffset = cursorOffset
	}

	public func matches(uri: String, documentVersion: Int, content: String, cursorOffset: Int) -> Bool {
		self.uri == uri && self.documentVersion == documentVersion && self.content == content && self.cursorOffset == cursorOffset
	}
}
