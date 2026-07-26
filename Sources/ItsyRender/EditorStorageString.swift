import ItsyEditor

func editorStorageString(_ value: Editor) -> String {
	value.textStorage.substring(0 ..< value.textStorage.length)
}
