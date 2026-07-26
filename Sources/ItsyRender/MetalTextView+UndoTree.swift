import ItsyEditor

public extension MetalTextView {
	@discardableResult
	func restoreUndoTreeNode(_ id: Int) -> Bool {
		guard editor.restoreUndoTreeNode(id: id) else {
			return false
		}
		syncEditorState()
		editorDidChange?(editor)
		return true
	}
}
