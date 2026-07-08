@testable import ItsyApp
import Testing

@Test func commandPaletteLineTargetParsesLineAndColumn() {
	#expect(CommandPaletteLineTarget.parse(":123") == CommandPaletteLineTarget(line: 123, column: 1))
	#expect(CommandPaletteLineTarget.parse("123") == CommandPaletteLineTarget(line: 123, column: 1))
	#expect(CommandPaletteLineTarget.parse("123:45") == CommandPaletteLineTarget(line: 123, column: 45))
	#expect(CommandPaletteLineTarget.parse(":123:45") == CommandPaletteLineTarget(line: 123, column: 45))
	#expect(CommandPaletteLineTarget.parse("123:") == CommandPaletteLineTarget(line: 123, column: 1))
	#expect(CommandPaletteLineTarget.parse("0") == nil)
	#expect(CommandPaletteLineTarget.parse("123:0") == nil)
	#expect(CommandPaletteLineTarget.parse("abc") == nil)
}

@Test func commandPaletteFileFilterRanksWorkspacePaths() {
	let paths = [
		"README.md",
		"Sources/ItsyApp/Palette/CommandPaletteCoordinator.swift",
		"Tests/ItsyAppTests/CommandPaletteModelsTests.swift",
	]

	#expect(CommandPaletteFileFilter.ranked(paths: paths, query: "", limit: 2) == Array(paths.prefix(2)))
	#expect(CommandPaletteFileFilter.ranked(paths: paths, query: "cpcoord") == [
		"Sources/ItsyApp/Palette/CommandPaletteCoordinator.swift",
	])
}
