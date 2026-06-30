import Foundation
import XCTest
@testable import ollyctl

final class CompletionGeneratorTests: XCTestCase {
    func testCompletionGeneratorUsesArgumentParserScripts() {
        let zsh = OllyCtlCompletionGenerator.script(for: .zsh)
        let bash = OllyCtlCompletionGenerator.script(for: .bash)
        let fish = OllyCtlCompletionGenerator.script(for: .fish)

        XCTAssertTrue(zsh.contains("#compdef ollyctl"))
        XCTAssertTrue(bash.contains("_ollyctl"))
        XCTAssertTrue(fish.contains("complete"))
    }

    func testManpageIncludesRootOptionsAndSubcommands() throws {
        let page = try OllyCtlManpageGenerator(date: Date(timeIntervalSince1970: 0)).render()

        XCTAssertTrue(page.contains(".TH OLLYCTL 1 \"1970-01-01\""))
        XCTAssertTrue(page.contains("ollyctl \\- Control a running olly instance over IPC."))
        XCTAssertTrue(page.contains(".B --completions <shell>"))
        XCTAssertTrue(page.contains(".B manpage"))
        XCTAssertTrue(page.contains(".B state"))
    }
}
