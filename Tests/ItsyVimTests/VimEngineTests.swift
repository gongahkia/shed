import Foundation
import ItsyVim
import Testing

@Test func vimEngineStartsNormalAndRepeatsMotionCount() {
	var engine = VimEngine()
	let buffer = TestBuffer("alpha beta\n")

	#expect(engine.mode == .normal)
	#expect(engine.handle(Key("3"), buffer: buffer).isEmpty)
	#expect(engine.pendingCount == 3)
	#expect(engine.handle(Key("w"), buffer: buffer) == [.move(.wordForward), .move(.wordForward), .move(.wordForward)])
	#expect(engine.pendingCount == nil)
}

@Test func vimEngineTracksOperatorPendingState() {
	var engine = VimEngine()
	let buffer = TestBuffer("alpha beta\n")

	#expect(engine.handle(Key("d"), buffer: buffer) == [.setOperator(.delete), .setMode(.operatorPending)])
	#expect(engine.mode == .operatorPending)
	#expect(engine.pendingOperator == .delete)
	#expect(engine.handle(Key("w"), buffer: buffer) == [.command("operator.delete.wordForward"), .setMode(.normal)])
	#expect(engine.mode == .normal)
	#expect(engine.pendingOperator == nil)
}

@Test func vimEngineTracksRegistersAndMacros() {
	var engine = VimEngine()
	let buffer = TestBuffer("alpha beta\n")

	#expect(engine.handle(Key("\""), buffer: buffer).isEmpty)
	#expect(engine.handle(Key("a"), buffer: buffer) == [.setRegister("a")])
	#expect(engine.register == VimRegister("a"))
	#expect(engine.handle(Key("q"), buffer: buffer).isEmpty)
	#expect(engine.handle(Key("b"), buffer: buffer) == [.beginMacroRecord("b")])
	#expect(engine.macroRecording == "b")
	#expect(engine.handle(Key("q"), buffer: buffer) == [.endMacroRecord, .command("macro.b.saved")])
	#expect(engine.macroRecording == nil)
	#expect(engine.handle(Key("@"), buffer: buffer).isEmpty)
	#expect(engine.handle(Key("b"), buffer: buffer) == [.playMacro("b")])
}

@Test func vimEngineTracksVisualSearchAndMarks() {
	var engine = VimEngine()
	let buffer = TestBuffer("alpha beta\n")

	#expect(engine.handle(Key("v"), buffer: buffer) == [.setMode(.visual(.character))])
	#expect(engine.handle(Key("escape"), buffer: buffer) == [.setMode(.normal)])
	#expect(engine.handle(Key("/"), buffer: buffer) == [.setMode(.command), .search(SearchQuery(text: "", direction: .forward))])
	#expect(engine.lastSearch == SearchQuery(text: "", direction: .forward))
	#expect(engine.handle(Key("return"), buffer: buffer) == [.setMode(.normal)])
	#expect(engine.handle(Key("m"), buffer: buffer).isEmpty)
	#expect(engine.handle(Key("a"), buffer: buffer) == [.setMark("a", Position(offset: 0))])
	#expect(engine.marks["a"] == Position(offset: 0))
	#expect(engine.handle(Key("'"), buffer: buffer).isEmpty)
	#expect(engine.handle(Key("a"), buffer: buffer) == [.jumpToMark("a")])
}

@Test func vimMarkStorePersistsWorkspaceMarks() throws {
	let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
	defer { try? FileManager.default.removeItem(at: directory) }
	let store = VimMarkStore(directory: directory)
	let workspace = URL(fileURLWithPath: "/tmp/itsy-workspace")

	try store.save(["a": Position(offset: 42), "z": Position(offset: 7)], workspaceRoot: workspace)

	#expect(store.load(workspaceRoot: workspace) == ["a": Position(offset: 42), "z": Position(offset: 7)])
	#expect(store.marksURL(workspaceRoot: workspace).lastPathComponent == VimMarkStore.workspaceHash(for: workspace) + ".json")
}

private struct TestBuffer: BufferQuery {
	private let text: String

	init(_ text: String) {
		self.text = text
	}

	var length: Int {
		text.utf8.count
	}

	func line(forOffset offset: Int) -> Int {
		text.utf8.prefix(max(0, min(offset, length))).filter { $0 == 10 }.count
	}

	func substring(_ range: Range<Int>) -> String {
		let lower = text.utf8.index(text.utf8.startIndex, offsetBy: range.lowerBound)
		let upper = text.utf8.index(text.utf8.startIndex, offsetBy: range.upperBound)
		return String(decoding: text.utf8[lower ..< upper], as: UTF8.self)
	}

	func graphemeBoundary(after offset: Int) -> Int {
		min(length, offset + 1)
	}
}
