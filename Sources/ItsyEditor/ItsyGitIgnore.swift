import Foundation

public enum ItsyGitIgnoreUpdateResult: Equatable, Sendable {
	case notRepository
	case alreadyIgnored(URL)
	case appended(URL)
	case failed(URL)
}

public enum ItsyGitIgnore {
	public static func ensureItsyDirectoryIgnored(in workspaceURL: URL) -> ItsyGitIgnoreUpdateResult {
		let repositoryRoot: URL
		do {
			repositoryRoot = try GitRepository.discoverRoot(containing: workspaceURL).standardizedFileURL
		} catch {
			return .notRepository
		}
		let gitignoreURL = repositoryRoot.appendingPathComponent(".gitignore")
		let contents: String
		do {
			contents = FileManager.default.fileExists(atPath: gitignoreURL.path)
				? try String(contentsOf: gitignoreURL, encoding: .utf8)
				: ""
		} catch {
			return .failed(gitignoreURL)
		}
		guard !containsItsyRule(in: contents) else {
			return .alreadyIgnored(gitignoreURL)
		}
		let lineEnding = contents.contains("\r\n") ? "\r\n" : "\n"
		let separator = contents.isEmpty || contents.hasSuffix("\n") ? "" : lineEnding
		do {
			try AtomicFileWriter.write(data: Data((contents + separator + ".itsy/" + lineEnding).utf8), to: gitignoreURL)
			return .appended(gitignoreURL)
		} catch {
			return .failed(gitignoreURL)
		}
	}

	private static func containsItsyRule(in contents: String) -> Bool {
		contents.split(whereSeparator: \.isNewline).contains { rawLine in
			let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
			guard !line.hasPrefix("#"), !line.hasPrefix("!") else {
				return false
			}
			return [".itsy", ".itsy/", "/.itsy", "/.itsy/"].contains(line)
		}
	}
}
