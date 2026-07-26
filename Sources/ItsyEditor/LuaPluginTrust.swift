import CryptoKit
import Foundation

public enum LuaPluginTrustError: Error, Equatable, Sendable {
	case invalidManifest(LuaPluginManifestError)
	case packageNotDirectory(URL)
	case unreadablePackage(URL)
	case symbolicLinkRejected(String)
	case trustDenied(VouchRecord)
	case trustMissing(VouchSubject)
}

public enum LuaPluginTrust {
	public static func subject(
		packageRoot: URL,
		scope: LuaPluginScope,
		fileManager: FileManager = .default
	) throws -> VouchSubject {
		let manifest: LuaPluginManifest
		do {
			manifest = try LuaPluginManifestLoader.load(packageRoot: packageRoot, fileManager: fileManager)
		} catch let error as LuaPluginManifestError {
			throw LuaPluginTrustError.invalidManifest(error)
		}
		return VouchSubject(
			sha256: try sha256(packageRoot: packageRoot, fileManager: fileManager),
			identifier: manifest.identifier,
			version: versionText(manifest.version),
			packageKind: .luaPlugin,
			packageScope: trustScope(scope)
		)
	}

	public static func decision(
		packageRoot: URL,
		scope: LuaPluginScope,
		repoRoot: URL,
		workspaceRoot: URL,
		homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser,
		fileManager: FileManager = .default,
		vouchEvidence: ((VouchSubject) -> VouchDecision?)? = nil
	) throws -> VouchDecision {
		let subject = try subject(packageRoot: packageRoot, scope: scope, fileManager: fileManager)
		return try decision(
			for: subject,
			repoRoot: repoRoot,
			workspaceRoot: workspaceRoot,
			homeDirectory: homeDirectory,
			fileManager: fileManager,
			vouchEvidence: vouchEvidence
		)
	}

	public static func requireTrust(
		packageRoot: URL,
		scope: LuaPluginScope,
		repoRoot: URL,
		workspaceRoot: URL,
		homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser,
		fileManager: FileManager = .default,
		vouchEvidence: ((VouchSubject) -> VouchDecision?)? = nil
	) throws -> VouchDecision {
		let subject = try subject(packageRoot: packageRoot, scope: scope, fileManager: fileManager)
		let decision = try decision(
			for: subject,
			repoRoot: repoRoot,
			workspaceRoot: workspaceRoot,
			homeDirectory: homeDirectory,
			fileManager: fileManager,
			vouchEvidence: vouchEvidence
		)
		switch decision {
		case .allow:
			return decision
		case let .deny(record):
			throw LuaPluginTrustError.trustDenied(record)
		case .missing:
			throw LuaPluginTrustError.trustMissing(subject)
		}
	}

	private static func decision(
		for subject: VouchSubject,
		repoRoot: URL,
		workspaceRoot: URL,
		homeDirectory: URL,
		fileManager: FileManager,
		vouchEvidence: ((VouchSubject) -> VouchDecision?)?
	) throws -> VouchDecision {
		var records = try VouchStore.load(
			urls: VouchStore.defaultURLs(repoRoot: repoRoot, workspaceRoot: workspaceRoot, homeDirectory: homeDirectory),
			fileManager: fileManager
		).records
		if let evidence = vouchEvidence?(subject) {
			switch evidence {
			case let .allow(record), let .deny(record): records.append(record)
			case .missing: break
			}
		}
		return VouchStore(records: records).decision(for: subject)
	}

	public static func sha256(packageRoot: URL, fileManager: FileManager = .default) throws -> String {
		let root = packageRoot.standardizedFileURL
		var isDirectory: ObjCBool = false
		guard fileManager.fileExists(atPath: root.path, isDirectory: &isDirectory), isDirectory.boolValue else {
			throw LuaPluginTrustError.packageNotDirectory(root)
		}
		let keys: Set<URLResourceKey> = [.isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey]
		guard let enumerator = fileManager.enumerator(at: root, includingPropertiesForKeys: Array(keys)) else {
			throw LuaPluginTrustError.unreadablePackage(root)
		}
		let entries = enumerator.compactMap { $0 as? URL }
			.filter { relativePath($0, root: root) != ".itsy-plugin-lock.json" }
			.sorted { $0.path < $1.path }
		var hasher = SHA256()
		for entry in entries {
			let values = try entry.resourceValues(forKeys: keys)
			let relative = relativePath(entry, root: root)
			if values.isSymbolicLink == true {
				throw LuaPluginTrustError.symbolicLinkRejected(relative)
			}
			hasher.update(data: Data(relative.utf8))
			hasher.update(data: Data([0]))
			if values.isDirectory == true {
				hasher.update(data: Data([1]))
			} else if values.isRegularFile == true {
				hasher.update(data: Data([2]))
				hasher.update(data: try Data(contentsOf: entry))
			} else {
				hasher.update(data: Data([3]))
			}
			hasher.update(data: Data([0]))
		}
		return hasher.finalize().map { String(format: "%02x", $0) }.joined()
	}

	private static func trustScope(_ scope: LuaPluginScope) -> VouchPackageScope {
		switch scope {
		case .global: .global
		case .workspace: .workspace
		}
	}

	private static func relativePath(_ url: URL, root: URL) -> String {
		let rootPath = root.standardizedFileURL.path
		let path = url.standardizedFileURL.path
		let prefix = rootPath.hasSuffix("/") ? rootPath : rootPath + "/"
		return path.hasPrefix(prefix) ? String(path.dropFirst(prefix.count)) : path
	}

	private static func versionText(_ version: LuaPluginVersion) -> String {
		"\(version.major).\(version.minor).\(version.patch)"
	}
}
