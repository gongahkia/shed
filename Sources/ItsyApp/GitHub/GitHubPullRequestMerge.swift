import Foundation
import ItsyEditor

public enum GitHubPullRequestMergeMethod: Equatable, Sendable {
	case merge
	case squash
	case rebase
	case automatic

	var flag: String {
		switch self {
		case .merge: "--merge"
		case .squash: "--squash"
		case .rebase: "--rebase"
		case .automatic: "--auto"
		}
	}
}

public struct GitHubPullRequestMergeMetadata: Equatable, Sendable {
	public let mergeCommitAllowed: Bool
	public let squashMergeAllowed: Bool
	public let rebaseMergeAllowed: Bool
	public let autoMergeAllowed: Bool
	public let isInMergeQueue: Bool

	public var compatibleMethods: [GitHubPullRequestMergeMethod] {
		var methods: [GitHubPullRequestMergeMethod] = []
		if mergeCommitAllowed { methods.append(.merge) }
		if squashMergeAllowed { methods.append(.squash) }
		if rebaseMergeAllowed { methods.append(.rebase) }
		if autoMergeAllowed || isInMergeQueue { methods.append(.automatic) }
		return methods
	}
}

public enum GitHubPullRequestMergeMetadataState: Equatable, Sendable {
	case ready(GitHubPullRequestMergeMetadata)
	case failed(GitHubCLIJSONBridgeError)
}

public struct GitHubPullRequestMergeMetadataQuery: Sendable {
	private let bridge: GitHubCLIJSONBridge

	public init(bridge: GitHubCLIJSONBridge) {
		self.bridge = bridge
	}

	public func refresh(repository: GitHubRepositoryName, pullRequestNumber: Int, workspaceURL: URL) async throws -> GitHubPullRequestMergeMetadataState {
		guard pullRequestNumber > 0 else { return .failed(.invalidCommand) }
		do {
			let response = try await bridge.executeRequest(GitHubCLIPullRequestMergeMetadataRequest(repository: repository, pullRequestNumber: pullRequestNumber), as: GitHubPullRequestMergeMetadataResponse.self, workspaceURL: workspaceURL)
			return .ready(GitHubPullRequestMergeMetadata(
				mergeCommitAllowed: response.data.repository.mergeCommitAllowed,
				squashMergeAllowed: response.data.repository.squashMergeAllowed,
				rebaseMergeAllowed: response.data.repository.rebaseMergeAllowed,
				autoMergeAllowed: response.data.repository.autoMergeAllowed,
				isInMergeQueue: response.data.repository.pullRequest.isInMergeQueue
			))
		} catch is CancellationError {
			throw CancellationError()
		} catch let error as GitHubCLIJSONBridgeError {
			return .failed(error)
		} catch {
			return .failed(.unavailable)
		}
	}
}

public enum GitHubPullRequestMergePreflight: Equatable, Sendable {
	case ready
	case alreadyMerged
	case conflicting
	case protected
	case mergeQueue
	case unsupportedMethod

	public static func make(detail: GitHubPullRequestDetail, metadata: GitHubPullRequestMergeMetadata, method: GitHubPullRequestMergeMethod) -> GitHubPullRequestMergePreflight {
		if detail.state == "MERGED" { return .alreadyMerged }
		if detail.mergeable == "CONFLICTING" || detail.mergeStateStatus == "DIRTY" { return .conflicting }
		if detail.mergeStateStatus == "BLOCKED" { return .protected }
		if metadata.isInMergeQueue { return .mergeQueue }
		return metadata.compatibleMethods.contains(method) ? .ready : .unsupportedMethod
	}
}

public struct GitHubPullRequestMergeIntent: Equatable, Sendable {
	public let repository: GitHubRepositoryName
	public let pullRequestNumber: Int
	public let method: GitHubPullRequestMergeMethod
	public let headOID: GitHubGitObjectID?

	public init(repository: GitHubRepositoryName, pullRequestNumber: Int, method: GitHubPullRequestMergeMethod, headOID: GitHubGitObjectID? = nil) throws {
		guard pullRequestNumber > 0 else { throw GitHubCLIJSONBridgeError.invalidCommand }
		self.repository = repository
		self.pullRequestNumber = pullRequestNumber
		self.method = method
		self.headOID = headOID
	}
}

