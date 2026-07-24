import Foundation
import ItsyConfig

private func fail(_ message: String) -> Never {
	FileHandle.standardError.write(Data(("itsy config: \(message)\n").utf8))
	exit(2)
}

private func usage() -> Never {
	fail("usage: itsy config [--workspace PATH] <path|list|get|set|reset> [key] [value] [--json]")
}

var arguments = Array(CommandLine.arguments.dropFirst())
guard arguments.first == "config" else { usage() }
arguments.removeFirst()
var workspaceRoot: URL?
if arguments.first == "--workspace" {
	guard arguments.count >= 2 else { usage() }
	workspaceRoot = URL(fileURLWithPath: arguments[1]).standardizedFileURL
	arguments.removeFirst(2)
}
guard let command = arguments.first else { usage() }
arguments.removeFirst()
let json = arguments.last == "--json"
if json { arguments.removeLast() }

let store: ItsySettingsStore
if let workspaceRoot {
	store = ItsySettingsStore(fileURL: ItsySettingsStore.workspaceFileURL(workspaceRoot: workspaceRoot))
} else {
	store = ItsySettingsStore()
}

func emit(_ key: String, _ value: String) {
	if json {
		let escapedKey = key.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
		let escapedValue = value.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
		print("{\"key\":\"\(escapedKey)\",\"value\":\"\(escapedValue)\"}")
	} else {
		print(value)
	}
}

switch command {
case "path":
	guard arguments.isEmpty else { usage() }
	print(store.fileURL.path)
case "list":
	guard arguments.isEmpty else { usage() }
	let settings = store.load().settings
	let keys = ItsySettingsCatalog.baseEntries.map(\.key) + ItsySettings.UISettings.knownSurfaceIDs.flatMap { id in
		["width", "height", "row_height", "input_font_size", "item_font_size"].map { "ui.surface.\(id).\($0)" }
	}
	if json {
		let records = keys.map { key in "{\"key\":\"\(key)\",\"value\":\"\(ItsySettingsCatalog.effectiveValue(for: key, in: settings))\"}" }
		print("[\(records.joined(separator: ","))]")
	} else {
		for key in keys { print("\(key) = \(ItsySettingsCatalog.effectiveValue(for: key, in: settings))") }
	}
case "get":
	guard arguments.count == 1 else { usage() }
	emit(arguments[0], ItsySettingsCatalog.effectiveValue(for: arguments[0], in: store.load().settings))
case "set":
	guard arguments.count == 2 else { usage() }
	let key = arguments[0]
	guard workspaceRoot == nil || !key.hasPrefix("ui.") else { fail("\(key) is user-only") }
	var settings = store.load().settings
	if let error = ItsySettingsCatalog.update(value: arguments[1], for: key, in: &settings) { fail(error) }
	do { try store.save(settings) } catch { fail(String(describing: error)) }
case "reset":
	guard arguments.count == 1 else { usage() }
	let key = arguments[0]
	guard workspaceRoot == nil || !key.hasPrefix("ui.") else { fail("\(key) is user-only") }
	var settings = store.load().settings
	guard ItsySettingsCatalog.reset(key, in: &settings) else { fail("unknown or read-only setting \(key)") }
	do { try store.save(settings) } catch { fail(String(describing: error)) }
default:
	usage()
}
