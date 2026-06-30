import Foundation
import ollyDSL
import ollyKit

extension OllyRuntime {
    public static var defaultDisplayChangeStream: DisplayChangeStreamProvider {
        { DisplayMonitor().changes() }
    }

    func startDisplayObservation() {
        guard displayObservationTask == nil else {
            return
        }
        let task = Task { [weak self, displayChangeStream] in
            for await change in displayChangeStream() {
                guard let self, !Task.isCancelled else {
                    return
                }
                await self.handleDisplayChange(change)
            }
        }
        displayObservationTask = task
        tasks.append(task)
    }

    func handleDisplayChange(_ change: DisplayChange) async {
        await hookDispatcher.displayChange(DisplayChangeHookContext(change: change))
        await initializeDisplays()
        try? await arrangeAllDisplays()
    }
}
