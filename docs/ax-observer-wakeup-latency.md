# AX Observer Wakeup Path

## Finding

`AXObserver` does not expose a public Mach receive right. The public macOS SDK surface provides:

- `AXObserverCreate`
- `AXObserverCreateWithInfoCallback`
- `AXObserverAddNotification`
- `AXObserverRemoveNotification`
- `AXObserverGetRunLoopSource`

`DISPATCH_SOURCE_TYPE_MACH_RECV` requires a `mach_port_t`, and the public AX observer API only returns a `CFRunLoopSourceRef`. There is no supported way to attach an AX observer directly to a dispatch Mach receive source.

## Measurement Result

Latency delta: not measurable on public API.

The comparison cannot be made against the same AX notification source because only the CFRunLoop source is public. A synthetic Mach-port benchmark would measure libdispatch delivery, not AX observer wakeup latency, so it would not answer the AX question.

## Decision

Keep `AXObserverBridge` on `CFRunLoopAddSource` / `AXObserverGetRunLoopSource`. Revisit only if Apple exposes an AX observer Mach receive port or a dispatch-native observer API.

## Verification

Checked against the local macOS 26.5 SDK header:

`/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX26.5.sdk/System/Library/Frameworks/ApplicationServices.framework/Frameworks/HIServices.framework/Headers/AXUIElement.h`
