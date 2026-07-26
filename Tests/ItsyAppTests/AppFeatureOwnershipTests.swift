@testable import ItsyApp
import Foundation
import Testing

@Test func appFeatureOwnershipCoversEveryFirstPartySourceRoot() throws {
	let sourceRoots = try FileManager.default.contentsOfDirectory(
		at: repositoryRootURL().appendingPathComponent("Sources/ItsyApp", isDirectory: true),
		includingPropertiesForKeys: [.isDirectoryKey],
		options: [.skipsHiddenFiles]
	)
	.compactMap { url -> String? in
		let values = try? url.resourceValues(forKeys: [.isDirectoryKey])
		return values?.isDirectory == true ? url.lastPathComponent : nil
	}
	#expect(Set(AppFeatureOwnership.boundaries.map(\.sourceRoot)) == Set(sourceRoots))
}

@Test func appFeatureOwnershipAssignsEachSourceRootOnce() {
	let sourceRoots = AppFeatureOwnership.boundaries.map(\.sourceRoot)
	#expect(Set(sourceRoots).count == sourceRoots.count)
	#expect(AppFeatureOwnership.boundary(for: "App")?.kind == .compositionRoot)
	#expect(AppFeatureOwnership.boundary(for: "Windows")?.kind == .compositionRoot)
	#expect(AppFeatureOwnership.boundary(for: "Git")?.owner == "local Git workflows")
}

private func repositoryRootURL() -> URL {
	URL(fileURLWithPath: #filePath)
		.deletingLastPathComponent()
		.deletingLastPathComponent()
		.deletingLastPathComponent()
}
