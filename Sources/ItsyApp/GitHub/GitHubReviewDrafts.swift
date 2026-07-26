import Foundation
import ItsyEditor

public enum GitHubReviewCommentSide: String, Codable, Equatable, Sendable {
	case left = "LEFT"
	case right = "RIGHT"
}

public struct GitHubGitObjectID: Codable, Equatable, Sendable {
	public let value: String

	public init(_ value: String) throws {
		guard value.count == 40 || value.count == 64,
			value.unicodeScalars.allSatisfy({ $0.isASCII && ((48 ... 57).contains($0.value) || (65 ... 70).contains($0.value) || (97 ... 102).contains($0.value)) })
		else { throw GitHubCLIJSONBridgeError.invalidCommand }
		self.value = value
	}
}

public struct GitHubReviewLineLocation: Codable, Equatable, Sendable {
	public let path: String
	public let range: ClosedRange<Int>
	public let side: GitHubReviewCommentSide
	public let commitOID: GitHubGitObjectID

	public init(path: String, range: ClosedRange<Int>, side: GitHubReviewCommentSide, commitOID: GitHubGitObjectID) throws {
		guard Self.isSafePath(path), range.lowerBound > 0 else { throw GitHubCLIJSONBridgeError.invalidCommand }
		self.path = path
		self.range = range
		self.side = side
		self.commitOID = commitOID
	}

	private static func isSafePath(_ path: String) -> Bool {
		!path.isEmpty && !path.hasPrefix("/") && path.split(separator: "/", omittingEmptySubsequences: false).allSatisfy { !$0.isEmpty && $0 != "." && $0 != ".." }
	}
}

public struct GitHubReviewReplyTarget: Codable, Equatable, Sendable {
	public let commentID: Int64

	public init(commentID: Int64) throws {
		guard commentID > 0 else { throw GitHubCLIJSONBridgeError.invalidCommand }
		self.commentID = commentID
	}

	public init(comment: GitHubReviewThreadComment) throws {
		guard let fullDatabaseId = comment.fullDatabaseId else { throw GitHubCLIJSONBridgeError.invalidCommand }
		try self.init(commentID: fullDatabaseId)
	}
}

public enum GitHubReviewDraftTarget: Codable, Equatable, Sendable {
	case review
	case inline(GitHubReviewLineLocation)
	case reply(GitHubReviewReplyTarget)
}

public struct GitHubReviewDraft: Codable, Equatable, Sendable, Identifiable {
	public let id: UUID
	public let pullRequestNumber: Int
	public let target: GitHubReviewDraftTarget
	public var body: String
	public let createdAt: Date

	public init(id: UUID = UUID(), pullRequestNumber: Int, target: GitHubReviewDraftTarget, body: String, createdAt: Date = Date()) throws {
		guard pullRequestNumber > 0, !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw GitHubCLIJSONBridgeError.invalidCommand }
		self.id = id
		self.pullRequestNumber = pullRequestNumber
		self.target = target
		self.body = body
		self.createdAt = createdAt
	}
}

public enum GitHubReviewMarkdown {
	public static func quote(_ sourceLines: [GitHubReviewThreadSourceLine]) -> String {
		sourceLines.map { $0.text.isEmpty ? ">" : "> \($0.text)" }.joined(separator: "\n")
	}

	public static func preview(draft: GitHubReviewDraft, workspaceURL: URL) -> String {
		guard case let .inline(location) = draft.target else { return draft.body }
		let thread = GitHubReviewThread(id: "draft", path: location.path, line: location.range.upperBound, originalLine: nil, startLine: location.range.lowerBound == location.range.upperBound ? nil : location.range.lowerBound, originalStartLine: nil, diffSide: location.side.rawValue, startDiffSide: location.side.rawValue, isResolved: false, isOutdated: false, comments: GitHubReviewThreadCommentConnection(nodes: []))
		let context = GitHubReviewThreadContext.make(thread: thread, workspaceURL: workspaceURL, surroundingLineCount: 0)
		let range = location.range.lowerBound == location.range.upperBound ? String(location.range.lowerBound) : "\(location.range.lowerBound)-\(location.range.upperBound)"
		let heading = "`\(location.path):\(range)`"
		let quoted = quote(context.sourceLines)
		return quoted.isEmpty ? "\(heading)\n\n\(draft.body)" : "\(heading)\n\n\(quoted)\n\n\(draft.body)"
	}
}

public struct GitHubReviewDraftStore {
	public let workspaceURL: URL
	private let fileManager: FileManager

