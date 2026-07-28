@testable import ItsyApp
import Testing

@Test func benchScenarioParsesPaletteExpectations() {
	let request = BenchScenarioRequest.current(arguments: [
		"Itsy",
		"--bench-scenario=palette",
		"--bench-query=Module00001.swift",
		"--bench-expected-top=src/group-01/Module00001.swift",
		"--bench-expected-results=1",
		"--bench-exit-after-scenario",
	])

	#expect(request?.scenario == .palette)
	#expect(request?.query == "Module00001.swift")
	#expect(request?.expectedTop == "src/group-01/Module00001.swift")
	#expect(request?.expectedResultCount == 1)
	#expect(request?.exitAfterCompletion == true)
}

@Test func benchScenarioRejectsUnknownNames() {
	#expect(BenchScenarioRequest.current(arguments: ["Itsy", "--bench-scenario=unknown"]) == nil)
}
