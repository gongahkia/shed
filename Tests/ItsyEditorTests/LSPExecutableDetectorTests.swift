import Foundation
import ItsyEditor
import Testing

@Test func lspExecutableDetectorUsesPathPrecedenceBeforeApprovedLocations() throws {
	let fixture = try LSPExecutableDetectorFixture()
	let first = try fixture.executable("first/test-server")
	_ = try fixture.executable("second/test-server")
	let fallback = try fixture.executable("fallback/test-server")
	let probe = LSPExecutableProbe(executable: "test-server", approvedPlatformLocations: [fallback.deletingLastPathComponent().path])
	let resolution = try LSPExecutableDetector.detect(
		command: "test-server",
		probe: probe,
		environment: ["PATH": "\(first.deletingLastPathComponent().path):\(fixture.root.appendingPathComponent("second").path)"]
	)
	#expect(resolution.executableURL == first.standardizedFileURL)
	#expect(resolution.source == .path)
}

@Test func lspExecutableDetectorUsesApprovedLocationWithoutMutatingEnvironment() throws {
	let fixture = try LSPExecutableDetectorFixture()
	let fallback = try fixture.executable("fallback/test-server")
	let probe = LSPExecutableProbe(executable: "test-server", approvedPlatformLocations: [fallback.deletingLastPathComponent().path])
	let environment = ["PATH": fixture.root.appendingPathComponent("empty").path]
	let resolution = try LSPExecutableDetector.detect(command: "test-server", probe: probe, environment: environment)
	#expect(resolution.executableURL == fallback.standardizedFileURL)
	#expect(resolution.source == .approvedPlatformLocation)
	#expect(environment == ["PATH": fixture.root.appendingPathComponent("empty").path])
}

@Test func lspExecutableDetectorReportsMissingAndBadVersions() throws {
	let fixture = try LSPExecutableDetectorFixture()
	let probe = LSPExecutableProbe(executable: "test-server", approvedPlatformLocations: [])
	#expect(throws: LSPExecutableDetectionError.missingExecutable("test-server")) {
		try LSPExecutableDetector.detect(command: "test-server", probe: probe, environment: ["PATH": fixture.root.path])
	}

	let executable = try fixture.executable("bin/test-server")
	let versionedProbe = LSPExecutableProbe(executable: "test-server", approvedPlatformLocations: [], minimumVersion: LSPServerVersion(major: 2))
	#expect(throws: LSPExecutableDetectionError.unsupportedVersion("test-server", found: LSPServerVersion(major: 1, minor: 9), minimum: LSPServerVersion(major: 2))) {
		try LSPExecutableDetector.detect(
			command: "test-server",
			probe: versionedProbe,
			environment: ["PATH": executable.deletingLastPathComponent().path],
			versionReader: { _, _ in "test-server 1.9.0" }
		)
	}
}

@Test func lspExecutableDetectorRespectsExplicitAndEnvironmentOverrides() throws {
	let fixture = try LSPExecutableDetectorFixture()
	let explicit = try fixture.executable("explicit/test-server")
	let override = try fixture.executable("override/test-server")
	let path = try fixture.executable("path/test-server")
	let probe = LSPExecutableProbe(executable: "test-server", approvedPlatformLocations: [])

	let explicitResolution = try LSPExecutableDetector.detect(
		command: explicit.path,
		probe: probe,
		environment: [probe.overrideEnvironmentVariable: override.path, "PATH": path.deletingLastPathComponent().path]
	)
	#expect(explicitResolution.executableURL == explicit.standardizedFileURL)
	#expect(explicitResolution.source == .explicitCommand)

	let overrideResolution = try LSPExecutableDetector.detect(
		command: "test-server",
		probe: probe,
		environment: [probe.overrideEnvironmentVariable: override.path, "PATH": path.deletingLastPathComponent().path]
	)
	#expect(overrideResolution.executableURL == override.standardizedFileURL)
	#expect(overrideResolution.source == .environmentOverride)
}

private final class LSPExecutableDetectorFixture {
	let root: URL

	init() throws {
		root = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-lsp-detector-\(UUID().uuidString)", isDirectory: true)
		try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
	}

	deinit {
		try? FileManager.default.removeItem(at: root)
	}

	func executable(_ relativePath: String) throws -> URL {
		let url = root.appendingPathComponent(relativePath)
		try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
		try "#!/bin/sh\nexit 0\n".write(to: url, atomically: true, encoding: .utf8)
		try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: url.path)
		return url
	}
}
