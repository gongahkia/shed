import AppKit
import CoreGraphics
import Foundation
import ollyKit

final class OverviewModeController {
    private let displayMonitor: DisplayMonitor
    private let snapshotProvider: OverviewSnapshotProvider
    private var overlayWindows: [NSWindow] = []

    init(
        displayMonitor: DisplayMonitor = DisplayMonitor(),
        snapshotProvider: OverviewSnapshotProvider = OverviewSnapshotProvider()
    ) {
        self.displayMonitor = displayMonitor
        self.snapshotProvider = snapshotProvider
    }

    func show() {
        guard overlayWindows.isEmpty else {
            return
        }

        let displays = displayMonitor.displays()
        let windows = snapshotProvider.windows(for: displays)
        overlayWindows = NSScreen.screens.compactMap { screen in
            guard let displayID = DisplayMonitor.displayID(for: screen),
                  let display = displays.first(where: { $0.id == displayID }) else {
                return nil
            }
            return makeOverlayWindow(screen: screen, display: display, windows: windows)
        }

        for window in overlayWindows {
            window.orderFrontRegardless()
            fade(window, to: 1)
        }
    }

    func hide() {
        let windows = overlayWindows
        overlayWindows.removeAll()
        for window in windows {
            fade(window, to: 0) {
                window.close()
            }
        }
    }

    private func makeOverlayWindow(
        screen: NSScreen,
        display: Display,
        windows: [OverviewWindowSnapshot]
    ) -> NSWindow {
        let window = NSWindow(
            contentRect: screen.frame,
            styleMask: [.borderless],
            backing: .buffered,
            defer: false
        )
        window.isOpaque = false
        window.backgroundColor = .clear
        window.alphaValue = 0
        window.level = .screenSaver
        window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
        window.ignoresMouseEvents = false
        window.contentView = OverviewView(
            display: display,
            windows: windows.filter { $0.displayID == display.id },
            onFocus: { [weak self] snapshot in
                self?.focus(snapshot)
                self?.hide()
            }
        )
        return window
    }

    private func focus(_ snapshot: OverviewWindowSnapshot) {
        NSRunningApplication(processIdentifier: snapshot.processID)?.activate()
    }

    private func fade(_ window: NSWindow, to alpha: CGFloat, completion: (() -> Void)? = nil) {
        NSAnimationContext.runAnimationGroup { context in
            context.duration = 0.12
            window.animator().alphaValue = alpha
        } completionHandler: {
            completion?()
        }
    }
}

final class OverviewKeyHoldMonitor {
    private let onActivate: () -> Void
    private let onDeactivate: () -> Void
    private var localMonitor: Any?
    private var globalMonitor: Any?
    private var isActive = false

    init(onActivate: @escaping () -> Void, onDeactivate: @escaping () -> Void) {
        self.onActivate = onActivate
        self.onDeactivate = onDeactivate
    }

    func install() {
        localMonitor = NSEvent.addLocalMonitorForEvents(matching: [.keyDown, .keyUp]) { [weak self] event in
            self?.handle(event)
            return event
        }
        globalMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.keyDown, .keyUp]) { [weak self] event in
            self?.handle(event)
        }
    }

    func remove() {
        if let localMonitor {
            NSEvent.removeMonitor(localMonitor)
        }
        if let globalMonitor {
            NSEvent.removeMonitor(globalMonitor)
        }
        localMonitor = nil
        globalMonitor = nil
    }

    private func handle(_ event: NSEvent) {
        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        let matchesChord = event.keyCode == 49 && flags.contains([.control, .option])
        if event.type == .keyDown && matchesChord && !isActive {
            isActive = true
            onActivate()
        } else if event.type == .keyUp && isActive {
            isActive = false
            onDeactivate()
        }
    }
}

struct OverviewWindowSnapshot: Equatable {
    let windowID: WindowID
    let processID: pid_t
    let title: String
    let frame: CGRect
    let displayID: DisplayID?
}

struct OverviewSnapshotProvider {
    func windows(for displays: [Display]) -> [OverviewWindowSnapshot] {
        let options: CGWindowListOption = [.optionOnScreenOnly, .excludeDesktopElements]
        guard let infoList = CGWindowListCopyWindowInfo(options, kCGNullWindowID) as? [[String: Any]] else {
            return []
        }

        return infoList.compactMap { info in
            guard intValue(info[kCGWindowLayer as String]) == 0,
                  let windowID = intValue(info[kCGWindowNumber as String]).map(WindowID.init),
                  let processID = intValue(info[kCGWindowOwnerPID as String]).map(pid_t.init),
                  let frame = frameValue(info[kCGWindowBounds as String]) else {
                return nil
            }
            let title = (info[kCGWindowName as String] as? String) ?? "Untitled"
            return OverviewWindowSnapshot(
                windowID: windowID,
                processID: processID,
                title: title,
                frame: frame,
                displayID: displayID(for: frame, displays: displays)
            )
        }
    }

