import ollyDSL

actor HookDispatcher {
    private var hooks = Hooks()

    func update(_ hooks: Hooks) {
        self.hooks = hooks
    }

    func tagSwitch(_ context: TagSwitchHookContext) {
        hooks.runTagSwitch(context: context)
    }

    func displayChange(_ context: DisplayChangeHookContext) {
        hooks.runDisplayChange(context: context)
    }

    func windowAppeared(_ context: WindowAppearedHookContext) {
        hooks.runWindowAppeared(context: context)
    }

    func windowClosed(_ context: WindowClosedHookContext) {
        hooks.runWindowClosed(context: context)
    }

    func engineChange(_ context: EngineChangeHookContext) {
        hooks.runEngineChange(context: context)
    }

    func fullscreen(_ context: FullscreenHookContext) {
        if context.didEnter {
            hooks.runFullscreenEnter(context: context)
        } else {
            hooks.runFullscreenExit(context: context)
        }
    }

    func axPermissionChanged(_ context: AXPermissionHookContext) {
        hooks.runAXPermissionChanged(context: context)
    }
}
