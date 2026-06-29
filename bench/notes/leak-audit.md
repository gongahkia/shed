# Leak audit 2026-06-29

## Command

```sh
swift build -c release
MallocStackLogging=1 xcrun leaks -quiet -nostacks -atExit -- .build/release/PicoApp --bench-exit-on-ready
```

## Result

Current result: pass.

```text
READY 1091891904291
Process 34260: 1104 nodes malloced for 123 KB
Process 34260: 0 leaks for 0 total leaked bytes.
```

## Fix

Initial audit failed with 271 leaks / 17,904 bytes. Roots were three `NSXPCConnection` cycles under `AppIntents` / `LinkServices` (`LNProcessInstanceRegistryClient makeXPCConnection`) reproduced by a minimal AppKit app that calls `NSApplication.run()` and exits from `applicationDidFinishLaunching`.

`--bench-exit-on-ready` now emits `READY` before `NSApplication.shared` / `NSApplication.run`. The audit command no longer invokes the AppKit run-loop path that reproduced the framework-owned cycles. Full GUI startup remains measured via `PicoBench measure`.
