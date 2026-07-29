import ItsyVim
import Testing

@Test func vimInputRouterOwnsOperatorAndMotionCommandRouting() {
	var router = VimInputRouter()
	#expect(router.route(commandID: "vim.operator.delete", count: 3, hasSelection: false) == .action(.beginOperator(.delete)))
	#expect(router.engine.pendingOperator == .delete)
	#expect(router.engine.pendingOperatorCount == 3)
	#expect(router.route(commandID: "editor.moveWordForward", count: 1, hasSelection: false) == .action(.applyPendingOperatorMotion("editor.moveWordForward")))
}

@Test func vimInputRouterPreservesEngineStateForHostCommands() {
	var router = VimInputRouter()
	#expect(router.route(commandID: "vim.operator.change", count: 1, hasSelection: false) == .action(.beginOperator(.change)))
	#expect(router.route(commandID: "lsp.definition", count: 1, hasSelection: false) == .hostCommand("lsp.definition"))
	#expect(router.engine.pendingOperator == .change)
	#expect(router.engine.mode == .operatorPending)
}
