import AppKit
import Sparkle

@MainActor final class SparkleUpdateCoordinator: NSObject, NSMenuItemValidation {
	private let updaterController: SPUStandardUpdaterController?

	var isConfigured: Bool {
		updaterController != nil
	}

	override init() {
		if Self.hasRequiredConfiguration {
			updaterController = SPUStandardUpdaterController(startingUpdater: false, updaterDelegate: nil, userDriverDelegate: nil)
		} else {
			updaterController = nil
		}
		super.init()
	}

	func start() {
		updaterController?.startUpdater()
	}

	@objc func checkForUpdates(_ sender: Any?) {
		updaterController?.checkForUpdates(sender)
	}

	func validateMenuItem(_: NSMenuItem) -> Bool {
		updaterController?.updater.canCheckForUpdates ?? false
	}

	private static var hasRequiredConfiguration: Bool {
		hasValue("SUFeedURL") && hasValue("SUPublicEDKey")
	}

	private static func hasValue(_ key: String) -> Bool {
		guard let value = Bundle.main.object(forInfoDictionaryKey: key) as? String else {
			return false
		}
		return !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
	}
}
