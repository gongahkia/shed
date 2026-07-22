import Foundation

public enum GitRemoteOperation: Equatable, Sendable {
	case fetch
	case pull
	case push
}

public struct GitRemoteCommandResult: Equatable, Sendable {
	public var exitStatus: Int32
	public var output: String
	public var wasCancelled: Bool

	public init(exitStatus: Int32, output: String, wasCancelled: Bool = false) {
		self.exitStatus = exitStatus
		self.output = output
		self.wasCancelled = wasCancelled
	}
}

public enum GitRemoteFailure: Equatable, Sendable {
	case authenticationRequired
	case nonFastForward
	case networkUnavailable
	case cancelled
	case commandFailed(String)
}

public enum GitRemoteOperationOutcome: Equatable, Sendable {
	case succeeded(output: String)
	case failed(GitRemoteFailure)
}

public protocol GitRemoteCommandRunning: Sendable {
	func run(operation: GitRemoteOperation, root: URL) async -> GitRemoteCommandResult
}

public enum GitRemoteOperationClassifier {
	public static func classify(_ result: GitRemoteCommandResult) -> GitRemoteOperationOutcome {
		if result.wasCancelled {
			return .failed(.cancelled)
		}
		guard result.exitStatus != 0 else {
			return .succeeded(output: result.output)
		}
		let output = result.output.lowercased()
		if output.contains("authentication failed") || output.contains("could not read username") || output.contains("terminal prompts disabled") || output.contains("could not authenticate") {
			return .failed(.authenticationRequired)
		}
		if output.contains("non-fast-forward") || output.contains("[rejected]") || output.contains("fetch first") {
			return .failed(.nonFastForward)
		}
		if output.contains("could not resolve host") || output.contains("failed to connect") || output.contains("network is unreachable") || output.contains("connection timed out") {
			return .failed(.networkUnavailable)
		}
		return .failed(.commandFailed(result.output))
	}
}

public actor GitRemoteOperationCoordinator {
	private var generation = 0

	public init() {}

	public func cancel() {
		generation += 1
	}

	public func run(operation: GitRemoteOperation, root: URL, runner: any GitRemoteCommandRunning) async -> GitRemoteOperationOutcome? {
		let expectedGeneration = generation
		let result = await runner.run(operation: operation, root: root)
		guard !Task.isCancelled, generation == expectedGeneration else {
			return nil
		}
		return GitRemoteOperationClassifier.classify(result)
	}
}
