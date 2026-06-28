import AppKit
import Dispatch
import Foundation
import PicoRender

private final class AppDelegate: NSObject, NSApplicationDelegate {
	private var window: NSWindow?

	func applicationDidFinishLaunching(_ notification: Notification) {
		if CommandLine.arguments.contains("--bench-exit-on-ready") {
			let ns = DispatchTime.now().uptimeNanoseconds
			FileHandle.standardOutput.write(Data("READY \(ns)\n".utf8))
			NSApp.terminate(nil)
			return
		}
		let editorView = MetalTextView(frame: NSRect(x: 0, y: 0, width: 960, height: 640))
		let window = NSWindow(
			contentRect: editorView.frame,
			styleMask: [.titled, .closable, .miniaturizable, .resizable],
			backing: .buffered,
			defer: false
		)
		window.title = "Pico"
		window.contentView = editorView
		window.makeFirstResponder(editorView)
		window.center()
		window.makeKeyAndOrderFront(nil)
		NSApp.activate(ignoringOtherApps: true)
		self.window = window
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
