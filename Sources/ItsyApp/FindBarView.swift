import AppKit
import Foundation

struct FindBarState: Equatable {
	var query: String
	var replacement: String
	var usesRegex: Bool
	var isCaseSensitive: Bool
	var matchesWholeWord: Bool
}

final class ItsyActionTextField: NSTextField {
	var onCancel: (() -> Void)?
	var onConfirm: (() -> Void)?
	var onFindPrevious: (() -> Void)?
	var onMoveSelection: ((Int) -> Void)?
	var handlesFindShortcuts = false

	override func keyDown(with event: NSEvent) {
		if handlesFindShortcuts, event.modifierFlags.contains(.control), event.charactersIgnoringModifiers == "s", onConfirm != nil {
			onConfirm?()
			return
		}
		if handlesFindShortcuts, event.modifierFlags.contains(.control), event.charactersIgnoringModifiers == "r", onFindPrevious != nil {
			onFindPrevious?()
			return
		}
		switch event.keyCode {
		case 36 where onConfirm != nil:
			onConfirm?()
			return
		case 53 where onCancel != nil:
			onCancel?()
			return
		case 125 where onMoveSelection != nil:
			onMoveSelection?(1)
			return
		case 126 where onMoveSelection != nil:
			onMoveSelection?(-1)
			return
		default:
			break
		}
		super.keyDown(with: event)
	}
}
