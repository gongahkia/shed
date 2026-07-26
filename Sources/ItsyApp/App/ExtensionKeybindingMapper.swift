import Foundation
import ItsyEditor
import ItsyKeymap

enum ExtensionKeybindingMapper {
	static func discover(root: URL, mode: Mode, validCommandIDs: Set<String>, fileManager: FileManager = .default) -> [KeyBinding] {
		ExtensionManifestLoader.discover(root: root, fileManager: fileManager).flatMap { manifest in
			bindings(from: manifest, mode: mode, validCommandIDs: validCommandIDs)
		}
	}

	static func bindings(from manifest: ExtensionManifest, mode: Mode, validCommandIDs: Set<String>) -> [KeyBinding] {
		let contributionIDs = Set(manifest.contributes.commands.map(\.id))
		return manifest.contributes.keybindings.compactMap { contribution in
			guard let commandID = resolveCommandID(contribution.command, manifest: manifest, contributionIDs: contributionIDs),
			      validCommandIDs.contains(commandID)
			else {
				NSLog("skipping extension keybinding with unknown command: \(manifest.identifier).\(contribution.command)")
				return nil
			}
			do {
				return try KeymapLoader.binding(mode: mode, key: contribution.key, commandID: commandID)
			} catch {
				NSLog("skipping extension keybinding \(manifest.identifier).\(contribution.command): \(error)")
				return nil
			}
		}
	}

	private static func resolveCommandID(_ rawCommandID: String, manifest: ExtensionManifest, contributionIDs: Set<String>) -> String? {
		let commandID = rawCommandID.trimmingCharacters(in: .whitespacesAndNewlines)
		if contributionIDs.contains(commandID) {
			return "extension:\(manifest.identifier):\(commandID)"
		}
		let scopedPrefix = "extension:\(manifest.identifier):"
		if commandID.hasPrefix(scopedPrefix) {
			let localID = String(commandID.dropFirst(scopedPrefix.count))
			return contributionIDs.contains(localID) ? commandID : nil
		}
		let dottedPrefix = "\(manifest.identifier)."
		if commandID.hasPrefix(dottedPrefix) {
			let localID = String(commandID.dropFirst(dottedPrefix.count))
			return contributionIDs.contains(localID) ? "extension:\(manifest.identifier):\(localID)" : nil
		}
		return nil
	}
}
