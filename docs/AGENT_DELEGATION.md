# Explicit agent delegation and handback

The normal in-app entry is now [ongoing CLI Chat](AGENT_CLI_CHAT.md), opened by **View → Agent Chat…** or `:agent chat`. This report preserves the original one-shot delegation implementation and its evidence; use `:agent task` for that advanced launcher. `make demo-delegation` now demonstrates chat and follow-ups (`make demo-chat` is an alias); the original controlled one-shot probe remains available without `--chat`.

The four requested workflow improvements are implemented over Shed's existing authenticated workspace bridge and actual editor. There is no additional server, model-owned daemon, document model, Undo engine or persistent agent worker.

## Try it

For the original one-shot launcher, open a project and run `:agent task`. Describe the task, choose the already-installed CLI and inspect its direct argument array. The default Codex command is `codex exec --sandbox workspace-write -`; Claude uses `claude -p`. Other CLIs can be used when they accept a task on standard input. Executables are resolved to a concrete path before consent; shell command strings are not evaluated. No login or installation is performed.

The second dialog names the agent, task, project and exact command. **Allow Control** is required before a managed CLI starts. Cancel is the default. Save dirty buffers and finish pending saves/reloads first. Approval starts a fresh change baseline; the client is admitted after the baseline and existing control attachment are ready.

A compact control strip leaves the source visible. Human typing, pointer navigation and menus are suspended for that window. **Take Back Control** revokes the Shed lease and cancels a managed task. Ctrl+Shift+Escape is also handled when delivered to Shed; operating-system shortcut interception is unqualified. Owned approval dialogs remain usable. Other applications and other Shed windows are not locked.

On completion, failure, cancellation, expiry, project changes or attachment closure, normal input is restored. Change-following pauses, and the existing Live changes summary/diffs open. Managed process output is a bounded, plainly labeled agent report; neither exit zero nor the agent's prose proves tests passed. A nonzero managed exit is a failed session. A new delegation starts a new net-change baseline.

For an existing external agent:

```sh
printf '%s\n' 'Describe the task here.' | ./scripts/shed delegate . --agent Codex --task-file -
```

This launches a normal project window, obtains the existing authenticated attachment, requests consent, and waits off the EDT. It returns JSON containing `environment.SHED_CONTROL`, `environment.SHED_DELEGATION`, the delegation ID and generic instructions. The external agent supplies that environment to subsequent CLI calls. Use `--connect <descriptor>` instead of a project argument to target a particular existing control-enabled window. No running window is chosen implicitly. Task files/stdin are bounded to 4,096 bytes in the external helper. A failed or abandoned helper does not authorize editor access; pending approval and preparation expire.

Managed tasks receive the same environment automatically. Their instructions include the current Java executable and absolute classpath as a CLI argument-array prefix, avoiding dependence on a missing `SHED_BIN`, PATH entry or stale JAVA_HOME. Reads use `ctl read <path> --agent <name>` to open the actual source and show its reading animation. Ordinary filesystem writes retain existing creation/modification/move/deletion animations. Ordinary filesystem reads cannot be inferred by a file watcher: visible reading still requires client cooperation.

Run the disposable, paced live demonstration with:

```sh
make demo-delegation
```

It uses a clearly labeled fixed Java client, not a cloud model. The existing `make demo` animation tour is unchanged.

## Java setup

Shed requires Java 21. The launcher first honors an explicit `JAVA`, then a valid `JAVA_HOME` (including a Homebrew prefix or macOS JDK bundle). An existing but incompatible home remains an error. A missing `JAVA_HOME` now produces a warning and continues discovery instead of blocking every command.

Automatic discovery prefers the app-owned `~/.local/share/shed-tools/jdk-21` before PATH and macOS/Homebrew discovery, avoiding the macOS Java-install stub when a working Shed runtime is already available. No JDK is downloaded automatically and shell startup files are not changed.

On this development Mac, the already available JDK 21 was copied into that stable app-owned directory, so ordinary `make demo-delegation` works despite the stale `/opt/homebrew/opt/openjdk@21` export. The old temporary JDK was preserved. You can explicitly choose a different valid runtime with `JAVA` or `JAVA_HOME`.

## Freshness acknowledgement

