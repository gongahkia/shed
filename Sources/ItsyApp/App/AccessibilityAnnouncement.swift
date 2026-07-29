import AppKit

enum AccessibilityAnnouncement: Equatable {
	case languageServerFailure(language: String)
	case languageServerRecovery(language: String)
	case recoveredEdits(fileName: String)

	var message: String {
		switch self {
		case let .languageServerFailure(language): L10n.string("Language server for \(language) failed")
		case let .languageServerRecovery(language): L10n.string("Language server for \(language) recovered")
		case let .recoveredEdits(fileName): L10n.string("Recovered unsaved edits for \(fileName)")
		}
	}

	var priority: NSAccessibilityPriorityLevel {
		switch self {
		case .languageServerFailure: .high
		case .languageServerRecovery, .recoveredEdits: .medium
		}
	}

	static func post(_ announcement: Self) {
		NSAccessibility.post(
			element: NSApplication.shared,
			notification: .announcementRequested,
			userInfo: [.announcement: announcement.message, .priority: announcement.priority.rawValue]
		)
	}
}