	public init(workspaceURL: URL, fileManager: FileManager = .default) {
		self.workspaceURL = workspaceURL
		self.fileManager = fileManager
	}

	public var url: URL {
		workspaceURL.appendingPathComponent(".itsy", isDirectory: true).appendingPathComponent("github-review-drafts.json")
	}

	public func load() -> [GitHubReviewDraft] {
		guard let data = try? Data(contentsOf: url) else { return [] }
		let decoder = JSONDecoder()
		decoder.dateDecodingStrategy = .iso8601
		return (try? decoder.decode([GitHubReviewDraft].self, from: data)) ?? []
	}

	public func load(pullRequestNumber: Int) -> [GitHubReviewDraft] {
		load().filter { $0.pullRequestNumber == pullRequestNumber }
	}

	public func save(_ drafts: [GitHubReviewDraft]) throws {
		try fileManager.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		let encoder = JSONEncoder()
		encoder.dateEncodingStrategy = .iso8601
		encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
		try AtomicFileWriter.write(data: try encoder.encode(drafts), to: url)
	}
}

public enum GitHubReviewDraftDeliveryResult: Equatable, Sendable {
	case queuedForReview
	case submitted(commentID: Int64)
	case failed(GitHubCLIJSONBridgeError)
}

public struct GitHubReviewDraftDelivery: Sendable {
	private let bridge: GitHubCLIJSONBridge

	public init(bridge: GitHubCLIJSONBridge) {
		self.bridge = bridge
	}

	public func submit(draft: GitHubReviewDraft, repository: GitHubRepositoryName, workspaceURL: URL) async throws -> GitHubReviewDraftDeliveryResult {
		switch draft.target {
		case .review:
			return .queuedForReview
		case let .inline(location):
			return try await submit(GitHubCLIReviewDraftRequest.inline(repository: repository, pullRequestNumber: draft.pullRequestNumber, location: location, body: draft.body), workspaceURL: workspaceURL)
		case let .reply(target):
			return try await submit(GitHubCLIReviewDraftRequest.reply(repository: repository, pullRequestNumber: draft.pullRequestNumber, target: target, body: draft.body), workspaceURL: workspaceURL)
		}
	}

	public func retry(draft: GitHubReviewDraft, repository: GitHubRepositoryName, workspaceURL: URL) async throws -> GitHubReviewDraftDeliveryResult {
		try await submit(draft: draft, repository: repository, workspaceURL: workspaceURL)
	}

	private func submit(_ request: GitHubCLIReviewDraftRequest, workspaceURL: URL) async throws -> GitHubReviewDraftDeliveryResult {
		do {
			let response = try await bridge.executeRequest(request, as: GitHubReviewDraftDeliveryResponse.self, workspaceURL: workspaceURL)
			return .submitted(commentID: response.id)
		} catch is CancellationError {
			throw CancellationError()
		} catch let error as GitHubCLIJSONBridgeError {
			return .failed(error)
		} catch {
			return .failed(.unavailable)
		}
	}
}

private enum GitHubCLIReviewDraftRequest: GitHubCLIJSONRequest {
	case inline(repository: GitHubRepositoryName, pullRequestNumber: Int, location: GitHubReviewLineLocation, body: String)
	case reply(repository: GitHubRepositoryName, pullRequestNumber: Int, target: GitHubReviewReplyTarget, body: String)

	func arguments() throws -> [String] {
		switch self {
		case let .inline(repository, pullRequestNumber, location, body):
			guard pullRequestNumber > 0, !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw GitHubCLIJSONBridgeError.invalidCommand }
			var arguments = [
				"api", "--method", "POST", "repos/\(repository.owner)/\(repository.name)/pulls/\(pullRequestNumber)/comments",
				"-f", "body=\(body)",
				"-f", "path=\(location.path)",
				"-f", "commit_id=\(location.commitOID.value)",
				"-F", "line=\(location.range.upperBound)",
				"-f", "side=\(location.side.rawValue)",
			]
			if location.range.lowerBound != location.range.upperBound {
				arguments += ["-F", "start_line=\(location.range.lowerBound)", "-f", "start_side=\(location.side.rawValue)"]
			}
			return arguments
		case let .reply(repository, pullRequestNumber, target, body):
			guard pullRequestNumber > 0, !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw GitHubCLIJSONBridgeError.invalidCommand }
			return ["api", "--method", "POST", "repos/\(repository.owner)/\(repository.name)/pulls/\(pullRequestNumber)/comments/\(target.commentID)/replies", "-f", "body=\(body)"]
		}
	}
}

private struct GitHubReviewDraftDeliveryResponse: Decodable, Sendable {
	let id: Int64
}
