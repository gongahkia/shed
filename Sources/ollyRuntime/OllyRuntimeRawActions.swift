import ollyCore
import ollyDSL
import ollyIPC
import ollyKit
import ollyLayouts

extension OllyRuntime {
    func runRawAction(_ command: IPCRunRawActionCommand) async {
        let config = await configStore.current()
        await runRawAction(label: command.label, displayID: nil, event: "ipc", config: config)
    }

    func runRawAction(
        label: String,
        displayID: DisplayID?,
        event: String,
        config: Config
    ) async {
        guard let action = config.shellAction(label: label) else {
            await publishRuntimeEvent(.rawAction(IPCRawActionEvent(
                label: label,
                status: .denied,
                stderrHead: "unknown raw action"
            )))
            return
        }
        await runRawAction(action, displayID: displayID, event: event, config: config)
    }

    func runRawAction(
        _ action: ShellAction,
        displayID: DisplayID?,
        event: String,
        config: Config
    ) async {
        let rawEvent = await rawActionExecutor.run(
            action,
            policy: config.permissions.shellExec,
            environment: await rawActionEnvironment(displayID: displayID, event: event)
        )
        await publishRuntimeEvent(.rawAction(rawEvent))
    }

    private func rawActionEnvironment(
        displayID requestedDisplayID: DisplayID?,
        event: String
    ) async -> [String: String] {
        var environment = [
            "OLLY_TAG": "0",
            "OLLY_DISPLAY_ID": "",
            "OLLY_WINDOW_ID": "",
            "OLLY_WINDOW_BUNDLE_ID": "",
            "OLLY_WINDOW_TITLE": "",
            "OLLY_ENGINE_ID": FloatingLayoutEngine.engineID.rawValue,
            "OLLY_EVENT": event,
            "OLLY_SOCKET_PATH": socketPath.rawValue,
            "OLLY_VERSION": String(OllyIPC.protocolVersion)
        ]
        var displayID = requestedDisplayID
        if let focusedWindowID {
            environment["OLLY_WINDOW_ID"] = String(focusedWindowID)
            if let window = await windowStore.state(for: focusedWindowID) {
                environment["OLLY_WINDOW_BUNDLE_ID"] = window.bundleID ?? ""
                environment["OLLY_WINDOW_TITLE"] = window.title ?? ""
                displayID = displayID ?? window.displayID
            }
        }
        displayID = displayID ?? selectedDisplay(nil)?.id
        guard let displayID else {
            return environment
        }
        environment["OLLY_DISPLAY_ID"] = String(displayID)
        let tagState = await tagStore.state(for: displayID)
        if let tag = tagState.activeTags.tags.first {
            environment["OLLY_TAG"] = String(tag.index)
        }
        if let engineID = LayoutEnginePolicy.resolvedEngineID(
            activeTags: tagState.activeTags,
            tagToEngine: tagState.tagToEngine
        ) {
            environment["OLLY_ENGINE_ID"] = engineID.rawValue
        }
        return environment
    }
}