```sh
shed ctl sync src/App.java --sha256 RAW_DISK_SHA256 --timeout-ms 2500
```

The file must be open. `editor/source/sync` checks project authorization, pinned path acquisition, exact raw-byte SHA-256, decoded editor text, encoding/BOM/newline metadata, clean dirty state, and a final disk hash. Documents without a newline retain FileBuffer's preferred newline label without an artificial mismatch. Deadline: 50–3,000 ms. It polls existing immutable editor snapshots every 40 ms on the request worker, and never forces a reload, clears Undo, writes source or waits on the EDT.

Success contains `kind: "sync"`, `path`, lowercase `sha256`, `documentId`, `version` and `synced: true`, inside the existing versioned control envelope. Changed disk bytes return `STALE_VERSION`, a dirty editor returns `CONFLICT`, an unopened file returns `NO_ACTIVE_EDITOR`, and a deadline returns `TIMEOUT`. Cancellation/authorization failure remains a rejection. This is a point-in-time acknowledgement, not a filesystem lock or a claim that all intermediate writes were painted.

## Control contract

The existing mutual authentication and explicit descriptor selection remain authoritative. Agent names and summaries are client claims: authentication proves possession of the selected attachment credential, not a vendor/executable attestation. Delegation IDs add session context; they do not replace credentials. `editor/delegation` accepts exactly:

```json
{"schemaVersion":1,"instanceId":"...","windowId":"...","projectId":"...","action":"request","requestId":"client-generated-id","agent":"Agent","task":"Bounded task description"}
```

Actions are `request`, `status`, and `finish`. Status/finish use the same ID; finish's task field is the optional agent-reported summary. Replies contain an `observation` with exactly `kind`, `requestId`, `agent`, `task`, `state`, `summary`. States: `awaiting-approval`, `preparing`, `active`, `completed`, `cancelled`, `rejected`, `failed`, `expired`. IDs are bounded to 64 characters, names to 40 and task/summary to 4,096 UTF-16 units internally. Duplicate identical requests do not open duplicate approval dialogs; another live session is rejected.

CLI forms:

```sh
shed ctl session-request --request-id task-1 --agent 'Any CLI' --task-file /path/to/task.txt
shed ctl session-status --request-id task-1
shed ctl session-finish --request-id task-1 --task-file /path/to/summary.txt
```

Clients use `SHED_DELEGATION`, or `--delegation <id>`, for all other editor requests. The server strips this permission envelope before existing strict request-schema validation. Once this window enters delegation mode, missing, wrong, revoked and expired leases cannot observe editor context or invoke control. Pending navigation retains a live authorization predicate through application; final observation checks reject responses captured across revocation. Potentially applied presentation operations retain `OUTCOME_UNKNOWN` semantics. Status-only application responses remain available. Existing explicitly enabled legacy APIs in windows that have not entered delegation mode continue to work.

Approval expires after 60 seconds, preparation after 45 seconds, active control after at most eight minutes, using monotonic elapsed time. Existing attachment expiry can end control earlier. One session is active per window; a bounded retired-ID set prevents immediate stale reuse. No permanent polling worker is added. The 100 ms UI lease timer runs only during a request/session and stops at handback.

The editor composition root owns delegation and workspace controllers separately. The temporary input shield restores original glass-pane, menu and editability state and removes its keyboard/property listeners. Source view rebinding cannot re-enable human editing during ownership, and disposal remains read-only through handback. Existing source, selection, revisions and Undo are not modified by the shield. Agent navigation and watched reloads continue using existing document/view ownership. Session clients cannot restart/stop the watcher to discard its baseline while ownership is active.

## Process and trust boundaries

Managed CLIs run by direct argv on the existing AsyncJobService, with bounded stdin, an 8,192-character output tail and a roughly 3,800-character handback excerpt. No arbitrary shell evaluation or provider permission-bypass flags are added. Production CLIs retain the user's normal environment/configuration/login, with the selected Shed attachment, lease and current Java runtime supplied explicitly. This is intentional user-authorized agent execution, not an isolated benchmark environment.

