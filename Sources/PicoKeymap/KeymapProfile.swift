import Foundation

public enum KeymapProfile: String, CaseIterable, Sendable {
	case plain
	case vim
	case emacs

	public static func selected(from arguments: [String]) throws -> KeymapProfile {
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
		return .plain
	}
}

public enum KeymapConfigurationError: Error, Equatable {
	case invalidProfile(String)
	case missingBundledProfile(KeymapProfile)
}

public enum KeymapConfiguration {
	public static func load(profile: KeymapProfile, userConfigURL: URL? = defaultUserConfigURL()) throws -> [KeyBinding] {
		var contents = try bundledProfileContents(profile)
		if let userConfigURL, FileManager.default.fileExists(atPath: userConfigURL.path) {
			contents += "\n"
			contents += try String(contentsOf: userConfigURL, encoding: .utf8)
		}
		return try KeymapLoader.load(contents)
	}

	public static func defaultUserConfigURL(homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser) -> URL {
		homeDirectory
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("pico", isDirectory: true)
			.appendingPathComponent("keys.toml")
	}

	private static func bundledProfileContents(_ profile: KeymapProfile) throws -> String {
		guard let url = Bundle.module.url(forResource: "keys.\(profile.rawValue)", withExtension: "toml") else {
			throw KeymapConfigurationError.missingBundledProfile(profile)
		}
		return try String(contentsOf: url, encoding: .utf8)
	}
}
