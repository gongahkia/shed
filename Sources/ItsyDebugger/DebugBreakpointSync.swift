import Foundation
import ItsyDAP

public struct DebugBreakpointVerification: Equatable, Sendable {
	public var sourceURL: URL
	public var requested: SourceBreakpoint
	public var adapterBreakpoint: DAPBreakpoint

	public init(sourceURL: URL, requested: SourceBreakpoint, adapterBreakpoint: DAPBreakpoint) {
		self.sourceURL = sourceURL
		self.requested = requested
		self.adapterBreakpoint = adapterBreakpoint
	}
}

public actor DebugBreakpointVerificationStore {
	private var values: [DebugBreakpointVerification] = []

	public init() {}

	public func replace(_ values: [DebugBreakpointVerification]) {
		self.values = values
	}

	public func snapshot() -> [DebugBreakpointVerification] {
		values
	}

	public func apply(_ breakpoint: DAPBreakpoint) {
		let hasLocation = breakpoint.source?.path != nil && breakpoint.line != nil
		guard breakpoint.id != nil || hasLocation else {
			return
		}
		guard let index = values.firstIndex(where: { verification in
			if let id = breakpoint.id, verification.adapterBreakpoint.id == id {
				return true
			}
			guard let sourcePath = breakpoint.source?.path,
			      let line = breakpoint.line,
			      sourcePath == verification.sourceURL.path
			else {
				return false
			}
			if line != verification.adapterBreakpoint.line, line != verification.requested.line {
				return false
			}
			return true
		}) else {
			return
		}
		values[index].adapterBreakpoint = breakpoint
	}
}

public enum DebugBreakpointSyncError: Error, Equatable, Sendable {
	case missingResponseBody
}

public enum DebugBreakpointSync {
	public static func syncPersistedBreakpoints(
		from store: BreakpointStore,
		using client: DAPClientSession,
		workspaceRoot: URL
	) async throws -> [DebugBreakpointVerification] {
		try await store.load()
		let snapshot = await store.snapshot()
		var verification: [DebugBreakpointVerification] = []
		for (sourceURL, arguments) in setBreakpointsRequests(from: snapshot, workspaceRoot: workspaceRoot) {
			let response = try await client.setBreakpoints(arguments)
			verification += try reconcile(sourceURL: sourceURL, requested: arguments.breakpoints ?? [], response: response)
		}
		return verification
	}

	public static func setBreakpointsArguments(
		from snapshot: [URL: [SourceBreakpoint]],
		workspaceRoot: URL
	) -> [DAPSetBreakpointsArguments] {
		setBreakpointsRequests(from: snapshot, workspaceRoot: workspaceRoot).map(\.arguments)
	}

	private static func setBreakpointsRequests(
		from snapshot: [URL: [SourceBreakpoint]],
		workspaceRoot: URL
	) -> [(sourceURL: URL, arguments: DAPSetBreakpointsArguments)] {
		let rootPath = workspaceRoot.standardizedFileURL.path
		let rootPrefix = rootPath.hasSuffix("/") ? rootPath : rootPath + "/"
		return snapshot
			.compactMap { url, breakpoints -> (URL, [SourceBreakpoint])? in
				let fileURL = url.standardizedFileURL
				guard !breakpoints.isEmpty,
				      fileURL.path == rootPath || fileURL.path.hasPrefix(rootPrefix)
				else {
					return nil
				}
				return (fileURL, breakpoints.sorted(by: breakpointOrder))
			}
			.sorted { $0.0.path < $1.0.path }
			.map { url, breakpoints in
				(url, DAPSetBreakpointsArguments(
					source: DAPSource(name: url.lastPathComponent, path: url.path),
					breakpoints: breakpoints
				))
			}
	}

	private static func reconcile(sourceURL: URL, requested: [SourceBreakpoint], response: DAPResponse) throws -> [DebugBreakpointVerification] {
		guard let body = response.body,
			  let data = try? JSONEncoder().encode(body),
			  let result = try? JSONDecoder().decode(DAPSetBreakpointsResponseBody.self, from: data)
		else {
			throw DebugBreakpointSyncError.missingResponseBody
		}
		return requested.enumerated().map { index, requestedBreakpoint in
			let adapterBreakpoint = result.breakpoints.indices.contains(index)
				? result.breakpoints[index]
				: DAPBreakpoint(verified: false, message: "Adapter returned no breakpoint status.", line: requestedBreakpoint.line, column: requestedBreakpoint.column)
			return DebugBreakpointVerification(sourceURL: sourceURL, requested: requestedBreakpoint, adapterBreakpoint: adapterBreakpoint)
		}
	}
}

private func breakpointOrder(_ lhs: SourceBreakpoint, _ rhs: SourceBreakpoint) -> Bool {
	if lhs.line != rhs.line {
		return lhs.line < rhs.line
	}
	return (lhs.column ?? 0) < (rhs.column ?? 0)
}
