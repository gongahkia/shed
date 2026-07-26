import Foundation

public struct GitHubRepositoryContext: Codable, Equatable, Sendable {
	public let nameWithOwner: String
	public let url: URL
	public let defaultBranchName: String?
	public let currentBranchPullRequest: GitHubPullRequestSummary?
	public let pullRequestLists: [GitHubPullRequestList]

	public init(nameWithOwner: String, url: URL, defaultBranchName: String?, currentBranchPullRequest: GitHubPullRequestSummary?, pullRequestLists: [GitHubPullRequestList]) {
		self.nameWithOwner = nameWithOwner
		self.url = url
		self.defaultBranchName = defaultBranchName
		self.currentBranchPullRequest = currentBranchPullRequest
		self.pullRequestLists = pullRequestLists
	}
}

public struct GitHubPullRequestSummary: Codable, Equatable, Sendable {
	public let number: Int
	public let url: URL
	public let title: String
	public let state: String
	public let isDraft: Bool
	public let headRefName: String
	public let baseRefName: String
	public let reviewDecision: String?

	public init(number: Int, url: URL, title: String, state: String, isDraft: Bool, headRefName: String, baseRefName: String, reviewDecision: String?) {
		self.number = number
		self.url = url
		self.title = title
		self.state = state
		self.isDraft = isDraft
		self.headRefName = headRefName
		self.baseRefName = baseRefName
		self.reviewDecision = reviewDecision
	}
}

public struct GitHubPullRequestListConfiguration: Equatable, Sendable {
	public let title: String
	public let limit: Int
	public let headRefName: GitHubCLIReferenceName?

	public init(title: String, limit: Int = 30, headRefName: GitHubCLIReferenceName? = nil) throws {
		guard !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, (1 ... 100).contains(limit) else {
			throw GitHubCLIJSONBridgeError.invalidCommand
		}
		self.title = title
		self.limit = limit
		self.headRefName = headRefName
	}
}

public struct GitHubPullRequestList: Codable, Equatable, Sendable {
	public let title: String
	public let pullRequests: [GitHubPullRequestSummary]

	public init(title: String, pullRequests: [GitHubPullRequestSummary]) {
		self.title = title
		self.pullRequests = pullRequests
	}
}

public enum GitHubRepositoryContextQueryOperation: String, Equatable, Sendable {
	case repository
	case currentBranchPullRequest
	case pullRequestList
}

public enum GitHubRepositoryContextQueryState: Equatable, Sendable {
	case ready(GitHubRepositoryContext)
	case repositoryMismatch(expected: String, actual: String)
	case queryFailed(operation: GitHubRepositoryContextQueryOperation, error: GitHubCLIJSONBridgeError)
}

public struct GitHubRepositoryContextQuery: Sendable {
	private static let repositoryFields: [GitHubCLIRepositoryJSONField] = [.nameWithOwner, .url, .defaultBranchRef]
	private static let pullRequestFields: [GitHubCLIPullRequestJSONField] = [.number, .url, .title, .state, .isDraft, .headRefName, .baseRefName, .reviewDecision]
	private let bridge: GitHubCLIJSONBridge

	public init(bridge: GitHubCLIJSONBridge) {
		self.bridge = bridge
	}

	public func refresh(
		workspaceURL: URL,
		expectedRepository: String? = nil,
		pullRequestLists: [GitHubPullRequestListConfiguration] = []
	) async throws -> GitHubRepositoryContextQueryState {
		do {
			let repository = try await bridge.execute(.repository(fields: Self.repositoryFields), as: GitHubRepositoryResponse.self, workspaceURL: workspaceURL)
			if let expectedRepository, repository.nameWithOwner.caseInsensitiveCompare(expectedRepository) != .orderedSame {
				return .repositoryMismatch(expected: expectedRepository, actual: repository.nameWithOwner)
			}
			let currentPullRequest = try await currentBranchPullRequest(workspaceURL: workspaceURL)
			var resolvedLists: [GitHubPullRequestList] = []
			for configuration in pullRequestLists {
				try Task.checkCancellation()
				let pullRequests: [GitHubPullRequestSummary]
				do {
					pullRequests = try await bridge.execute(
						.pullRequestList(limit: configuration.limit, headRefName: configuration.headRefName, fields: Self.pullRequestFields),
						as: [GitHubPullRequestSummary].self,
						workspaceURL: workspaceURL
					)
				} catch is CancellationError {
					throw CancellationError()
				} catch let error as GitHubCLIJSONBridgeError {
					throw GitHubRepositoryContextQueryFailure(operation: .pullRequestList, error: error)
				}
				resolvedLists.append(GitHubPullRequestList(title: configuration.title, pullRequests: pullRequests))
			}
			return .ready(GitHubRepositoryContext(
				nameWithOwner: repository.nameWithOwner,
				url: repository.url,
				defaultBranchName: repository.defaultBranchRef?.name,
				currentBranchPullRequest: currentPullRequest,
				pullRequestLists: resolvedLists
			))
		} catch is CancellationError {
			throw CancellationError()
		} catch let error as GitHubRepositoryContextQueryFailure {
			return .queryFailed(operation: error.operation, error: error.error)
		} catch let error as GitHubCLIJSONBridgeError {
			return .queryFailed(operation: .repository, error: error)
		} catch {
			return .queryFailed(operation: .repository, error: .unavailable)
		}
	}

	private func currentBranchPullRequest(workspaceURL: URL) async throws -> GitHubPullRequestSummary? {
		do {
			return try await bridge.execute(.pullRequest(number: nil, fields: Self.pullRequestFields), as: GitHubPullRequestSummary.self, workspaceURL: workspaceURL)
		} catch is CancellationError {
			throw CancellationError()
		} catch let error as GitHubCLIJSONBridgeError where Self.isEmptyPullRequest(error) {
			return nil
		} catch let error as GitHubCLIJSONBridgeError {
			throw GitHubRepositoryContextQueryFailure(operation: .currentBranchPullRequest, error: error)
		}
	}

	private static func isEmptyPullRequest(_ error: GitHubCLIJSONBridgeError) -> Bool {
		let output: String
		switch error {
		case let .processFailure(diagnostics), let .invalidJSON(diagnostics):
			output = diagnostics.standardOutput + "\n" + diagnostics.standardError
		default:
			return false
		}
		let normalized = output.lowercased()
		return normalized.contains("no pull requests found") || normalized.contains("no pull request found")
	}
}

private struct GitHubRepositoryResponse: Decodable, Sendable {
	let nameWithOwner: String
	let url: URL
	let defaultBranchRef: GitHubDefaultBranchResponse?
}

private struct GitHubDefaultBranchResponse: Decodable, Sendable {
	let name: String
}

private struct GitHubRepositoryContextQueryFailure: Error {
	let operation: GitHubRepositoryContextQueryOperation
	let error: GitHubCLIJSONBridgeError
}
