import AppKit
import Dispatch
import Foundation

private final class AppDelegate: NSObject, NSApplicationDelegate {
	func applicationDidFinishLaunching(_ notification: Notification) {
		if CommandLine.arguments.contains("--bench-exit-on-ready") {
			let ns = DispatchTime.now().uptimeNanoseconds
			FileHandle.standardOutput.write(Data("READY \(ns)\n".utf8))
			NSApp.terminate(nil)
		}
	}
}

private let appDelegate = AppDelegate()

@main
enum PicoAppMain {
	static func main() {
		let app = NSApplication.shared
		app.setActivationPolicy(.regular)
		app.delegate = appDelegate
		app.run()
	}
}
