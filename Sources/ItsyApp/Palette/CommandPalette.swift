// @file command palette bridge actions.
import AppKit

@MainActor enum ItsyCommandPaletteBridge {
	static var showExCommand: ((NSWindow?, @escaping (String?) -> Void) -> Bool)?

	static func requestExCommand(relativeTo window: NSWindow?, completion: @escaping (String?) -> Void) -> Bool {
		showExCommand?(window, completion) ?? false
	}
}
