import ItsyEditor
import Testing

@Test func lspNavigationHistoryRestoresExactOriginSelections() {
	let origin = LSPNavigationLocation(uri: "file:///tmp/A.swift", selection: 7 ..< 12)
	let definition = LSPNavigationLocation(uri: "file:///tmp/B.swift", selection: 31 ..< 35)
	let implementation = LSPNavigationLocation(uri: "file:///tmp/C.swift", selection: 2 ..< 2)
	var history = LSPNavigationHistory()
	history.recordJump(from: origin, to: definition)
	history.recordJump(from: definition, to: implementation)
	#expect(history.goBack(from: implementation) == definition)
	#expect(history.goBack(from: definition) == origin)
	#expect(history.goForward(from: origin) == definition)
	#expect(history.goForward(from: definition) == implementation)
}
