import ollyCore
import ollyDSL
import ollyKit
import ollyLayouts

extension OllyRuntime {
    func loadConfig(useDefaultWhenMissing: Bool) async throws {
        do {
            let loaded = try configLoader.load()
            await applyLoadedConfig(loaded.config)
        } catch ConfigLoaderError.missingSource where useDefaultWhenMissing {
            await applyLoadedConfig(Config())
        }
    }

    func reloadConfig() async throws {
        let previousConfig = await configStore.current()
        do {
            try await loadConfig(useDefaultWhenMissing: true)
            let currentConfig = await configStore.current()
            previousConfig.hooks.runConfigReload(context: ConfigReloadHookContext(
                previous: previousConfig,
                current: currentConfig,
                sourceURL: configLoader.sourceURL
            ))
            await initializeDisplays()
            try await reapplyRulesToStoredWindows()
            try await arrangeAllDisplays()
        } catch {
            lastError = String(describing: error)
            throw error
        }
    }

    func setActiveTags(_ activeTags: TagSet, on displayID: DisplayID) async {
        let previous = await tagStore.activeTags(on: displayID)
        await tagStore.setActiveTags(activeTags, on: displayID)
        guard previous != activeTags else {
            return
        }
        await hookDispatcher.tagSwitch(TagSwitchHookContext(
            displayID: displayID,
            previousTags: previous,
            activeTags: activeTags
        ))
    }

    func publishEngineChangeHook(
        displayID: DisplayID,
        tag: Tag,
        previousEngineID: LayoutEngineID?,
        currentEngineID: LayoutEngineID
    ) async {
        guard previousEngineID != currentEngineID else {
            return
        }
        await hookDispatcher.engineChange(EngineChangeHookContext(
            displayID: displayID,
            tag: tag,
            previousEngineID: previousEngineID,
            currentEngineID: currentEngineID
        ))
    }

    private func applyLoadedConfig(_ config: Config) async {
        await configStore.replace(with: config)
        nativeSpaceDriftPolicy = config.nativeSpace.driftPolicy
        focusPolicy = config.focusPolicy
        try? await scratchpads.upsert(config.scratchpads.entries)
        await focusRateLimiter.update(settings: config.focusPolicy.rateLimitSettings)
        await hookDispatcher.update(config.hooks)
    }
}
