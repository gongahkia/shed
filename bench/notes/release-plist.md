# Release Info.plist 2026-06-29

## Bundle metadata

- Bundle id: `dev.pico.editor`
- Version: `0.1.0`
- Build: `1`
- Category: `public.app-category.developer-tools`
- High resolution: `NSHighResolutionCapable = true`
- Principal class: `NSApplication`
- Validation: `plutil -lint Pico.app/Contents/Info.plist`

## Apple events

`NSAppleEventsUsageDescription` is intentionally absent.

Reason: Pico does not send Apple events. The repo's use of `osascript` is limited to benchmark/QA driver scripts outside the shipped app. Apple documents `NSAppleEventsUsageDescription` as the message shown when an app requests the ability to send Apple events, so adding a user-facing purpose string would be misleading for this build.

Validation:
- `rg -n "AppleEvent|NSAppleEvent|NSAppleScript|ScriptingBridge|AEDesc|AE[A-Z]|osascript" Sources/PicoApp Sources/PicoEditor Sources/PicoRender Sources/PicoSyntax Sources/PicoKeymap Package.swift -S` returned no shipped app source matches.
- `plutil -extract NSAppleEventsUsageDescription raw Pico.app/Contents/Info.plist` returned no value.

## Signing

`Pico.app` is not distribution signed by id:300. Developer ID signing is tracked by id:301.

References:
- https://developer.apple.com/documentation/bundleresources/information-property-list/nsappleeventsusagedescription
- https://developer.apple.com/documentation/bundleresources/information-property-list/lsapplicationcategorytype
- https://developer.apple.com/documentation/bundleresources/information-property-list/nshighresolutioncapable
