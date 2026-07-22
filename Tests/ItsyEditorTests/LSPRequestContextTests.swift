import ItsyEditor
import Testing

@Test func lspRequestContextRejectsTypingVersionAndCursorRaces() {
	let context = LSPRequestContext(
		uri: "file:///tmp/App.swift",
		documentVersion: 4,
		content: "print(value)",
		cursorOffset: 12
	)
	#expect(context.matches(uri: "file:///tmp/App.swift", documentVersion: 4, content: "print(value)", cursorOffset: 12))
	#expect(!context.matches(uri: "file:///tmp/App.swift", documentVersion: 5, content: "print(value)", cursorOffset: 12))
	#expect(!context.matches(uri: "file:///tmp/App.swift", documentVersion: 4, content: "print(values)", cursorOffset: 12))
	#expect(!context.matches(uri: "file:///tmp/App.swift", documentVersion: 4, content: "print(value)", cursorOffset: 13))
	#expect(!context.matches(uri: "file:///tmp/Other.swift", documentVersion: 4, content: "print(value)", cursorOffset: 12))
}
