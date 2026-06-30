import CoreGraphics
import ollyDSL
import ollyKit

public extension OllyRuntime {
    func focusRing() async -> FocusRing {
        await configStore.current().focusRing
    }

    func frameForWindow(_ windowID: WindowID) async -> CGRect? {
        if let target = windowTargets.target(for: windowID),
           let frame = AXFrameReader().frame(for: target.axElement) {
            return frame
        }
        return await windowStore.state(for: windowID)?.frame
    }
}
