import Foundation
import ItsyEditor

public enum GitHubPullRequestBranchValidation: Equatable, Sendable {
	case detached
	case unpublished(GitHubCLIReferenceName)
	case published(GitHubCLIReferenceName)

	public static func make(status: GitStatus) throws -> GitHubPullRequestBranchValidation {
		guard let head = status.branch.head else { return .detached }
		let branch = try GitHubCLIReferenceName(head)
		return status.branch.upstream == nil ? .unpublished(branch) : .published(branch)
	}
}

public struct GitHubAccountName: Equatable, Sendable {
	public let value: String

	public init(_ value: String) throws {
		guard !value.isEmpty, value.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" }) else { throw GitHubCLIJSONBridgeError.invalidCommand }
		self.value = value
	}
}

public struct GitHubPullRequestReviewer: Equatable, Sendable {
	public let value: String

	public init(_ value: String) throws {
		let parts = value.split(separator: "/", omittingEmptySubsequences: false)
		guard (value == "@copilot" || parts.count == 1 || parts.count == 2), !parts.isEmpty,
			parts.allSatisfy({ !$0.isEmpty && $0.allSatisfy { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" } })
		else { throw GitHubCLIJSONBridgeError.invalidCommand }
		self.value = value
	}
}

public struct GitHubPullRequestTemplateLoader {
	public let workspaceURL: URL
	private let fileManager: FileManager

	public init(workspaceURL: URL, fileManager: FileManager = .default) {
		self.workspaceURL = workspaceURL.standardizedFileURL
		self.fileManager = fileManager
	}

	public func load(relativePath: String) throws -> String? {
		guard !relativePath.hasPrefix("/"), relativePath.split(separator: "/", omittingEmptySubsequences: false).allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." }) else { throw GitHubCLIJSONBridgeError.invalidCommand }
		let url = workspaceURL.appendingPathComponent(relativePath).standardizedFileURL
		guard url.path.hasPrefix(workspaceURL.path + "/"), fileManager.fileExists(atPath: url.path) else { return nil }
		return try String(contentsOf: url, encoding: .utf8)
	}

	public func loadDefault() throws -> String? {
		for path in [".github/PULL_REQUEST_TEMPLATE.md", ".github/pull_request_template.md", "PULL_REQUEST_TEMPLATE.md", "docs/PULL_REQUEST_TEMPLATE.md"] {
			if let template = try load(relativePath: path) { return template }
		}
		return nil
	}
}

public struct GitHubPullRequestCreateInput: Equatable, Sendable {
	public let repository: GitHubRepositoryName
	public let base: GitHubCLIReferenceName
	public let headOwner: GitHubAccountName?
	public let headBranch: GitHubCLIReferenceName
	public let title: String
	public let body: String
	public let isDraft: Bool
	public let reviewers: [GitHubPullRequestReviewer]

	public init(repository: GitHubRepositoryName, base: GitHubCLIReferenceName, branch: GitHubPullRequestBranchValidation, headOwner: GitHubAccountName? = nil, title: String, body: String, isDraft: Bool, reviewers: [GitHubPullRequestReviewer] = []) throws {
		guard case let .published(branch) = branch,
			!title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
			!body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
			Set(reviewers.map(\.value)).count == reviewers.count
		else { throw GitHubCLIJSONBridgeError.invalidCommand }
		self.repository = repository
		self.base = base
		self.headOwner = headOwner
		headBranch = branch
		self.title = title
		self.body = body
		self.isDraft = isDraft
		self.reviewers = reviewers
	}

	var headArgument: String {
		headOwner.map { "\($0.value):\(headBranch.value)" } ?? headBranch.value
	}
}

public struct GitHubPullRequestUpdateInput: Equatable, Sendable {
	public let repository: GitHubRepositoryName
	public let pullRequestNumber: Int
	public let title: String
	public let body: String
	public let base: GitHubCLIReferenceName?
	public let reviewers: [GitHubPullRequestReviewer]

	public init(repository: GitHubRepositoryName, pullRequestNumber: Int, title: String, body: String, base: GitHubCLIReferenceName? = nil, reviewers: [GitHubPullRequestReviewer] = []) throws {
		guard pullRequestNumber > 0,
			!title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
			!body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
			Set(reviewers.map(\.value)).count == reviewers.count
		else { throw GitHubCLIJSONBridgeError.invalidCommand }
		self.repository = repository
		self.pullRequestNumber = pullRequestNumber
		self.title = title
		self.body = body
		self.base = base
		self.reviewers = reviewers
	}
}

public enum GitHubPullRequestComposeResult: Equatable, Sendable {
	case created(URL)
	case updated(GitHubPullRequestDetailQueryState)
	case failed(GitHubCLIJSONBridgeError)
}

public struct GitHubPullRequestComposer: Sendable {
	private let bridge: GitHubCLIJSONBridge
	private let detailQuery: GitHubPullRequestDetailQuery

	public init(bridge: GitHubCLIJSONBridge) {
		self.bridge = bridge
		detailQuery = GitHubPullRequestDetailQuery(bridge: bridge)
	}

	public func create(_ input: GitHubPullRequestCreateInput, workspaceURL: URL) async throws -> GitHubPullRequestComposeResult {
		do {
			let output = try await bridge.executeTextRequest(GitHubCLIPullRequestCreateRequest(input: input), workspaceURL: workspaceURL)
			guard let url = URL(string: output.trimmingCharacters(in: .whitespacesAndNewlines)) else { return .failed(.unavailable) }
			return .created(url)
		} catch is CancellationError {
			throw CancellationError()
		} catch let error as GitHubCLIJSONBridgeError {
			return .failed(error)
		} catch {
			return .failed(.unavailable)
		}
	}

	public func update(_ input: GitHubPullRequestUpdateInput, workspaceURL: URL) async throws -> GitHubPullRequestComposeResult {
		do {
			try await bridge.executeRequest(GitHubCLIPullRequestUpdateRequest(input: input), workspaceURL: workspaceURL)
			return .updated(try await detailQuery.refresh(number: input.pullRequestNumber, workspaceURL: workspaceURL))
		} catch is CancellationError {
			throw CancellationError()
		} catch let error as GitHubCLIJSONBridgeError {
			return .failed(error)
		} catch {
			return .failed(.unavailable)
		}
	}

	public func retryCreate(_ input: GitHubPullRequestCreateInput, workspaceURL: URL) async throws -> GitHubPullRequestComposeResult {
		try await create(input, workspaceURL: workspaceURL)
	}

	public func retryUpdate(_ input: GitHubPullRequestUpdateInput, workspaceURL: URL) async throws -> GitHubPullRequestComposeResult {
		try await update(input, workspaceURL: workspaceURL)
	}
}

private struct GitHubCLIPullRequestCreateRequest: GitHubCLIJSONRequest {
	let input: GitHubPullRequestCreateInput

	func arguments() throws -> [String] {
		var arguments = ["pr", "create", "--repo", "\(input.repository.owner)/\(input.repository.name)", "--base", input.base.value, "--head", input.headArgument, "--title", input.title, "--body", input.body]
		if input.isDraft { arguments.append("--draft") }
		for reviewer in input.reviewers { arguments += ["--reviewer", reviewer.value] }
		return arguments
	}
}

private struct GitHubCLIPullRequestUpdateRequest: GitHubCLIJSONRequest {
	let input: GitHubPullRequestUpdateInput

	func arguments() throws -> [String] {
		var arguments = ["pr", "edit", String(input.pullRequestNumber), "--repo", "\(input.repository.owner)/\(input.repository.name)", "--title", input.title, "--body", input.body]
		if let base = input.base { arguments += ["--base", base.value] }
		for reviewer in input.reviewers { arguments += ["--add-reviewer", reviewer.value] }
		return arguments
	}
}
