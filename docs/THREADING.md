# Threading policy

Shed uses Swing's event dispatch thread (EDT) for the UI and `AsyncJobService` workers for blocking or long-running work. This policy applies on macOS, Windows, and Linux.

## EDT ownership

- Construct, access, or mutate Swing and AWT UI objects only on the EDT. This includes models attached to components.
- Run command handlers, action listeners, Swing `Timer` callbacks, rendering, status updates, dialogs, and editor-state changes caused by UI interaction on the EDT.
- Do not block the EDT with file, network, or process I/O; process waits; long computation; locks with unbounded waits; or `Future.get()`.
- Use `SwingUtilities.invokeLater` to transfer externally initiated UI work to the EDT. Use `invokeAndWait` only where a synchronous boundary is required and never from the EDT.

## Background work

- Use `AsyncJobService` for shell commands, process output, file or network I/O, and expensive computation.
- A job task must not access Swing/AWT components or their attached models. Capture immutable input on the EDT before submitting the task.
- `AsyncJobService.JobCompletion` always runs on the EDT. It may update the UI directly and must stay short; do not add another `invokeLater` wrapper.
- Completion handlers receive only completed-job data. They must verify cancellation and stale editor state before applying results.
- Unexpected worker and completion failures are reported through `ApplicationErrorReporter`; failed jobs are also appended to the local command log. Routine completions and automatic cancellations stay out of the log. Cancellation uses the job token and interrupt path.

## Settings and picker boundaries

`SettingsApplication.search/update` are synchronous caller-thread operations. Production settings controls and persistent command updates invoke them on the EDT. A successful update invokes the injected runtime-apply callback inline, after persistence; validation or write failure does not invoke it. This retains the existing small synchronous configuration write rather than silently introducing asynchronous callback delivery. Settings descriptors are immutable snapshots; this boundary has no subscriptions.

`PaletteSession` captures immutable candidates before worker queries. Queries do not open files, activate buffers, or access Swing. Acceptance and close are owned by the calling UI thread; acceptance invokes its application callback inline. Closing the application session revokes acceptance and releases its callback. An in-flight query may finish after close, so query cancellation alone is not an admission check.

`PaletteFilterSession` is the per-dialog Swing owner of debounce, worker cancellation, request generations, queued Enter, and EDT result delivery. Its close operation is idempotent, stops the timer, cancels work, and removes only its own document/action/mouse listeners. Stale or closed completions cannot render or accept a result. `PaletteController` closes it and disposes the dialog in `finally`, including failures during preview or dialog preparation. No callback has moved to a different thread through these interfaces.

## Verification

`AsyncJobServiceTest.returnsAsyncUiUpdateToEventDispatchThread` verifies that worker work stays off the EDT and that a representative Swing status update executes on the EDT.

Oracle documents that Swing is not thread-safe, that component access belongs on the EDT, and that long-running work must not block it: [Swing threading policy](https://docs.oracle.com/javase/8/docs/api/javax/swing/package-summary.html) and [Concurrency in Swing](https://docs.oracle.com/javase/tutorial/uiswing/concurrency/index.html).
