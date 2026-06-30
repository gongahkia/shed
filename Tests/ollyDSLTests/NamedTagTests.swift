import XCTest
import ollyCore
@testable import ollyDSL

final class NamedTagTests: XCTestCase {
    func testTagNamedDeclarationsAssignSequentialSlots() throws {
        let workspaces = try Workspaces(validating: [
            Tag.named("comms"),
            Tag.named("code")
        ])

        XCTAssertEqual(workspaces.tags[0], NamedTag(name: "comms", tag: try Tag(index: 0)))
        XCTAssertEqual(workspaces.tags[1], NamedTag(name: "code", tag: try Tag(index: 1)))
        XCTAssertEqual(workspaces.tag(named: "code"), try Tag(index: 1))
    }

    func testConfigStoresNamedTagsFromWorkspacesBuilder() throws {
        let config = Config {
            Workspaces {
                Tag.named("comms")
                Tag.named("code")
            }
        }

        XCTAssertEqual(config.workspaces.tag(named: "comms"), try Tag(index: 0))
        XCTAssertEqual(config.workspaces.tag(named: "code"), try Tag(index: 1))
    }

    func testTagDeclarationCanCarryGlobalEngineBinding() throws {
        let config = Config {
            Workspaces {
                Tag.named("code").engine(.bsp)
            }
        }
        let tag = try XCTUnwrap(config.workspaces.tag(named: "code"))

        XCTAssertEqual(config.workspaces.engineBinding(for: tag, on: 42), .bsp)
    }

    func testDisplayWorkspaceDeclarationsShareTagsAndOverrideGlobalBindings() throws {
        let config = Config {
            Workspaces {
                Tag.named("web").engine(.floating)
                display(1) {
                    Tag.named("web").engine(.bsp)
                }
                display(2) {
                    Tag.named("web").engine(.tabbed)
                }
            }
        }
        let tag = try XCTUnwrap(config.workspaces.tag(named: "web"))

        XCTAssertEqual(config.workspaces.tags, [NamedTag(name: "web", tag: tag)])
        XCTAssertEqual(config.workspaces.engineBinding(for: tag, on: 1), .bsp)
        XCTAssertEqual(config.workspaces.engineBinding(for: tag, on: 2), .tabbed)
        XCTAssertEqual(config.workspaces.engineBinding(for: tag, on: 3), .floating)
    }

    func testDuplicateNamesThrow() {
        XCTAssertThrowsError(
            try Workspaces(validating: [
                Tag.named("code"),
                Tag.named("code")
            ])
        ) { error in
            XCTAssertEqual(error as? WorkspacesError, .duplicateTagName("code"))
        }
    }

    func testMoreThanSixtyFourTagsThrows() {
        let declarations = (0..<65).map { index in
            NamedTagDeclaration(uncheckedName: "tag-\(index)")
        }

        XCTAssertThrowsError(try Workspaces(validating: declarations)) { error in
            XCTAssertEqual(error as? WorkspacesError, .tooManyTags(65))
        }
    }
}
