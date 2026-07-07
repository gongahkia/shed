import Foundation
import ItsyDAP

public enum DebugBreakpointSync {
	public static func syncPersistedBreakpoints(
		from store: BreakpointStore,
		using client: DAPClientSession,
		workspaceRoot: URL
	) async throws {
		try await store.load()
		let snapshot = await store.snapshot()
		for arguments in setBreakpointsArguments(from: snapshot, workspaceRoot: workspaceRoot) {
			try await client.setBreakpoints(arguments)
		}
	}

	public static func setBreakpointsArguments(
		from snapshot: [URL: [SourceBreakpoint]],
		workspaceRoot: URL
	) -> [DAPSetBreakpointsArguments] {
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
				DAPSetBreakpointsArguments(
					source: DAPSource(name: url.lastPathComponent, path: url.path),
					breakpoints: breakpoints
				)
			}
	}
}

private func breakpointOrder(_ lhs: SourceBreakpoint, _ rhs: SourceBreakpoint) -> Bool {
	if lhs.line != rhs.line {
		return lhs.line < rhs.line
	}
	return (lhs.column ?? 0) < (rhs.column ?? 0)
}