public struct GitHubPullRequestMergeConfirmation: Equatable, Sendable {
	public let method: GitHubPullRequestMergeMethod
	fileprivate let intent: GitHubPullRequestMergeIntent

	fileprivate init(intent: GitHubPullRequestMergeIntent) {
		method = intent.method
		self.intent = intent
	}
}

public enum GitHubPullRequestMergePlanner {
	public static func requestConfirmation(for intent: GitHubPullRequestMergeIntent) -> GitHubPullRequestMergeConfirmation {
		GitHubPullRequestMergeConfirmation(intent: intent)
	}
}

public enum GitHubPullRequestCleanupOption: Equatable, Sendable {
	case keepLocalBranch
	case deleteLocalBranch(GitHubCLIReferenceName)
}

public enum GitHubPullRequestCleanupResult: Equatable, Sendable {
	case notRequested
	case skippedNotMerged
	case deleted
	case failed
}

public protocol GitHubLocalBranchCleaning: Sendable {
	func deleteMergedBranch(_ branch: GitHubCLIReferenceName, workspaceURL: URL) async throws
}

public struct GitHubLocalBranchCleaner: GitHubLocalBranchCleaning {
	public init() {}

	public func deleteMergedBranch(_ branch: GitHubCLIReferenceName, workspaceURL: URL) async throws {
		let repository = GitRepository(root: workspaceURL)
		guard try repository.status().branch.head != branch.value else { throw GitHubCLIJSONBridgeError.invalidCommand }
		try repository.deleteBranch(branch.value, force: false)
	}
}

public protocol GitHubLocalGitRefreshing: Sendable {
	func refresh(workspaceURL: URL) async throws -> GitStatus
}

public struct GitHubLocalGitStatusRefresher: GitHubLocalGitRefreshing {
	public init() {}

	public func refresh(workspaceURL: URL) async throws -> GitStatus {
		try GitRepository(root: workspaceURL).status()
	}
}

public enum GitHubPullRequestMergeResult: Equatable, Sendable {
	case cancelled
	case notMergeable(GitHubPullRequestMergePreflight)
	case failed(GitHubCLIJSONBridgeError)
	case recovered(error: GitHubCLIJSONBridgeError, pullRequest: GitHubPullRequestDetailQueryState, gitStatus: GitStatus?, cleanup: GitHubPullRequestCleanupResult)
	case completed(pullRequest: GitHubPullRequestDetailQueryState, gitStatus: GitStatus?, cleanup: GitHubPullRequestCleanupResult)
}

public struct GitHubPullRequestMergeService: Sendable {
	private let bridge: GitHubCLIJSONBridge
	private let detailQuery: GitHubPullRequestDetailQuery
	private let gitRefresher: any GitHubLocalGitRefreshing
	private let branchCleaner: any GitHubLocalBranchCleaning

	public init(bridge: GitHubCLIJSONBridge, gitRefresher: any GitHubLocalGitRefreshing = GitHubLocalGitStatusRefresher(), branchCleaner: any GitHubLocalBranchCleaning = GitHubLocalBranchCleaner()) {
		self.bridge = bridge
		detailQuery = GitHubPullRequestDetailQuery(bridge: bridge)
		self.gitRefresher = gitRefresher
		self.branchCleaner = branchCleaner
	}

	public func cancel(_: GitHubPullRequestMergeConfirmation) -> GitHubPullRequestMergeResult {
		.cancelled
	}

	public func merge(_ confirmation: GitHubPullRequestMergeConfirmation, detail: GitHubPullRequestDetail, metadata: GitHubPullRequestMergeMetadata, cleanup: GitHubPullRequestCleanupOption, workspaceURL: URL) async throws -> GitHubPullRequestMergeResult {
		let preflight = GitHubPullRequestMergePreflight.make(detail: detail, metadata: metadata, method: confirmation.method)
		guard preflight == .ready else { return .notMergeable(preflight) }
		do {
			try await bridge.executeRequest(GitHubCLIPullRequestMergeRequest(intent: confirmation.intent), workspaceURL: workspaceURL)
			let refreshedPullRequest = try await detailQuery.refresh(number: confirmation.intent.pullRequestNumber, workspaceURL: workspaceURL)
			let gitStatus: GitStatus?
			do {
				gitStatus = try await gitRefresher.refresh(workspaceURL: workspaceURL)
			} catch is CancellationError {
				throw CancellationError()
			} catch {
				gitStatus = nil
			}
			let cleanupResult = try await performCleanup(after: refreshedPullRequest, option: cleanup, workspaceURL: workspaceURL)
			return .completed(pullRequest: refreshedPullRequest, gitStatus: gitStatus, cleanup: cleanupResult)
		} catch is CancellationError {
			throw CancellationError()
		} catch let error as GitHubCLIJSONBridgeError {
			return try await recover(after: error, confirmation: confirmation, cleanup: cleanup, workspaceURL: workspaceURL)
		} catch {
			return try await recover(after: .unavailable, confirmation: confirmation, cleanup: cleanup, workspaceURL: workspaceURL)
		}
	}

