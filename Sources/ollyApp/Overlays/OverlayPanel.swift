import AppKit

@MainActor
final class OverlayPanel: NSPanel {
    init(screen: NSScreen, level: NSWindow.Level = .floating, clickThrough: Bool = true) {
        super.init(
            contentRect: screen.frame,
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        self.level = level
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        isOpaque = false
        backgroundColor = .clear
        hasShadow = false
        ignoresMouseEvents = clickThrough
        isFloatingPanel = true
        hidesOnDeactivate = false
        worksWhenModal = true
        alphaValue = 0
        isReleasedWhenClosed = false
    }

    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}
