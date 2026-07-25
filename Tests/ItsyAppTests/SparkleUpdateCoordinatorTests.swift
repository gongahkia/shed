import AppKit
import ItsyConfig
@testable import ItsyApp
import Testing

@MainActor @Test func sparkleUpdaterUsesTomlOptInAndKeepsDownloadsManual() {
	let driver = FakeSparkleUpdaterDriver()
	let coordinator = SparkleUpdateCoordinator(driver: driver)

	coordinator.start(automaticallyChecks: true)
	#expect(driver.startCount == 1)
	#expect(driver.automaticallyChecksForUpdates)
	#expect(!driver.automaticallyDownloadsUpdates)

	coordinator.apply(.init(automaticallyCheck: false))
	#expect(!driver.automaticallyChecksForUpdates)
	#expect(!driver.automaticallyDownloadsUpdates)

	coordinator.checkForUpdates(nil)
	#expect(driver.checkCount == 1)
	#expect(coordinator.validateMenuItem(NSMenuItem()) == driver.canCheckForUpdates)
}

@MainActor private final class FakeSparkleUpdaterDriver: SparkleUpdaterDriver {
	var canCheckForUpdates = true
	var automaticallyChecksForUpdates = false
	var automaticallyDownloadsUpdates = true
	var startCount = 0
	var checkCount = 0

	func start() {
		startCount += 1
	}

	func checkForUpdates() {
		checkCount += 1
	}
}
