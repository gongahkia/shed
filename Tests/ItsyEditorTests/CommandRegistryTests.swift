import ItsyEditor
import Testing

@Test func commandRegistryStoresAndRunsCommandsInOrder() throws {
	var registry = CommandRegistry()
	var transcript: [String] = []
	try registry.register([
		Command(id: "file.open", title: "Open File", defaultKey: "Cmd-O") {
			transcript.append("open")
		},
		Command(id: "file.save", title: "Save File", defaultKey: "Cmd-S") {
			transcript.append("save")
		},
		Command(id: "internal.noop", title: "Hidden", isHidden: true) {
			transcript.append("hidden")
		},
	])

	#expect(registry.commands.map(\.id) == ["file.open", "file.save"])
	#expect(registry.allCommands.map(\.id) == ["file.open", "file.save", "internal.noop"])
	#expect(registry.command(id: "file.save")?.title == "Save File")
	try registry.run(id: "file.open")
	try registry.run(id: "file.save")
	try registry.run(id: "internal.noop")
	#expect(transcript == ["open", "save", "hidden"])
}

@Test func commandRegistryRejectsDuplicateAndMissingIDs() throws {
	var registry = CommandRegistry()
	try registry.register(Command(id: "view.palette", title: "Command Palette") {})

	do {
		try registry.register(Command(id: "view.palette", title: "Other Palette") {})
		Issue.record("duplicate id should throw")
	} catch CommandRegistryError.duplicateID("view.palette") {
	} catch {
		Issue.record("unexpected duplicate error: \(error)")
	}

	do {
		try registry.run(id: "missing")
		Issue.record("missing id should throw")
	} catch CommandRegistryError.missingID("missing") {
	} catch {
		Issue.record("unexpected missing error: \(error)")
	}
}
