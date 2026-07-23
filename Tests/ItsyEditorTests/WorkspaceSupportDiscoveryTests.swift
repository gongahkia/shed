@testable import ItsyEditor
import Foundation
import Testing

@Test func workspaceSupportScannerFindsSourceLanguagesAndSkipsVendorTrees() throws {
	let root = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-support-scan-\(UUID().uuidString)", isDirectory: true)
	defer { try? FileManager.default.removeItem(at: root) }
	try FileManager.default.createDirectory(at: root.appendingPathComponent("node_modules/dependency", isDirectory: true), withIntermediateDirectories: true)
	try "print(1)".write(to: root.appendingPathComponent("main.py"), atomically: true, encoding: .utf8)
	try "const value = 1".write(to: root.appendingPathComponent("web.ts"), atomically: true, encoding: .utf8)
	try "fn main() {}".write(to: root.appendingPathComponent("node_modules/dependency/ignored.rs"), atomically: true, encoding: .utf8)
	let snapshot = WorkspaceSupportScanner.scan(root: root)
	#expect(snapshot.languageIDs == ["python", "typescript"])
	#expect(snapshot.componentIDs == ["pyright", "typescript-language-server"])
}

@Test func gitHubRepositoryLocatorAcceptsHTTPSAndSSHOrigins() {
	#expect(GitHubRepositoryLocator.repository(remoteURL: "https://github.com/gongahkia/itsy.git") == GitHubRepositoryReference(owner: "gongahkia", name: "itsy"))
	#expect(GitHubRepositoryLocator.repository(remoteURL: "git@github.com:gongahkia/itsy.git") == GitHubRepositoryReference(owner: "gongahkia", name: "itsy"))
	#expect(GitHubRepositoryLocator.repository(remoteURL: "https://example.com/gongahkia/itsy.git") == nil)
}
