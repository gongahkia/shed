import AppKit
import XCTest
import ollyCore
import ollyIPC
import ollyKit
import ollyRuntime
@testable import ollyApp

final class OverlayAccessibilityTests: XCTestCase {
    @MainActor
    func testSnapZoneViewExposesLayoutAreaAndButtonChildren() throws {
        let view = SnapZoneView(frame: CGRect(x: 0, y: 0, width: 300, height: 200))
        let zones = [
            SnapZone(position: .leftHalf, frame: CGRect(x: 0, y: 0, width: 150, height: 200)),
            SnapZone(position: .rightHalf, frame: CGRect(x: 150, y: 0, width: 150, height: 200))
        ]

        view.update(zones: zones, highlighted: .leftHalf, animateHighlight: false)

        XCTAssertTrue(view.isAccessibilityElement())
        XCTAssertEqual(view.accessibilityRole(), .layoutArea)
        XCTAssertEqual(view.accessibilityLabel(), "Snap zones")
        let children = try XCTUnwrap(view.accessibilityChildren() as? [NSAccessibilityElement])
        XCTAssertEqual(children.count, 2)
        XCTAssertEqual(children[0].accessibilityRole(), .button)
        XCTAssertEqual(children[0].accessibilityLabel(), "Snap left half")
    }

    @MainActor
    func testFocusRingIsHiddenAccessibilityDecoration() {
        let view = FocusRingView()

        XCTAssertTrue(view.isAccessibilityElement())
        XCTAssertEqual(view.accessibilityRole(), .unknown)
        XCTAssertEqual(view.accessibilityLabel(), "Focus ring")
        XCTAssertTrue(view.isAccessibilityHidden())
    }

    @MainActor
    func testAltTabViewExposesListWithButtonChildren() throws {
        let view = AltTabSwitcherView(thumbnailCache: WindowThumbnailCache(capture: { _, _ in Self.image() }))
        view.frame = CGRect(x: 0, y: 0, width: 500, height: 300)

        view.configure(windows: Self.windows(), selectedID: 1, mode: .grid, reduceMotion: true)

        XCTAssertEqual(view.accessibilityRole(), .list)
        XCTAssertEqual(view.accessibilityLabel(), "Alt-Tab windows")
        let children = try XCTUnwrap(view.accessibilityChildren() as? [WindowThumbnailView])
        XCTAssertEqual(children.count, 2)
        XCTAssertEqual(children[0].accessibilityRole(), .button)
        XCTAssertEqual(children[0].accessibilityLabel(), "window 1")
    }

    @MainActor
    func testCheatsheetViewExposesListWithStaticTextRows() throws {
        let view = CheatsheetView()

        view.configure(entries: [
            CheatsheetEntry(category: .focus, title: "Focus left", detail: "opt+h")
        ])

        XCTAssertEqual(view.accessibilityRole(), .list)
        XCTAssertEqual(view.accessibilityLabel(), "Keybinds")
        let children = try XCTUnwrap(view.accessibilityChildren() as? [CommandPaletteRowView])
        XCTAssertEqual(children.count, 1)
        XCTAssertEqual(children[0].accessibilityRole(), .staticText)
        XCTAssertEqual(children[0].accessibilityLabel(), "Focus left, opt+h")
    }

    @MainActor
    func testOverviewViewExposesLayoutArea() {
        let view = OverviewView(display: Self.display(), windows: [], onFocus: { _ in })

        XCTAssertTrue(view.isAccessibilityElement())
        XCTAssertEqual(view.accessibilityRole(), .layoutArea)
        XCTAssertEqual(view.accessibilityLabel(), "Overview")
    }

    private static func windows() -> [WindowState] {
        [
            WindowState(
                id: 1,
                processID: 42,
                bundleID: "dev.olly.test",
                displayID: 1,
                tagMask: 1,
                layoutOrder: 1,
                frame: CGRect(x: 0, y: 0, width: 200, height: 100),
                title: "window 1"
            ),
            WindowState(
                id: 2,
                processID: 42,
                bundleID: "dev.olly.test",
                displayID: 1,
                tagMask: 1,
                layoutOrder: 2,
                frame: CGRect(x: 0, y: 0, width: 200, height: 100),
                title: "window 2"
            )
        ]
    }

    private static func display() -> Display {
        Display(
            id: 1,
            frame: CGRect(x: 0, y: 0, width: 500, height: 300),
            visibleFrame: CGRect(x: 0, y: 0, width: 500, height: 280),
            scaleFactor: 2,
            localizedName: "Built-in",
            isMain: true
        )
    }

    private static func image() -> CGImage {
        guard let context = CGContext(
            data: nil,
            width: 2,
            height: 2,
            bitsPerComponent: 8,
            bytesPerRow: 8,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ), let image = context.makeImage() else {
            fatalError("test image unavailable")
        }
        return image
    }
}