    private func displayID(for frame: CGRect, displays: [Display]) -> DisplayID? {
        displays.max { lhs, rhs in
            lhs.frame.intersection(frame).area < rhs.frame.intersection(frame).area
        }?.id
    }

    private func frameValue(_ value: Any?) -> CGRect? {
        guard let dictionary = value as? NSDictionary else {
            return nil
        }
        return CGRect(dictionaryRepresentation: dictionary)
    }

    private func intValue(_ value: Any?) -> Int? {
        if let number = value as? NSNumber {
            return number.intValue
        }
        return value as? Int
    }
}

final class OverviewView: NSView {
    private let display: Display
    private let windows: [OverviewWindowSnapshot]
    private let onFocus: (OverviewWindowSnapshot) -> Void
    private var hitRects: [(CGRect, OverviewWindowSnapshot)] = []

    init(
        display: Display,
        windows: [OverviewWindowSnapshot],
        onFocus: @escaping (OverviewWindowSnapshot) -> Void
    ) {
        self.display = display
        self.windows = windows
        self.onFocus = onFocus
        super.init(frame: .zero)
        wantsLayer = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool {
        true
    }

    override func draw(_ dirtyRect: NSRect) {
        NSColor.black.withAlphaComponent(0.58).setFill()
        bounds.fill()
        drawHeader()
        drawTags()
        drawWindows()
    }

    override func mouseDown(with event: NSEvent) {
        let point = convert(event.locationInWindow, from: nil)
        if let snapshot = hitRects.first(where: { $0.0.contains(point) })?.1 {
            onFocus(snapshot)
        }
    }

    private func drawHeader() {
        let text = "\(display.localizedName) - floating"
        text.draw(
            at: CGPoint(x: 28, y: 26),
            withAttributes: [
                .font: NSFont.boldSystemFont(ofSize: 22),
                .foregroundColor: NSColor.white
            ]
        )
    }

    private func drawTags() {
        let rect = CGRect(x: 28, y: 64, width: 34, height: 24)
        let path = NSBezierPath(roundedRect: rect, xRadius: 6, yRadius: 6)
        NSColor.systemBlue.withAlphaComponent(0.88).setFill()
        path.fill()
        "1".draw(
            in: rect.insetBy(dx: 12, dy: 3),
            withAttributes: [
                .font: NSFont.boldSystemFont(ofSize: 14),
                .foregroundColor: NSColor.white
            ]
        )
    }

    private func drawWindows() {
        hitRects.removeAll()
        let displayRect = display.frame
        guard displayRect.width > 0 && displayRect.height > 0 else {
            return
        }

        for snapshot in windows {
            let rect = miniaturizedRect(snapshot.frame, in: displayRect).insetBy(dx: 3, dy: 3)
            hitRects.append((rect, snapshot))
            drawWindow(snapshot, rect: rect)
        }
    }

    private func miniaturizedRect(_ frame: CGRect, in displayRect: CGRect) -> CGRect {
        let usable = bounds.insetBy(dx: 28, dy: 110)
        let scaleX = usable.width / displayRect.width
        let scaleY = usable.height / displayRect.height
        return CGRect(
            x: usable.minX + (frame.minX - displayRect.minX) * scaleX,
            y: usable.minY + (displayRect.maxY - frame.maxY) * scaleY,
            width: max(44, frame.width * scaleX),
            height: max(30, frame.height * scaleY)
        )
    }

    private func drawWindow(_ snapshot: OverviewWindowSnapshot, rect: CGRect) {
        let path = NSBezierPath(roundedRect: rect, xRadius: 8, yRadius: 8)
        NSColor.windowBackgroundColor.withAlphaComponent(0.92).setFill()
        path.fill()
        NSColor.white.withAlphaComponent(0.25).setStroke()
        path.stroke()

        snapshot.title.draw(
            in: rect.insetBy(dx: 10, dy: 8),
            withAttributes: [
                .font: NSFont.systemFont(ofSize: 12),
                .foregroundColor: NSColor.labelColor
            ]
        )
    }
}

private extension CGRect {
    var area: CGFloat {
        guard !isNull && !isInfinite else {
            return 0
        }
        return max(0, width) * max(0, height)
    }
}
