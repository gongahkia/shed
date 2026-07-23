import Foundation

public struct GitHubPullRequestRepositoryOwner: Codable, Equatable, Sendable {
	public let login: String
}

public struct GitHubPullRequestCommit: Codable, Equatable, Sendable {
	public let oid: String
	public let messageHeadline: String
	public let authoredDate: String?
	public let committedDate: String?
}

public struct GitHubPullRequestChangedFile: Codable, Equatable, Sendable {
	public let path: String
	public let additions: Int
	public let deletions: Int
}

public struct GitHubPullRequestCheck: Codable, Equatable, Sendable {
	public let name: String
	public let state: String
	public let bucket: String?
	public let workflow: String?
	public let link: URL?
}

public enum GitHubPullRequestChecks: Equatable, Sendable {
	case available([GitHubPullRequestCheck])
	case unavailable
}

public struct GitHubPullRequestDetail: Decodable, Equatable, Sendable {
	public let number: Int
	public let url: URL
	public let title: String
	public let body: String?
	public let state: String
	public let isDraft: Bool
	public let headRefName: String
	public let headRepositoryOwner: GitHubPullRequestRepositoryOwner?
	public let baseRefName: String
	public let reviewDecision: String?
	public let mergeable: String?
	public let mergeStateStatus: String?
	public let commits: [GitHubPullRequestCommit]
	public let files: [GitHubPullRequestChangedFile]
	public let checks: GitHubPullRequestChecks

	private enum CodingKeys: String, CodingKey {
		case number, url, title, body, state, isDraft, headRefName, headRepositoryOwner, baseRefName, reviewDecision, mergeable, mergeStateStatus, commits, files
	}

	public init(from decoder: Decoder) throws {
		let values = try decoder.container(keyedBy: CodingKeys.self)
		number = try values.decode(Int.self, forKey: .number)
		url = try values.decode(URL.self, forKey: .url)
		title = try values.decode(String.self, forKey: .title)
		body = try values.decodeIfPresent(String.self, forKey: .body)
		state = try values.decode(String.self, forKey: .state)
		isDraft = try values.decode(Bool.self, forKey: .isDraft)
		headRefName = try values.decode(String.self, forKey: .headRefName)
		headRepositoryOwner = try values.decodeIfPresent(GitHubPullRequestRepositoryOwner.self, forKey: .headRepositoryOwner)
		baseRefName = try values.decode(String.self, forKey: .baseRefName)
		reviewDecision = try values.decodeIfPresent(String.self, forKey: .reviewDecision)
		mergeable = try values.decodeIfPresent(String.self, forKey: .mergeable)
		mergeStateStatus = try values.decodeIfPresent(String.self, forKey: .mergeStateStatus)
		commits = try values.decodeIfPresent([GitHubPullRequestCommit].self, forKey: .commits) ?? []
		files = try values.decodeIfPresent([GitHubPullRequestChangedFile].self, forKey: .files) ?? []
		checks = .unavailable
	}

	public init(number: Int, url: URL, title: String, body: String?, state: String, isDraft: Bool, headRefName: String, headRepositoryOwner: GitHubPullRequestRepositoryOwner?, baseRefName: String, reviewDecision: String?, mergeable: String?, mergeStateStatus: String?, commits: [GitHubPullRequestCommit], files: [GitHubPullRequestChangedFile], checks: GitHubPullRequestChecks) {
		self.number = number
		self.url = url
		self.title = title
		self.body = body
		self.state = state
		self.isDraft = isDraft
		self.headRefName = headRefName
		self.headRepositoryOwner = headRepositoryOwner
		self.baseRefName = baseRefName
		self.reviewDecision = reviewDecision
		self.mergeable = mergeable
		self.mergeStateStatus = mergeStateStatus
		self.commits = commits
		self.files = files
		self.checks = checks
	}

	func withChecks(_ checks: GitHubPullRequestChecks) -> GitHubPullRequestDetail {
		GitHubPullRequestDetail(number: number, url: url, title: title, body: body, state: state, isDraft: isDraft, headRefName: headRefName, headRepositoryOwner: headRepositoryOwner, baseRefName: baseRefName, reviewDecision: reviewDecision, mergeable: mergeable, mergeStateStatus: mergeStateStatus, commits: commits, files: files, checks: checks)
	}
}

public struct GitHubPullRequestFilePage: Equatable, Sendable {
	public let page: Int
	public let pageCount: Int
	public let files: [GitHubPullRequestChangedFile]

	public static func make(files: [GitHubPullRequestChangedFile], page: Int, pageSize: Int = 50) -> GitHubPullRequestFilePage? {
		guard pageSize > 0 else { return nil }
		let pageCount = max(1, Int(ceil(Double(files.count) / Double(pageSize))))
		guard (0 ..< pageCount).contains(page) else { return nil }
		let start = page * pageSize
		return GitHubPullRequestFilePage(page: page, pageCount: pageCount, files: Array(files.dropFirst(start).prefix(pageSize)))
	}
}

public enum GitHubPullRequestDetailQueryState: Equatable, Sendable {
	case ready(GitHubPullRequestDetail)
	case failed(GitHubCLIJSONBridgeError)
}

public struct GitHubPullRequestDetailQuery: Sendable {
	private static let detailFields: [GitHubCLIPullRequestJSONField] = [.number, .url, .title, .body, .state, .isDraft, .headRefName, .headRepositoryOwner, .baseRefName, .reviewDecision, .mergeable, .mergeStateStatus, .commits, .files]
	private static let checkFields: [GitHubCLIPullRequestCheckJSONField] = [.name, .state, .bucket, .workflow, .link]
	private let bridge: GitHubCLIJSONBridge

	public init(bridge: GitHubCLIJSONBridge) {
		self.bridge = bridge
	}

	public func refresh(number: Int, workspaceURL: URL) async throws -> GitHubPullRequestDetailQueryState {
		guard number > 0 else { return .failed(.invalidCommand) }
		do {
			let detail = try await bridge.execute(.pullRequest(number: number, fields: Self.detailFields), as: GitHubPullRequestDetail.self, workspaceURL: workspaceURL)
			let checks: GitHubPullRequestChecks
			do {
				checks = .available(try await bridge.execute(.pullRequestChecks(number: number, fields: Self.checkFields), as: [GitHubPullRequestCheck].self, workspaceURL: workspaceURL))
			} catch is CancellationError {
				throw CancellationError()
			} catch {
				checks = .unavailable
			}
			return .ready(detail.withChecks(checks))
		} catch is CancellationError {
			throw CancellationError()
		} catch let error as GitHubCLIJSONBridgeError {
			return .failed(error)
		} catch {
			return .failed(.unavailable)
		}
	}
}

public struct GitHubPullRequestDiffContext: Equatable, Sendable {
	public let pullRequestNumber: Int
	public let file: GitHubPullRequestChangedFile
	public let localFileURL: URL?
	public let baseRefName: String
	public let headRefName: String

	public static func make(detail: GitHubPullRequestDetail, file: GitHubPullRequestChangedFile, workspaceURL: URL, fileManager: FileManager = .default) -> GitHubPullRequestDiffContext {
		let root = workspaceURL.standardizedFileURL
		let candidate = root.appendingPathComponent(file.path).standardizedFileURL
		let localFileURL = candidate.path.hasPrefix(root.path + "/") && fileManager.fileExists(atPath: candidate.path) ? candidate : nil
		return GitHubPullRequestDiffContext(pullRequestNumber: detail.number, file: file, localFileURL: localFileURL, baseRefName: detail.baseRefName, headRefName: detail.headRefName)
	}
}
