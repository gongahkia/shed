import Foundation
import ollyDSL

final class HotKeyDiagnosticsStore {
    static let shared = HotKeyDiagnosticsStore()

    private let lock = NSLock()
    private var report: HotKeyCollisionReport?

    func update(_ report: HotKeyCollisionReport) {
        lock.lock()
        self.report = report
        lock.unlock()
    }

    func clear() {
        lock.lock()
        report = nil
        lock.unlock()
    }

    func currentReport() -> HotKeyCollisionReport? {
        lock.lock()
        defer { lock.unlock() }
        return report
    }
}
