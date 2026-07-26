@testable import ItsyEditor
import Foundation
import Testing

@Test func managedSupportCatalogKeepsOnlyRequestedCoreLanguageAndDebugServices() {
	let catalog = ManagedSupportCatalog.bundled
	let coreLSP = Set(catalog.coreComponents.filter { $0.kind == .languageServer }.map(\.id))
	let coreDAP = Set(catalog.coreComponents.filter { $0.kind == .debugAdapter }.map(\.id))
	#expect(coreLSP == ["pyright", "typescript-language-server", "omnisharp", "clangd"])
	#expect(coreDAP == ["debugpy", "vscode-js-debug", "lldb-dap"])
	#expect(catalog.component(languageID: "go", kind: .languageServer)?.tier == .onDemand)
	#expect(catalog.component(languageID: "swift", kind: .languageServer)?.tier == .onDemand)
	#expect(catalog.component(command: "typescript-language-server", kind: .languageServer)?.id == "typescript-language-server")
	#expect(catalog.component(id: "omnisharp")?.artifacts.artifact(for: .arm64)?.sha256.count == 64)
	#expect(catalog.component(id: "omnisharp")?.artifacts.artifact(for: .x86_64)?.sha256.count == 64)
	#expect(catalog.component(id: "sqls")?.artifacts.artifact(for: .arm64)?.sha256.count == 64)
	#expect(catalog.component(id: "codelldb")?.artifacts.artifact(for: .x86_64)?.sha256.count == 64)
}

@Test func managedSupportCatalogCoversEveryDeclaredLanguageServer() {
	let catalog = ManagedSupportCatalog.bundled
	for language in BundledLanguageInventory.languages where language.server != nil {
		#expect(catalog.component(languageID: language.languageID, kind: .languageServer) != nil)
	}
}

@Test func onDemandLanguageServersStayDisabledUntilItsyEnablesThem() throws {
	let component = try #require(ManagedSupportCatalog.bundled.component(id: "gopls"))
	let key = "itsy.support.enabled.\(component.id)"
	let previous = UserDefaults.standard.object(forKey: key)
	defer {
		if let previous {
			UserDefaults.standard.set(previous, forKey: key)
		} else {
			UserDefaults.standard.removeObject(forKey: key)
		}
	}
	ManagedSupportEnablement.setEnabled(false, for: component)
	let registry = LSPServerRegistry()
	#expect(registry.resolvedConfig(forLanguageID: "go", environment: ["PATH": "/opt/homebrew/bin"]) == nil)
	ManagedSupportEnablement.setEnabled(true, for: component)
	#expect(ManagedSupportEnablement.isEnabled(component))
}

@Test func managedSupportInstallerRejectsHashMismatchBeforeExtraction() throws {
	let fixture = try ManagedSupportFixture()
	defer { fixture.cleanup() }
	let archive = try fixture.zip(named: "tool")
	let component = try #require(ManagedSupportCatalog.bundled.component(id: "pyright"))
	let actualSHA = try ManagedSupportInstaller.sha256(url: archive)
	let artifact = ManagedSupportArtifact(
		version: "1.0.0",
		archiveURL: URL(string: "https://example.invalid/tool.zip")!,
		sha256: String(repeating: "0", count: 64),
		format: .zip,
		executablePaths: ["source-tool/bin/tool"]
	)
	#expect(throws: ManagedSupportInstallError.sha256Mismatch(expected: artifact.sha256, actual: actualSHA)) {
		try ManagedSupportInstaller.install(ManagedSupportInstallRequest(component: component, artifact: artifact, installRoot: fixture.installRoot), archiveURL: archive)
	}
}

@Test func managedSupportInstallerWritesPrivateReceiptAfterValidatedInstall() throws {
	let fixture = try ManagedSupportFixture()
	defer { fixture.cleanup() }
	let archive = try fixture.zip(named: "tool")
	let component = try #require(ManagedSupportCatalog.bundled.component(id: "pyright"))
	let artifact = ManagedSupportArtifact(
		version: "1.0.0",
		archiveURL: URL(string: "https://example.invalid/tool.zip")!,
		sha256: try ManagedSupportInstaller.sha256(url: archive),
		format: .zip,
		executablePaths: ["source-tool/bin/tool"]
	)
	let request = ManagedSupportInstallRequest(component: component, artifact: artifact, installRoot: fixture.installRoot)
	let receipt = try ManagedSupportInstaller.install(request, archiveURL: archive)
	let installed = ManagedSupportInstaller.installedURL(for: request)
	#expect(receipt.componentID == "pyright")
	#expect(FileManager.default.isExecutableFile(atPath: installed.appendingPathComponent("source-tool/bin/tool").path))
	#expect(try ManagedSupportInstaller.loadReceipt(installedURL: installed) == receipt)
}

@Test func managedSupportInstallerMarksOnlyAllowlistedLaunchersExecutable() throws {
	let fixture = try ManagedSupportFixture()
	defer { fixture.cleanup() }
	let archive = try fixture.zip(named: "non-executable-tool", isExecutable: false)
	let component = try #require(ManagedSupportCatalog.bundled.component(id: "pyright"))
	let artifact = ManagedSupportArtifact(
		version: "1.0.1",
		archiveURL: URL(string: "https://example.invalid/tool.zip")!,
		sha256: try ManagedSupportInstaller.sha256(url: archive),
		format: .zip,
		executablePaths: ["source-non-executable-tool/bin/tool"]
	)
	let request = ManagedSupportInstallRequest(component: component, artifact: artifact, installRoot: fixture.installRoot)
	_ = try ManagedSupportInstaller.install(request, archiveURL: archive)
	let executable = ManagedSupportInstaller.installedURL(for: request).appendingPathComponent("source-non-executable-tool/bin/tool")
	#expect(FileManager.default.isExecutableFile(atPath: executable.path))
}

private final class ManagedSupportFixture {
	let root: URL
	let installRoot: URL

	init() throws {
		root = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-managed-support-\(UUID().uuidString)", isDirectory: true)
		installRoot = root.appendingPathComponent("support", isDirectory: true)
		try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
	}

	func zip(named name: String, isExecutable: Bool = true) throws -> URL {
		let source = root.appendingPathComponent("source-\(name)", isDirectory: true)
		let executable = source.appendingPathComponent("bin/tool")
		try FileManager.default.createDirectory(at: executable.deletingLastPathComponent(), withIntermediateDirectories: true)
		try Data("#!/bin/sh\nexit 0\n".utf8).write(to: executable)
		try FileManager.default.setAttributes([.posixPermissions: isExecutable ? 0o755 : 0o644], ofItemAtPath: executable.path)
		let archive = root.appendingPathComponent("\(name).zip")
		let process = Process()
		process.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
		process.arguments = ["-c", "-k", "--keepParent", source.path, archive.path]
		try process.run()
		process.waitUntilExit()
		guard process.terminationStatus == 0 else {
			throw CocoaError(.fileWriteUnknown)
		}
		return archive
	}

	func cleanup() {
		try? FileManager.default.removeItem(at: root)
	}
}
