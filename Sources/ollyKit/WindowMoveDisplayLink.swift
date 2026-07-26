import AppKit
import QuartzCore

final class DisplayLinkFlushCoordinator {
    private weak var mover: WindowMover?
    private let displayMonitor: DisplayMonitor
    private var links: [DisplayID: WindowMoveDisplayLink] = [:]

    init(mover: WindowMover, displayMonitor: DisplayMonitor) {
        self.mover = mover
        self.displayMonitor = displayMonitor
    }

    deinit {
        links.values.forEach { $0.invalidate() }
    }

    func requestFlush(displayID: DisplayID) {
        if let link = links[displayID] {
            link.requestFlush()
            return
        }

        guard let mover, let screen = displayMonitor.screen(for: displayID) ?? NSScreen.main else {
            Task { [weak mover] in
                await mover?.flushPending(displayID: displayID)
            }
            return
        }

        let link = WindowMoveDisplayLink(displayID: displayID, screen: screen, mover: mover)
        links[displayID] = link
        link.requestFlush()
    }
}

private final class WindowMoveDisplayLink: NSObject {
    private let displayID: DisplayID
    private weak var mover: WindowMover?
    private var displayLink: CADisplayLink?

    init(displayID: DisplayID, screen: NSScreen, mover: WindowMover) {
        self.displayID = displayID
        self.mover = mover
        super.init()
        let link = screen.displayLink(target: self, selector: #selector(tick(_:)))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 60, preferred: 60)
        link.isPaused = true
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    func requestFlush() {
        displayLink?.isPaused = false
    }

    func invalidate() {
        displayLink?.invalidate()
    }

    @objc private func tick(_ link: CADisplayLink) {
        link.isPaused = true
        Task { [weak self] in
            guard let self, let mover else {
                return
            }
            await mover.flushPending(displayID: displayID)
        }
    }
}
