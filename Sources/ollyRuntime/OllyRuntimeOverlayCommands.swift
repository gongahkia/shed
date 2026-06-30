import ollyIPC

extension OllyRuntime {
    func showOverlay(_ command: IPCShowOverlayCommand) async {
        await overlayRequests.publish(command.kind)
    }
}
