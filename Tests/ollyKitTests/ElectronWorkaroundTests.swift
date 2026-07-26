import CoreGraphics
import XCTest
@testable import ollyKit

final class ElectronWorkaroundTests: XCTestCase {
    func testFallbackRequiresKnownBundleAndZeroAXWindows() {
        let workaround = ElectronWorkaround(bundleIDs: ["com.example.Electron"])

        XCTAssertTrue(
            workaround.shouldUseFallback(bundleIdentifier: "com.example.Electron", axWindowCount: 0)
        )
        XCTAssertFalse(
            workaround.shouldUseFallback(bundleIdentifier: "com.example.Electron", axWindowCount: 1)
        )
        XCTAssertFalse(
            workaround.shouldUseFallback(bundleIdentifier: "com.example.Native", axWindowCount: 0)
        )
    }

    func testFallbackWindowsFilterByPIDLayerAndBounds() {
        let frame = CGRect(x: 10, y: 20, width: 300, height: 200)
        let windows = ElectronWorkaround.fallbackWindows(
            processID: 42,
            windowInfo: [
                windowInfo(processID: 42, windowID: 2, title: "Main", frame: frame),
                windowInfo(processID: 7, windowID: 3, title: "Other PID", frame: frame),
                windowInfo(processID: 42, windowID: 4, title: "Layer", frame: frame, layer: 1),
                windowInfo(processID: 42, windowID: 5, title: "Empty", frame: .zero)
            ]
        )

        XCTAssertEqual(windows, [
            ElectronFallbackWindow(windowID: 2, processID: 42, frame: frame, title: "Main")
        ])
    }

    private func windowInfo(
        processID: Int,
        windowID: Int,
        title: String,
        frame: CGRect,
        layer: Int = 0
    ) -> [String: Any] {
        [
            kCGWindowOwnerPID as String: NSNumber(value: processID),
            kCGWindowNumber as String: NSNumber(value: windowID),
            kCGWindowLayer as String: NSNumber(value: layer),
            kCGWindowName as String: title,
            kCGWindowBounds as String: [
                "X": frame.origin.x,
                "Y": frame.origin.y,
                "Width": frame.size.width,
                "Height": frame.size.height
            ]
        ]
    }
}
