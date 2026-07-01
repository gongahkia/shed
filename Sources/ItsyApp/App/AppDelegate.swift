import AppKit

final class AppDelegate: NSObject, NSApplicationDelegate {
	private let coordinator: AppCoordinator

	init(documentController: ItsyDocumentController) {
		coordinator = AppCoordinator(documentController: documentController)
		super.init()
	}

	func applicationDidFinishLaunching(_ notification: Notification) {
		coordinator.applicationDidFinishLaunching(notification)
	}

	func applicationWillTerminate(_ notification: Notification) {
		coordinator.applicationWillTerminate(notification)
	}

	func application(_ sender: NSApplication, openFile filename: String) -> Bool {
		coordinator.application(sender, openFile: filename)
	}

	func application(_ application: NSApplication, continue userActivity: NSUserActivity, restorationHandler: @escaping ([NSUserActivityRestoring]) -> Void) -> Bool {
		coordinator.application(application, continue: userActivity, restorationHandler: restorationHandler)
	}
}
