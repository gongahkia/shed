// @file find-bar state and AppKit controls.
import AppKit
import Foundation

struct FindBarState: Equatable {
	var query: String
	var replacement: String
	var usesRegex: Bool
	var isCaseSensitive: Bool
	var matchesWholeWord: Bool
}