Cancellation, deadlines and disposal terminate the owned process and observed descendants identified by PID/start time. Cleanup waits for child exit and retires pipe readers/writers off the EDT. Process/output/tracked-child limits fail rather than allocating unbounded state. Detached daemons escaping normal ancestry are not an OS confinement guarantee; users must not select a service-creating command and expect Shed to sandbox it. A runner crash/SIGKILL and hostile process detachment remain outside the qualified fixture scope.

Taking back control cannot reverse completed filesystem writes or revoke a separately launched agent's filesystem permissions. External agents are instructed to stop on revocation, and bridge requests are denied. This is window/input coordination plus bridge authorization, not an operating-system project lock or a security boundary against a fully trusted same-user process. Existing per-command terminal approval is unchanged; delegation adds no implicit terminal grant.

## Qualification

See [raw evidence, commands and captures](performance/agent-delegation/INDEX.md).

- Safe Maven selection: 538 passed, seven existing skips (six opt-in measurements and the existing data-loss fixture skip). Full native/GUI suite and the retired ProjectFileOpenPrerequisiteTest characterization are excluded under the established admission policy.
- Python tooling: 107 passed, including synthetic unsafe-output/symlink/missing-compiler rejection. No private shell history was read or modified.
- Real macOS Swing window plus separate fixed Java client: explicit approval, real source opening/reading, exact write/sync, create/move/delete, session diffs, human handback, pending-save rejection, external CLI bootstrap/consent/disconnect, cancellation, timeout, failure exit and inherited-pipe/background-child cleanup passed. Direct IO-worker accounting returned to zero in the final run.
- Final paced Make demo, post-handback 40-character completed-paint probe: first 1.82 ms, p95 0.54 ms on the small fixture. Earlier successful runs measured p95 1.39 and 1.76 ms. These samples measure the EDT source mutation plus `paintImmediately`; they exclude waiting to enter the EDT and OS keyboard delivery. They are bounded current-host component checks, not an input-to-screen qualification or matched startup/whole-IDE memory benchmarks; the earlier 1.76 ms run overlapped packaged-media smoke. Full native-memory and multilingual performance qualifications remain open.
- The final IO-retirement counter covers virtual reader/writer threads directly; earlier platform-thread-only samples are corrected in the evidence index. Eleven overlapping safe tests passed after that diagnostic-only change.
- Twenty-four in-memory ownership cycles restore the original editable-listener count, source, revision, dirty state, selection and Undo state. This is not whole-process memory equivalence.
- Packaged Mermaid/math/local-image smoke passed. No README, theme, editor-font default, saved preference migration, document-model replacement or new dependency was introduced.

Initial failures are preserved: one incorrect test assumed the final active file would still be Demo.txt after following other changes; a real view-rebinding editability gap was corrected; child cleanup initially returned before process exit; and the unchanged architecture-boundary test rejected peer-controller ownership, now moved into Texteditor. No assertion was weakened or unsafe native path admitted.

The transport, controlled Java process and macOS component workflow are qualified here. Existing-window `delegate --connect` is exercised by a separate CLI process. The new-window `delegate <project>` route reuses the existing launch/readiness implementation, but its whole cross-process approval sequence is not independently qualified here. The actual configured cloud CLIs were not invoked in this change; instruction compliance, provider-version compatibility, tool approvals and login are still provider-specific acceptance items. The Codex and Claude argument forms follow their official [noninteractive](https://developers.openai.com/codex/noninteractive) and [CLI](https://code.claude.com/docs/en/cli-reference) documentation. OpenCode or another harness can use explicit external attachment; the managed launcher currently expects stdin-task CLIs.

Windows/Linux desktops, genuinely cold startup, adversarial same-user filesystem isolation, complete process-detachment confinement, native-memory attribution, physical-font glyph coverage, Unicode first-edit stalls and stock HTML-preview latency remain unqualified or open as previously documented. B0.1 native-shell benchmarks remain NO-GO. No such harness, real language server, unrestricted desktop automation or real model was run.

Starting HEAD: `6b472202e79b99e4f690a51b7c2ed3ce4187a197`, clean tree. Requested local commits were created; no pushes. README before/after SHA-256: `48bf87f09ffa570b7fc65d305a15c3228bbc67e906cc992e4fe7108893e7501e`. Prior work and evidence are preserved.
