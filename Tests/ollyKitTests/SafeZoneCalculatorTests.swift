import CoreGraphics
import XCTest
@testable import ollyKit

final class SafeZoneCalculatorTests: XCTestCase {
    func testLayoutFrameReservesMenuBarFromVisibleFrame() {
        let display = makeDisplay(
            frame: CGRect(x: 0, y: 0, width: 1000, height: 800),
            visibleFrame: CGRect(x: 0, y: 0, width: 1000, height: 778)
        )

        let result = SafeZoneCalculator().result(for: display)

        XCTAssertEqual(result.layoutFrame, CGRect(x: 0, y: 0, width: 1000, height: 778))
        XCTAssertEqual(
            result.reserves,
            [
                SafeZoneReserve(
                    displayID: 1,
                    kind: .menuBar,
                    rect: CGRect(x: 0, y: 778, width: 1000, height: 22)
                )
            ]
        )
    }

    func testLayoutFrameUsesNotchReserveWhenLargerThanMenuBar() {
        let display = makeDisplay(
            frame: CGRect(x: 0, y: 0, width: 1512, height: 982),
            visibleFrame: CGRect(x: 0, y: 0, width: 1512, height: 958),
            safeAreaInsets: DisplaySafeAreaInsets(top: 74)
        )

        let result = SafeZoneCalculator(notchPadding: 12).result(for: display)

        XCTAssertEqual(result.layoutFrame, CGRect(x: 0, y: 0, width: 1512, height: 896))
        XCTAssertEqual(
            result.reserves,
            [
                SafeZoneReserve(
                    displayID: 1,
                    kind: .menuBar,
                    rect: CGRect(x: 0, y: 958, width: 1512, height: 24)
                ),
                SafeZoneReserve(
                    displayID: 1,
                    kind: .notch,
                    rect: CGRect(x: 0, y: 896, width: 1512, height: 86)
                )
            ]
        )
    }

    func testLayoutFrameDoesNotAddNotchPaddingWithoutNotchInset() {
        let display = makeDisplay(
            frame: CGRect(x: 0, y: 0, width: 800, height: 600),
            visibleFrame: CGRect(x: 0, y: 0, width: 800, height: 600)
        )

        let result = SafeZoneCalculator(notchPadding: 12).result(for: display)

        XCTAssertEqual(result.layoutFrame, display.frame)
        XCTAssertTrue(result.reserves.isEmpty)
    }

    private func makeDisplay(
        frame: CGRect,
        visibleFrame: CGRect,
        safeAreaInsets: DisplaySafeAreaInsets = DisplaySafeAreaInsets()
    ) -> Display {
        Display(
            id: 1,
            frame: frame,
            visibleFrame: visibleFrame,
            safeAreaInsets: safeAreaInsets,
            scaleFactor: 2,
            localizedName: "Display",
            isMain: true
        )
    }
}
