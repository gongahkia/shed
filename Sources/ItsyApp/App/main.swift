// @file AppKit process entrypoint.
import AppKit

if CommandLine.arguments.contains("--bench-exit-on-ready") {
	exitForBenchReady()
}

recordBenchStage("process_start")

MainActor.assumeIsolated {
	let app = NSApplication.shared
	let documentController = ItsyDocumentController()
	let appDelegate = AppDelegate(documentController: documentController)

	_ = documentController
	app.setActivationPolicy(.regular)
	app.delegate = appDelegate
	app.run()
}