	private func recover(after error: GitHubCLIJSONBridgeError, confirmation: GitHubPullRequestMergeConfirmation, cleanup: GitHubPullRequestCleanupOption, workspaceURL: URL) async throws -> GitHubPullRequestMergeResult {
		let refreshedPullRequest = try await detailQuery.refresh(number: confirmation.intent.pullRequestNumber, workspaceURL: workspaceURL)
		let gitStatus: GitStatus?
		do {
			gitStatus = try await gitRefresher.refresh(workspaceURL: workspaceURL)
		} catch is CancellationError {
			throw CancellationError()
		} catch {
			gitStatus = nil
		}
		let cleanupResult = try await performCleanup(after: refreshedPullRequest, option: cleanup, workspaceURL: workspaceURL)
		return .recovered(error: error, pullRequest: refreshedPullRequest, gitStatus: gitStatus, cleanup: cleanupResult)
	}

	private func performCleanup(after state: GitHubPullRequestDetailQueryState, option: GitHubPullRequestCleanupOption, workspaceURL: URL) async throws -> GitHubPullRequestCleanupResult {
		guard case let .deleteLocalBranch(branch) = option else { return .notRequested }
		guard case let .ready(detail) = state, detail.state == "MERGED" else { return .skippedNotMerged }
		do {
			try await branchCleaner.deleteMergedBranch(branch, workspaceURL: workspaceURL)
			return .deleted
		} catch is CancellationError {
			throw CancellationError()
		} catch {
			return .failed
		}
	}
}

private struct GitHubCLIPullRequestMergeMetadataRequest: GitHubCLIJSONRequest {
	let repository: GitHubRepositoryName
	let pullRequestNumber: Int

	func arguments() throws -> [String] {
		guard pullRequestNumber > 0 else { throw GitHubCLIJSONBridgeError.invalidCommand }
		return ["api", "graphql", "-F", "owner=\(repository.owner)", "-F", "name=\(repository.name)", "-F", "number=\(pullRequestNumber)", "-f", "query=\(Self.query)"]
	}

	private static let query = "query($owner: String!, $name: String!, $number: Int!) { repository(owner: $owner, name: $name) { mergeCommitAllowed squashMergeAllowed rebaseMergeAllowed autoMergeAllowed pullRequest(number: $number) { isInMergeQueue } } }"
}

private struct GitHubCLIPullRequestMergeRequest: GitHubCLIJSONRequest {
	let intent: GitHubPullRequestMergeIntent

	func arguments() throws -> [String] {
		var arguments = ["pr", "merge", String(intent.pullRequestNumber), "--repo", "\(intent.repository.owner)/\(intent.repository.name)"]
		if let headOID = intent.headOID { arguments += ["--match-head-commit", headOID.value] }
		arguments.append(intent.method.flag)
		return arguments
	}
}

private struct GitHubPullRequestMergeMetadataResponse: Decodable, Sendable {
	let data: GitHubPullRequestMergeMetadataData
}

private struct GitHubPullRequestMergeMetadataData: Decodable, Sendable {
	let repository: GitHubPullRequestMergeMetadataRepository
}

private struct GitHubPullRequestMergeMetadataRepository: Decodable, Sendable {
	let mergeCommitAllowed: Bool
	let squashMergeAllowed: Bool
	let rebaseMergeAllowed: Bool
	let autoMergeAllowed: Bool
	let pullRequest: GitHubPullRequestMergeMetadataPullRequest
}

private struct GitHubPullRequestMergeMetadataPullRequest: Decodable, Sendable {
	let isInMergeQueue: Bool
}
