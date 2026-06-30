import ApplicationServices
import Foundation

public final class FocusInputAttribution: @unchecked Sendable {
    public static let shared = FocusInputAttribution()

    private let queue = DispatchQueue(label: "dev.olly.focus.input-attribution")
    private var tap: CFMachPort?
    private var runLoopSource: CFRunLoopSource?
    private var healthTimer: DispatchSourceTimer?
    private var recentInputTimesByProcessID: [pid_t: Date] = [:]

    public init() {}

    public func start() {
        guard CGPreflightListenEventAccess() else {
            _ = CGRequestListenEventAccess()
            return
        }
        queue.async { [self] in
            installTapIfNeeded()
        }
    }

    public func stop() {
        queue.async { [self] in
            healthTimer?.cancel()
            healthTimer = nil
            if let source = runLoopSource {
                CFRunLoopRemoveSource(CFRunLoopGetMain(), source, .commonModes)
            }
            if let tap {
                CFMachPortInvalidate(tap)
            }
            runLoopSource = nil
            tap = nil
            recentInputTimesByProcessID.removeAll(keepingCapacity: true)
        }
    }

    public func hasRecentInput(pid: pid_t, within interval: TimeInterval = 0.25) -> Bool {
        queue.sync {
            recentInputTimesByProcessID[pid].map { Date().timeIntervalSince($0) < interval } ?? false
        }
    }

    public func recordInput(pid: pid_t, at date: Date = Date()) {
        queue.sync {
            recentInputTimesByProcessID[pid] = date
        }
    }

    private func installTapIfNeeded() {
        guard tap == nil else {
            return
        }
        let mask = (1 << CGEventType.leftMouseDown.rawValue) | (1 << CGEventType.keyDown.rawValue)
        let ref = Unmanaged.passUnretained(self).toOpaque()
        guard let port = CGEvent.tapCreate(
            tap: .cgSessionEventTap,
            place: .headInsertEventTap,
            options: .listenOnly,
            eventsOfInterest: CGEventMask(mask),
            callback: Self.eventTapCallback,
            userInfo: ref
        ) else {
            return
        }
        tap = port
        let source = CFMachPortCreateRunLoopSource(nil, port, 0)
        runLoopSource = source
        CFRunLoopAddSource(CFRunLoopGetMain(), source, .commonModes)
        CGEvent.tapEnable(tap: port, enable: true)
        scheduleHealthMonitor()
    }

    private func scheduleHealthMonitor() {
        healthTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 5, repeating: 5)
        timer.setEventHandler { [weak self] in
            self?.repairTapIfNeeded()
        }
        timer.resume()
        healthTimer = timer
    }

    private func repairTapIfNeeded() {
        guard let tap else {
            installTapIfNeeded()
            return
        }
        guard !CGEvent.tapIsEnabled(tap: tap) else {
            return
        }
        CGEvent.tapEnable(tap: tap, enable: true)
        if !CGEvent.tapIsEnabled(tap: tap) {
            self.tap = nil
            installTapIfNeeded()
        }
    }

    private static let eventTapCallback: CGEventTapCallBack = { _, type, event, refcon in
        guard let refcon else {
            return Unmanaged.passUnretained(event)
        }
        let attribution = Unmanaged<FocusInputAttribution>.fromOpaque(refcon).takeUnretainedValue()
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            attribution.queue.async {
                if let tap = attribution.tap {
                    CGEvent.tapEnable(tap: tap, enable: true)
                }
            }
            return nil
        }
        let pid = pid_t(event.getIntegerValueField(.eventTargetUnixProcessID))
        attribution.recordInput(pid: pid)
        return Unmanaged.passUnretained(event)
    }
}
