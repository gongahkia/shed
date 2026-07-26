import Foundation

public struct GitHubRepositoryName: Equatable, Sendable {
	public let owner: String
	public let name: String

	public init(_ value: String) throws {
		let components = value.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
		guard components.count == 2, components.allSatisfy(Self.isValidComponent) else {
			throw GitHubCLIJSONBridgeError.invalidCommand
		}
		owner = components[0]
		name = components[1]
	}

	private static func isValidComponent(_ value: String) -> Bool {
		!value.isEmpty && value.allSatisfy { $0.isLetter || $0.isNumber || $0 == "." || $0 == "-" || $0 == "_" }
	}
}

struct GitHubCLIGraphQLReviewThreadsRequest: GitHubCLIJSONRequest {
	let repository: GitHubRepositoryName
	let pullRequestNumber: Int
	let endCursor: String?

	func arguments() throws -> [String] {
		guard pullRequestNumber > 0 else { throw GitHubCLIJSONBridgeError.invalidCommand }
		var arguments = [
			"api", "graphql",
			"-F", "owner=\(repository.owner)",
			"-F", "name=\(repository.name)",
			"-F", "number=\(pullRequestNumber)",
			"-f", "query=\(Self.query)",
		]
		if let endCursor {
			arguments += ["-F", "endCursor=\(endCursor)"]
		}
		return arguments
	}

	private static let query = """
	query($owner: String!, $name: String!, $number: Int!, $endCursor: String) {
	  repository(owner: $owner, name: $name) {
	    pullRequest(number: $number) {
	      reviewThreads(first: 100, after: $endCursor) {
	        nodes {
	          id path line originalLine startLine originalStartLine diffSide startDiffSide isResolved isOutdated
	          comments(first: 100) { nodes { id fullDatabaseId body createdAt url author { login } } }
	        }
	        pageInfo { hasNextPage endCursor }
	      }
	    }
	  }
	}
	"""
}

public struct GitHubReviewThreadComment: Codable, Equatable, Sendable {
	public let id: String
	public let fullDatabaseId: Int64?
	public let body: String
	public let createdAt: String
	public let url: URL
	public let author: GitHubReviewThreadAuthor?
}

public struct GitHubReviewThreadAuthor: Codable, Equatable, Sendable {
	public let login: String
}

public struct GitHubReviewThread: Codable, Equatable, Sendable {
	public let id: String
	public let path: String
	public let line: Int?
	public let originalLine: Int?
	public let startLine: Int?
	public let originalStartLine: Int?
	public let diffSide: String?
	public let startDiffSide: String?
	public let isResolved: Bool
	public let isOutdated: Bool
	public let comments: GitHubReviewThreadCommentConnection

	public var range: ClosedRange<Int>? {
		guard let end = line ?? originalLine else { return nil }
		return (startLine ?? originalStartLine ?? end) ... end
	}
}

public struct GitHubReviewThreadCommentConnection: Codable, Equatable, Sendable {
	public let nodes: [GitHubReviewThreadComment]
}

public struct GitHubReviewThreadSourceLine: Equatable, Sendable {
	public let number: Int
	public let text: String
}

public struct GitHubReviewThreadContext: Equatable, Sendable {
	public let thread: GitHubReviewThread
	public let localFileURL: URL?
	public let sourceLines: [GitHubReviewThreadSourceLine]

	public static func make(thread: GitHubReviewThread, workspaceURL: URL, surroundingLineCount: Int = 3, fileManager: FileManager = .default) -> GitHubReviewThreadContext {
		let root = workspaceURL.standardizedFileURL
		let candidate = root.appendingPathComponent(thread.path).standardizedFileURL
		guard candidate.path.hasPrefix(root.path + "/"), fileManager.fileExists(atPath: candidate.path), let contents = try? String(contentsOf: candidate, encoding: .utf8), let range = thread.range else {
			return GitHubReviewThreadContext(thread: thread, localFileURL: nil, sourceLines: [])
		}
		let lines = contents.components(separatedBy: .newlines)
		let lower = max(1, range.lowerBound - max(0, surroundingLineCount))
		let upper = min(lines.count, range.upperBound + max(0, surroundingLineCount))
		guard lower <= upper else { return GitHubReviewThreadContext(thread: thread, localFileURL: candidate, sourceLines: []) }
		return GitHubReviewThreadContext(
			thread: thread,
			localFileURL: candidate,
			sourceLines: (lower ... upper).map { GitHubReviewThreadSourceLine(number: $0, text: lines[$0 - 1]) }
		)
	}
}

public enum GitHubReviewThreadsQueryState: Equatable, Sendable {
	case ready([GitHubReviewThread])
	case incompletePagination([GitHubReviewThread])
	case failed(GitHubCLIJSONBridgeError)
}

public struct GitHubReviewThreadsQuery: Sendable {
	private let bridge: GitHubCLIJSONBridge

	public init(bridge: GitHubCLIJSONBridge) {
		self.bridge = bridge
	}

	public func refresh(repository: GitHubRepositoryName, pullRequestNumber: Int, workspaceURL: URL, maximumPages: Int = 20) async throws -> GitHubReviewThreadsQueryState {
		guard pullRequestNumber > 0, maximumPages > 0 else { return .failed(.invalidCommand) }
		var threads: [GitHubReviewThread] = []
		var cursor: String?
		for _ in 0 ..< maximumPages {
			do {
				let response = try await bridge.executeRequest(
					GitHubCLIGraphQLReviewThreadsRequest(repository: repository, pullRequestNumber: pullRequestNumber, endCursor: cursor),
					as: GitHubReviewThreadsResponse.self,
					workspaceURL: workspaceURL
				)
				threads += response.data.repository.pullRequest.reviewThreads.nodes
				let pageInfo = response.data.repository.pullRequest.reviewThreads.pageInfo
				guard pageInfo.hasNextPage else { return .ready(threads) }
				guard let endCursor = pageInfo.endCursor, endCursor != cursor else { return .incompletePagination(threads) }
				cursor = endCursor
			} catch is CancellationError {
				throw CancellationError()
			} catch let error as GitHubCLIJSONBridgeError {
				return .failed(error)
			} catch {
				return .failed(.unavailable)
			}
		}
		return .incompletePagination(threads)
	}
}

private struct GitHubReviewThreadsResponse: Decodable, Sendable {
	let data: GitHubReviewThreadsData
}

private struct GitHubReviewThreadsData: Decodable, Sendable {
	let repository: GitHubReviewThreadsRepository
}

private struct GitHubReviewThreadsRepository: Decodable, Sendable {
	let pullRequest: GitHubReviewThreadsPullRequest
}

private struct GitHubReviewThreadsPullRequest: Decodable, Sendable {
	let reviewThreads: GitHubReviewThreadConnection
}

private struct GitHubReviewThreadConnection: Codable, Sendable {
	let nodes: [GitHubReviewThread]
	let pageInfo: GitHubReviewThreadPageInfo
}

private struct GitHubReviewThreadPageInfo: Codable, Sendable {
	let hasNextPage: Bool
	let endCursor: String?
}
