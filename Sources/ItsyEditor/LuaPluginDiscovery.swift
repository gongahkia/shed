import Foundation

public enum LuaPluginScope: String, Equatable, Sendable {
	case global
	case workspace
}

public struct LuaDiscoveredPlugin: Equatable, Sendable {
	public let scope: LuaPluginScope
	public let manifest: LuaPluginManifest

	public init(scope: LuaPluginScope, manifest: LuaPluginManifest) {
		self.scope = scope
		self.manifest = manifest
	}
}

public struct LuaPluginDiscoveryFailure: Equatable, Sendable {
	public let scope: LuaPluginScope
	public let packageRoot: URL
	public let error: LuaPluginDiscoveryError

	public init(scope: LuaPluginScope, packageRoot: URL, error: LuaPluginDiscoveryError) {
		self.scope = scope
		self.packageRoot = packageRoot.standardizedFileURL
		self.error = error
	}
}

public struct LuaPluginDiscoveryResult: Equatable, Sendable {
	public let plugins: [LuaDiscoveredPlugin]
	public let failures: [LuaPluginDiscoveryFailure]

	public init(plugins: [LuaDiscoveredPlugin], failures: [LuaPluginDiscoveryFailure]) {
		self.plugins = plugins
		self.failures = failures
	}
}

public enum LuaPluginDiscoveryError: Error, Equatable, Sendable {
	case unreadableRoot
	case packageEscapesRoot
	case invalidManifest(LuaPluginManifestError)
}

public enum LuaPluginDiscovery {
	public static func globalRoot(homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser) -> URL {
		homeDirectory
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("plugins", isDirectory: true)
	}

	public static func workspaceRoot(workspaceRoot: URL) -> URL {
		workspaceRoot.appendingPathComponent(".itsy", isDirectory: true).appendingPathComponent("plugins", isDirectory: true)
	}

	public static func discover(
		workspaceRoot workspaceURL: URL?,
		homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser,
		fileManager: FileManager = .default
	) -> LuaPluginDiscoveryResult {
		var candidates: [LuaDiscoveredPlugin] = []
		var failures: [LuaPluginDiscoveryFailure] = []
		let globalRoot = globalRoot(homeDirectory: homeDirectory)
		collect(scope: .global, root: globalRoot, fileManager: fileManager, candidates: &candidates, failures: &failures)
		if let workspaceURL {
			collect(scope: .workspace, root: workspaceRoot(workspaceRoot: workspaceURL), fileManager: fileManager, candidates: &candidates, failures: &failures)
		}
		return LuaPluginDiscoveryResult(
			plugins: resolve(candidates),
			failures: failures.sorted {
				if $0.scope != $1.scope { return $0.scope.rawValue < $1.scope.rawValue }
				return $0.packageRoot.path.localizedStandardCompare($1.packageRoot.path) == .orderedAscending
			}
		)
	}

	private static func collect(
		scope: LuaPluginScope,
		root: URL,
		fileManager: FileManager,
		candidates: inout [LuaDiscoveredPlugin],
		failures: inout [LuaPluginDiscoveryFailure]
	) {
		guard fileManager.fileExists(atPath: root.path) else {
			return
		}
		guard let enumerator = fileManager.enumerator(
			at: root,
			includingPropertiesForKeys: [.isRegularFileKey],
			options: [.skipsHiddenFiles]
		) else {
			failures.append(.init(scope: scope, packageRoot: root, error: .unreadableRoot))
			return
		}
		for case let manifestURL as URL in enumerator where manifestURL.lastPathComponent == LuaPluginManifestLoader.manifestFilename {
			let packageRoot = manifestURL.deletingLastPathComponent()
			guard isContained(packageRoot, in: root) else {
				failures.append(.init(scope: scope, packageRoot: packageRoot, error: .packageEscapesRoot))
				continue
			}
			do {
				candidates.append(.init(scope: scope, manifest: try LuaPluginManifestLoader.load(packageRoot: packageRoot, fileManager: fileManager)))
			} catch let error as LuaPluginManifestError {
				failures.append(.init(scope: scope, packageRoot: packageRoot, error: .invalidManifest(error)))
			} catch {
				failures.append(.init(scope: scope, packageRoot: packageRoot, error: .invalidManifest(.invalidSyntax)))
			}
		}
	}

	private static func isContained(_ packageRoot: URL, in root: URL) -> Bool {
		let rootPath = root.resolvingSymlinksInPath().standardizedFileURL.path
		let packagePath = packageRoot.resolvingSymlinksInPath().standardizedFileURL.path
		let prefix = rootPath.hasSuffix("/") ? rootPath : rootPath + "/"
		return packagePath == rootPath || packagePath.hasPrefix(prefix)
	}

	private static func resolve(_ candidates: [LuaDiscoveredPlugin]) -> [LuaDiscoveredPlugin] {
		var pluginsByIdentifier: [String: LuaDiscoveredPlugin] = [:]
		for candidate in candidates {
			guard let current = pluginsByIdentifier[candidate.manifest.identifier] else {
				pluginsByIdentifier[candidate.manifest.identifier] = candidate
				continue
			}
			if shouldReplace(current, with: candidate) {
				pluginsByIdentifier[candidate.manifest.identifier] = candidate
			}
		}
		return pluginsByIdentifier.values.sorted {
			$0.manifest.identifier.localizedStandardCompare($1.manifest.identifier) == .orderedAscending
		}
	}

	private static func shouldReplace(_ current: LuaDiscoveredPlugin, with candidate: LuaDiscoveredPlugin) -> Bool {
		if current.scope != candidate.scope {
			return candidate.scope == .workspace
		}
		if current.manifest.version != candidate.manifest.version {
			return candidate.manifest.version > current.manifest.version
		}
		return candidate.manifest.packageRoot.path.localizedStandardCompare(current.manifest.packageRoot.path) == .orderedAscending
	}
}
