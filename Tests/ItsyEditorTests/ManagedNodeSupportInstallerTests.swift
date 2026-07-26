import CryptoKit
import Foundation
@testable import ItsyEditor
import Testing

@Test func bundledTypeScriptSupportPinsTheServerAndCompilerPackages() throws {
	let component = try #require(ManagedSupportCatalog.bundled.component(id: "typescript-language-server"))
	let support = try #require(component.nodeSupport)

	#expect(support.version == "5.3.0")
	#expect(support.executablePath == "node_modules/typescript-language-server/lib/cli.mjs")
	#expect(support.packages.map(\.packageName) == ["typescript", "typescript-language-server"])
	#expect(support.packages.allSatisfy { $0.integrity.hasPrefix("sha512-") })
}

@Test func managedNodeSupportInstallsPinnedPackagesAndResolvesTheServerEntrypoint() throws {
	let fixture = try ManagedNodeSupportFixture()
	defer { fixture.cleanup() }
	let typescript = try fixture.packageArchive(
		name: "typescript",
		files: ["package.json": "{}\n", "lib/typescript.js": "export {};\n"]
	)
	let server = try fixture.packageArchive(
		name: "typescript-language-server",
		files: ["package.json": "{}\n", "lib/cli.mjs": "#!/usr/bin/env node\n"]
	)
	let component = try ManagedSupportComponent(
		id: "typescript-language-server",
		displayName: "TypeScript Language Server",
		kind: .languageServer,
		tier: .core,
		languageIDs: ["typescript"],
		command: "typescript-language-server",
		installMode: .managed,
		officialURL: #require(URL(string: "https://example.invalid")),
		nodeSupport: ManagedNodeSupport(
			version: "5.3.0",
			packages: [
				.init(
					packageName: "typescript",
					version: "5.9.3",
					archiveURL: #require(URL(string: "https://example.invalid/typescript.tgz")),
					integrity: fixture.integrity(of: typescript)
				),
				.init(
					packageName: "typescript-language-server",
					version: "5.3.0",
					archiveURL: #require(URL(string: "https://example.invalid/typescript-language-server.tgz")),
					integrity: fixture.integrity(of: server)
				),
			],
			executablePath: "node_modules/typescript-language-server/lib/cli.mjs"
		)
	)
	let receipt = try ManagedNodeSupportInstaller.install(
		component: component,
		archives: ["typescript": typescript, "typescript-language-server": server],
		installRoot: fixture.installRoot
	)
	let resolved = ManagedSupportResolver.executableURL(for: component, installRoot: fixture.installRoot)

	#expect(receipt.componentID == component.id)
	#expect(receipt.version == "5.3.0")
	#expect(resolved?.path.hasSuffix("node_modules/typescript-language-server/lib/cli.mjs") == true)
	#expect(try FileManager.default.isExecutableFile(atPath: #require(resolved).path))
}

@Test func managedNodeSupportRejectsArchivesWithUnexpectedPaths() throws {
	let fixture = try ManagedNodeSupportFixture()
	defer { fixture.cleanup() }
	let archive = try fixture.archive(entries: ["outside.txt": "unexpected\n"])
	let component = try ManagedSupportComponent(
		id: "typescript-language-server",
		displayName: "TypeScript Language Server",
		kind: .languageServer,
		tier: .core,
		languageIDs: ["typescript"],
		command: "typescript-language-server",
		installMode: .managed,
		officialURL: #require(URL(string: "https://example.invalid")),
		nodeSupport: ManagedNodeSupport(
			version: "5.3.0",
			packages: [.init(
				packageName: "typescript-language-server",
				version: "5.3.0",
				archiveURL: #require(URL(string: "https://example.invalid/server.tgz")),
				integrity: fixture.integrity(of: archive)
			)],
			executablePath: "node_modules/typescript-language-server/lib/cli.mjs"
		)
	)
	#expect(throws: ManagedNodeSupportInstallError.unsafeArchivePath("outside.txt")) {
		try ManagedNodeSupportInstaller.install(
			component: component,
			archives: ["typescript-language-server": archive],
			installRoot: fixture.installRoot
		)
	}
}

@Test func nodeRuntimeDetectorFindsAValidNodeOutsideTheInheritedPath() throws {
	let fixture = try ManagedNodeSupportFixture()
	defer { fixture.cleanup() }
	let node = try fixture.executable(
		".local/share/fnm/node-versions/v20.1.0/installation/bin/node",
		contents: "#!/bin/sh\necho v20.1.0\n"
	)
	let runtime = NodeRuntimeDetector.resolve(
		environment: ["PATH": fixture.root.appendingPathComponent("empty").path],
		homeDirectory: fixture.root,
		versionReader: { url in url == node ? "v20.1.0" : "v1.0.0" }
	)

	#expect(runtime?.executableURL == node)
	#expect(runtime?.version == LSPServerVersion(major: 20, minor: 1))
}

private final class ManagedNodeSupportFixture {
	let root: URL
	let installRoot: URL

	init() throws {
		root = FileManager.default.temporaryDirectory.appendingPathComponent(
			"itsy-managed-node-support-\(UUID().uuidString)",
			isDirectory: true
		)
		installRoot = root.appendingPathComponent("support", isDirectory: true)
		try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
	}

	func packageArchive(name: String, files: [String: String]) throws -> URL {
		let entries = Dictionary(uniqueKeysWithValues: files.map { ("package/\($0.key)", $0.value) })
		let archive = try archive(entries: entries)
		let destination = root.appendingPathComponent("\(name).tgz")
		try FileManager.default.moveItem(at: archive, to: destination)
		return destination
	}

	func archive(entries: [String: String]) throws -> URL {
		let stage = root.appendingPathComponent("stage-\(UUID().uuidString)", isDirectory: true)
		try FileManager.default.createDirectory(at: stage, withIntermediateDirectories: true)
		for (path, contents) in entries {
			let file = stage.appendingPathComponent(path)
			try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
			try contents.write(to: file, atomically: true, encoding: .utf8)
		}
		let archive = root.appendingPathComponent("archive-\(UUID().uuidString).tgz")
		let archivePath: String = if entries.keys.allSatisfy({ $0 == "package" || $0.hasPrefix("package/") }) {
			"package"
		} else if entries.count == 1, let path = entries.keys.first {
			path
		} else {
			"."
		}
		let process = Process()
		process.executableURL = URL(fileURLWithPath: "/usr/bin/tar")
		process.arguments = ["-czf", archive.path, "-C", stage.path, archivePath]
		process.standardOutput = Pipe()
		process.standardError = Pipe()
		try process.run()
		process.waitUntilExit()
		guard process.terminationStatus == 0 else { throw CocoaError(.fileWriteUnknown) }
		try FileManager.default.removeItem(at: stage)
		return archive
	}

	func executable(_ relativePath: String, contents: String) throws -> URL {
		let file = root.appendingPathComponent(relativePath)
		try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
		try contents.write(to: file, atomically: true, encoding: .utf8)
		try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: file.path)
		return file.standardizedFileURL
	}

	func integrity(of archive: URL) throws -> String {
		try "sha512-\(Data(SHA512.hash(data: Data(contentsOf: archive))).base64EncodedString())"
	}

	func cleanup() {
		try? FileManager.default.removeItem(at: root)
	}
}
