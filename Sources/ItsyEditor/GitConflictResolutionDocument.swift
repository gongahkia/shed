import Foundation

public enum GitConflictResolutionDocumentError: Error, Equatable, Sendable {
	case unresolvedMarkers
}

public struct GitConflictResolutionDocument: Equatable, Sendable {
	public private(set) var text: String

	public init(text: String) {
		self.text = text
	}

	public var regions: [GitConflictRegion] {
		GitConflictParser.parse(text)
	}

	public var hasUnresolvedMarkers: Bool {
		GitConflictParser.hasUnresolvedMarkers(text)
	}

	public mutating func resolve(regionIndex: Int, with resolution: GitConflictResolution) {
		text = GitConflictParser.resolvedText(text, regionIndex: regionIndex, resolution: resolution)
	}

	public mutating func replaceText(_ text: String) {
		self.text = text
	}

	public func textForStaging() throws -> String {
		guard !hasUnresolvedMarkers else {
			throw GitConflictResolutionDocumentError.unresolvedMarkers
		}
		return text
	}
}
