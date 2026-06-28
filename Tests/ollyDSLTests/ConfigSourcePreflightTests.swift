import XCTest
@testable import ollyDSL

final class ConfigSourcePreflightTests: XCTestCase {
    func testDuplicateTagNameFailsBeforeSwiftC() throws {
        let source = """
        import ollyDSL

        public func ollyConfig() -> Config {
            Config {
                Workspaces {
                    Tag.named("code")
                    Tag.named("code")
                }
            }
        }
        """

        try assertPreflightFailure(source: source, contains: ["duplicate-tag-name", "\"code\""])
    }

    func testDuplicateChordFailsBeforeSwiftC() throws {
        let source = """
        import ollyDSL

        public func ollyConfig() -> Config {
            Config {
                Keybinds {
                    Keybind(KeyChord([.option, .shift], .j), do: .focus(.next))
                    Keybind(KeyChord([.shift, .option], .j), do: .swap(.next))
                }
            }
        }
        """

        try assertPreflightFailure(source: source, contains: ["duplicate-chord", "option+shift+j"])
    }

    private func assertPreflightFailure(source: String, contains expectedFragments: [String]) throws {
        let directory = try temporaryDirectory()
        let sourceURL = directory.appendingPathComponent("Config.swift")
        let compilerURL = directory.appendingPathComponent("fake-swiftc")
        let countURL = directory.appendingPathComponent("compile-count")
        try source.write(to: sourceURL, atomically: true, encoding: .utf8)
        try fakeCompiler(countURL: countURL).write(to: compilerURL, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: compilerURL.path)

        let loader = ConfigLoader(
            sourceURL: sourceURL,
            cacheDirectory: directory.appendingPathComponent("cache", isDirectory: true),
            swiftcURL: compilerURL
        )

        XCTAssertThrowsError(try loader.load()) { error in
            guard case let ConfigLoaderError.compileFailed(command, exitCode, output) = error else {
                return XCTFail("expected compileFailed, got \(error)")
            }
            XCTAssertEqual(command, "olly-config-preflight \(sourceURL.path)")
            XCTAssertEqual(exitCode, 1)
            for fragment in expectedFragments {
                XCTAssertTrue(output.contains(fragment), output)
            }
            XCTAssertFalse(FileManager.default.fileExists(atPath: countURL.path))
        }
    }

    private func temporaryDirectory() throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ollyDSLTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func fakeCompiler(countURL: URL) -> String {
        """
        #!/bin/sh
        out=
        while [ "$#" -gt 0 ]; do
          if [ "$1" = "-o" ]; then
            shift
            out="$1"
          fi
          shift
        done
        mkdir -p "$(dirname "$out")"
        printf 'fake dylib' > "$out"
        if [ -f "\(countURL.path)" ]; then
          count=$(cat "\(countURL.path)")
        else
          count=0
        fi
        expr "$count" + 1 > "\(countURL.path)"
        """
    }
}
