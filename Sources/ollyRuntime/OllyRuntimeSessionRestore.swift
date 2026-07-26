import ollyDSL

extension OllyRuntime {
    func restoreWindowsOnLaunchIfEnabled() async {
        guard await configStore.current().session.restoreOnLaunch else {
            return
        }
        _ = await restoreJournaledWindows()
    }
}
