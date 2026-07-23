import Foundation

public enum ManagedSupportResolver {
	public static func executableURL(
		for component: ManagedSupportComponent,
		installRoot: URL = ManagedSupportInstaller.defaultInstallRoot(),
		fileManager: FileManager = .default
	) -> URL? {
		guard component.installMode == .managed else {
			return nil
		}
		let componentRoot = installRoot.appendingPathComponent(component.id, isDirectory: true)
		guard let versions = try? fileManager.contentsOfDirectory(at: componentRoot, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]) else {
			return nil
		}
		let candidates = versions.compactMap { versionURL -> (URL, ManagedSupportInstallReceipt)? in
			guard let receipt = try? ManagedSupportInstaller.loadReceipt(installedURL: versionURL), receipt.componentID == component.id else {
				return nil
			}
			return (versionURL, receipt)
		}.sorted { lhs, rhs in
			lhs.1.installedAt > rhs.1.installedAt
		}
		for (versionURL, receipt) in candidates {
			let paths = receipt.executablePaths.sorted { lhs, rhs in
				let preferred = URL(fileURLWithPath: lhs).lastPathComponent == component.command
				let otherPreferred = URL(fileURLWithPath: rhs).lastPathComponent == component.command
				if preferred != otherPreferred {
					return preferred
				}
				return lhs < rhs
			}
			for path in paths {
				let executable = versionURL.appendingPathComponent(path).standardizedFileURL
				if fileManager.isExecutableFile(atPath: executable.path) {
					return executable
				}
			}
		}
		return nil
	}
}
