import AppKit
import Carbon.HIToolbox
import XCTest
import ollyIPC
import ollyKit
import ollyRuntime
@testable import ollyApp

final class GridOverlayControllerTests: XCTestCase {
    func testNavigatorMovesBetweenGridZones() {
        XCTAssertEqual(GridZoneNavigator.move(from: .center, direction: .right), .rightHalf)
        XCTAssertEqual(GridZoneNavigator.move(from: .center, direction: .left), .leftHalf)
        XCTAssertEqual(GridZoneNavigator.move(from: .center, direction: .upward), .topHalf)
        XCTAssertEqual(GridZoneNavigator.move(from: .center, direction: .downward), .bottomHalf)
        XCTAssertEqual(GridZoneNavigator.move(from: .bottomHalf, direction: .downward), .maximize)
        XCTAssertEqual(GridZoneNavigator.move(from: .maximize, direction: .upward), .center)
    }

    func testKeyActionMatchesCommandQuestionAndGridControls() throws {
        XCTAssertEqual(
            GridOverlayKeyAction.action(for: try keyEvent(keyCode: kVK_ANSI_Slash, flags: [.command, .shift]), isGridActive: false),
            .showGrid
        )
        XCTAssertEqual(
            GridOverlayKeyAction.action(for: try keyEvent(keyCode: kVK_RightArrow), isGridActive: true),
            .move(.right)
        )
        XCTAssertEqual(
            GridOverlayKeyAction.action(for: try keyEvent(keyCode: kVK_Return), isGridActive: true),
            .commit
        )
        XCTAssertEqual(
            GridOverlayKeyAction.action(for: try keyEvent(keyCode: kVK_RightArrow), isGridActive: false),
            .none
        )
    }

    @MainActor
    func testGridOverlayCommitsSelectedZoneAndCleansUpPanels() async throws {
        guard let screen = NSScreen.screens.first,
              let displayID = DisplayMonitor.displayID(for: screen) else {
            throw XCTSkip("no screen available")
        }
        let host = OverlayPanelHost(notificationCenter: NotificationCenter())
        let runtime = OllyRuntime()
        var committed: IPCSnapWindowCommand?
        let controller = GridOverlayController(
            runtime: runtime,
            overlayHost: host,
            displayIDProvider: { displayID },
            layoutFrameProvider: { _ in screen.frame },
            snapCommit: { command in committed = command },
            reduceMotionProvider: { true }
        )
        defer {
            controller.stop()
        }

        await controller.show()
        await controller.handle(.move(.right))
        await controller.handle(.commit)
        try await Task.sleep(nanoseconds: 25_000_000)

        XCTAssertEqual(controller.selectedPosition, .center)
        XCTAssertEqual(committed, IPCSnapWindowCommand(position: .rightHalf, displayID: displayID))
        XCTAssertEqual(controller.activeOverlayCount, 0)
    }

    private func keyEvent(
        keyCode: Int,
        flags: NSEvent.ModifierFlags = []
    ) throws -> NSEvent {
        try XCTUnwrap(NSEvent.keyEvent(
            with: .keyDown,
            location: .zero,
            modifierFlags: flags,
            timestamp: 0,
            windowNumber: 0,
            context: nil,
            characters: "",
            charactersIgnoringModifiers: "",
            isARepeat: false,
            keyCode: UInt16(keyCode)
        ))
    }
}
