import CoreGraphics
import Foundation
import XCTest
import ollyCore
import ollyIPC
import ollyKit

final class IPCStateSnapshotTests: XCTestCase {
    func testTagIndexRejectsOutOfRangeValues() {
        XCTAssertThrowsError(try IPCTagIndex(validating: 64))
        XCTAssertThrowsError(try JSONDecoder().decode(IPCTagIndex.self, from: Data("64".utf8)))
    }

    func testWindowStateConvertsToIPCShape() throws {
        let tagSet = TagSet([try Tag(index: 1), try Tag(index: 3)])
        let state = WindowState(
            id: 77,
            processID: 123,
            bundleID: "com.example.Editor",
            displayID: 9,
            tagMask: tagSet.rawValue,
            isFloating: true,
            layoutOrder: 4,
            frame: CGRect(x: 10, y: 20, width: 300, height: 400),
            title: "Editor",
            role: "AXWindow",
            subrole: "AXStandardWindow"
        )

        let ipcState = IPCWindowState(state: state)

        XCTAssertEqual(ipcState.windowID, 77)
        XCTAssertEqual(ipcState.processID, 123)
        XCTAssertEqual(ipcState.bundleID, "com.example.Editor")
        XCTAssertEqual(ipcState.displayID, 9)
        XCTAssertEqual(ipcState.tags.map(\.rawValue), [1, 3])
        XCTAssertTrue(ipcState.isFloating)
        XCTAssertEqual(ipcState.layoutOrder, 4)
        XCTAssertEqual(ipcState.frame, IPCFrame(x: 10, y: 20, width: 300, height: 400))
        XCTAssertEqual(ipcState.title, "Editor")
    }

    func testDisplayStateConvertsToIPCShape() throws {
        let tag = try Tag(index: 2)
        let state = DisplayTagState(
            displayID: 11,
            activeTags: TagSet(tag),
            tagToEngine: [tag: LayoutEngineID(rawValue: "master-stack")],
            mruHistory: [TagSet(tag)]
        )

        let ipcState = IPCDisplayState(state: state)

        XCTAssertEqual(ipcState.displayID, 11)
        XCTAssertEqual(ipcState.activeTags.map(\.rawValue), [2])
        XCTAssertEqual(ipcState.tagEngines, [
            IPCTagEngineBinding(tag: try IPCTagIndex(validating: 2), engineID: LayoutEngineID(rawValue: "master-stack"))
        ])
        XCTAssertEqual(ipcState.mruHistory.map { $0.map(\.rawValue) }, [[2]])
    }
}
