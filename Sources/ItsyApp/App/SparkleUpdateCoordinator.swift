import AppKit
import ItsyConfig
import Sparkle

@MainActor protocol SparkleUpdaterDriver: AnyObject {
	var canCheckForUpdates: Bool { get }
	var automaticallyChecksForUpdates: Bool { get set }
	var automaticallyDownloadsUpdates: Bool { get set }
	func start()
	func checkForUpdates()
}

@MainActor final class StandardSparkleUpdaterDriver: SparkleUpdaterDriver {
	private let controller: SPUStandardUpdaterController

	init() {
		controller = SPUStandardUpdaterController(startingUpdater: false, updaterDelegate: nil, userDriverDelegate: nil)
	}

	var canCheckForUpdates: Bool { controller.updater.canCheckForUpdates }
	var automaticallyChecksForUpdates: Bool {
		get { controller.updater.automaticallyChecksForUpdates }
		set { controller.updater.automaticallyChecksForUpdates = newValue }
	}
	var automaticallyDownloadsUpdates: Bool {
		get { controller.updater.automaticallyDownloadsUpdates }
		set { controller.updater.automaticallyDownloadsUpdates = newValue }
	}

	func start() {
		controller.startUpdater()
	}

	func checkForUpdates() {
		controller.checkForUpdates(nil)
	}
}

@MainActor final class SparkleUpdateCoordinator: NSObject, NSMenuItemValidation {
	private let driver: SparkleUpdaterDriver?
	private var started = false

	var isConfigured: Bool { driver != nil }

	init(driver: SparkleUpdaterDriver? = nil) {
		self.driver = driver ?? (Self.hasRequiredConfiguration ? StandardSparkleUpdaterDriver() : nil)
		super.init()
	}

	func start(automaticallyChecks: Bool) {
		guard let driver else { return }
		if !started {
			driver.start()
			started = true
		}
		apply(automaticallyChecks: automaticallyChecks)
	}

	func apply(_ settings: ItsySettings.UpdateSettings) {
		apply(automaticallyChecks: settings.automaticallyCheck)
	}

	@objc func checkForUpdates(_ sender: Any?) {
		driver?.checkForUpdates()
	}

	func validateMenuItem(_: NSMenuItem) -> Bool {
		driver?.canCheckForUpdates ?? false
	}

	private func apply(automaticallyChecks: Bool) {
		guard let driver else { return }
		driver.automaticallyChecksForUpdates = automaticallyChecks
		driver.automaticallyDownloadsUpdates = false
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
