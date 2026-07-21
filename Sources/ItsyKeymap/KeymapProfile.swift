import Foundation

public enum KeymapProfile: String, CaseIterable, Sendable {
	case plain
	case vim
	case emacs

	public static func selected(from arguments: [String], default defaultProfile: KeymapProfile = .plain) throws -> KeymapProfile {
		for argument in arguments {
			guard argument.hasPrefix("--profile=") else {
				continue
			}
			let name = String(argument.dropFirst("--profile=".count))
			guard let profile = KeymapProfile(rawValue: name) else {
				throw KeymapConfigurationError.invalidProfile(name)
			}
			return profile
		}
		return defaultProfile
	}
}

public enum KeymapConfigurationError: Error, Equatable {
	case invalidProfile(String)
	case missingBundledProfile(KeymapProfile)
	case duplicateBundledBinding(KeymapProfile)
}

public enum KeymapConfiguration {
	public static func load(profile: KeymapProfile, userConfigURL: URL? = defaultUserConfigURL()) throws -> [KeyBinding] {
		let bundledBindings = try bundledBindings(profile: profile)
		let bundledMatrix = KeymapBindingMatrix(profile: profile, bindings: bundledBindings)
		guard bundledMatrix.collisions.isEmpty else {
			throw KeymapConfigurationError.duplicateBundledBinding(profile)
		}
		guard let userConfigURL, FileManager.default.fileExists(atPath: userConfigURL.path) else {
			return bundledBindings
		}
		let userBindings = try KeymapLoader.load(String(contentsOf: userConfigURL, encoding: .utf8))
		return resolvedBindings(bundledBindings + userBindings)
	}

	public static func bundledBindings(profile: KeymapProfile) throws -> [KeyBinding] {
		try KeymapLoader.load(bundledProfileContents(profile))
	}

	public static func resolvedBindings(_ bindings: [KeyBinding]) -> [KeyBinding] {
		var resolved: [KeyBinding] = []
		for binding in bindings {
			resolved.removeAll { existing in
				existing.mode == binding.mode && (isPrefix(existing.chord, of: binding.chord) || isPrefix(binding.chord, of: existing.chord))
			}
			resolved.append(binding)
		}
		return resolved
	}

	public static func defaultUserConfigURL(homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser) -> URL {
		homeDirectory
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("keys.toml")
	}

	private static func bundledProfileContents(_ profile: KeymapProfile) throws -> String {
		guard let url = Bundle.module.url(forResource: "keys.\(profile.rawValue)", withExtension: "toml") else {
			throw KeymapConfigurationError.missingBundledProfile(profile)
		}
		return try String(contentsOf: url, encoding: .utf8)
	}

	private static func isPrefix(_ candidate: [Key], of chord: [Key]) -> Bool {
		candidate.count <= chord.count && candidate.elementsEqual(chord.prefix(candidate.count))
	}
}
