import ollyDSL
import ollyIPC
import ollyKit

extension OllyRuntime {
    public func cooperativeAppsInfo() async -> IPCCooperativeAppsInfo {
        let config = await configStore.current()
        let windows = await windowStore.allWindows()
        return IPCCooperativeAppsInfo(apps: config.cooperativeApps.resolvedApps.map { app in
            IPCCooperativeAppInfo(
                bundleID: app.bundleID,
                behavior: app.behavior.rawValue,
                detectedWindowCount: windows.filter { $0.bundleID == app.bundleID }.count
            )
        })
    }

    func cooperativeSafeZoneReserves(config: Config) async -> [SafeZoneReserve] {
        await windowStore.allWindows().compactMap { window in
            guard let displayID = window.displayID,
                  config.cooperativeApps.behavior(for: window.bundleID)?.reservesSpace == true else {
                return nil
            }
            return SafeZoneReserve(displayID: displayID, kind: .cooperativeApp, rect: window.frame)
        }
    }
}

private extension CooperativeBehavior {
    var reservesSpace: Bool {
        self == .floatAndReserveSpace || self == .dockAware
    }
}
