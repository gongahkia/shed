import Foundation

public enum GitHubReviewSubmissionAction: String, Equatable, Sendable {
	case approve
	case comment
	case requestChanges

	var flag: String {
		switch self {
		case .approve: "--approve"
		case .comment: "--comment"
		case .requestChanges: "--request-changes"
		}
	}

	var confirmationTitle: String {
		switch self {
		case .approve: "Approve pull request"
		case .comment: "Submit review comment"
		case .requestChanges: "Request changes"
		}
	}
}

public struct GitHubReviewSubmissionIntent: Equatable, Sendable {
	public let pullRequestNumber: Int
	public let action: GitHubReviewSubmissionAction
	public let body: String

	public init(pullRequestNumber: Int, action: GitHubReviewSubmissionAction, body: String) throws {
		guard pullRequestNumber > 0, !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw GitHubCLIJSONBridgeError.invalidCommand }
		self.pullRequestNumber = pullRequestNumber
		self.action = action
		self.body = body
	}

	public init(draft: GitHubReviewDraft, action: GitHubReviewSubmissionAction) throws {
		guard case .review = draft.target else { throw GitHubCLIJSONBridgeError.invalidCommand }
		try self.init(pullRequestNumber: draft.pullRequestNumber, action: action, body: draft.body)
	}
}

public struct GitHubReviewSubmissionConfirmation: Equatable, Sendable {
	public let title: String
	public let action: GitHubReviewSubmissionAction
	fileprivate let intent: GitHubReviewSubmissionIntent

	fileprivate init(intent: GitHubReviewSubmissionIntent) {
		title = intent.action.confirmationTitle
		action = intent.action
		self.intent = intent
	}
}

public enum GitHubReviewSubmissionPlanner {
	public static func requestConfirmation(for intent: GitHubReviewSubmissionIntent) -> GitHubReviewSubmissionConfirmation {
		GitHubReviewSubmissionConfirmation(intent: intent)
	}
}

public enum GitHubReviewSubmissionResult: Equatable, Sendable {
	case cancelled
	case submitted(action: GitHubReviewSubmissionAction, refreshed: GitHubPullRequestDetailQueryState)
	case failed(action: GitHubReviewSubmissionAction, error: GitHubCLIJSONBridgeError)
}

public struct GitHubReviewSubmissionService: Sendable {
	private let bridge: GitHubCLIJSONBridge
	private let detailQuery: GitHubPullRequestDetailQuery

	public init(bridge: GitHubCLIJSONBridge) {
		self.bridge = bridge
		detailQuery = GitHubPullRequestDetailQuery(bridge: bridge)
	}

	public func cancel(_: GitHubReviewSubmissionConfirmation) -> GitHubReviewSubmissionResult {
		.cancelled
	}

	public func submit(_ confirmation: GitHubReviewSubmissionConfirmation, repository: GitHubRepositoryName, workspaceURL: URL) async throws -> GitHubReviewSubmissionResult {
		do {
			try await bridge.executeRequest(GitHubCLIReviewSubmissionRequest(repository: repository, intent: confirmation.intent), workspaceURL: workspaceURL)
			let refreshed = try await detailQuery.refresh(number: confirmation.intent.pullRequestNumber, workspaceURL: workspaceURL)
			return .submitted(action: confirmation.action, refreshed: refreshed)
		} catch is CancellationError {
			throw CancellationError()
		} catch let error as GitHubCLIJSONBridgeError {
			return .failed(action: confirmation.action, error: error)
		} catch {
			return .failed(action: confirmation.action, error: .unavailable)
		}
	}
}

private struct GitHubCLIReviewSubmissionRequest: GitHubCLIJSONRequest {
	let repository: GitHubRepositoryName
	let intent: GitHubReviewSubmissionIntent

	func arguments() throws -> [String] {
		guard intent.pullRequestNumber > 0, !intent.body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw GitHubCLIJSONBridgeError.invalidCommand }
		return ["pr", "review", String(intent.pullRequestNumber), "--repo", "\(repository.owner)/\(repository.name)", "--body", intent.body, intent.action.flag]
	}
}
