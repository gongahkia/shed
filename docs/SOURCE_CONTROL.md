# Source Control: Git, Mercurial and Subversion

Open **View → Source Control**, the **Source Control** command-palette action, or `:scm`. The existing resizable, toggleable right sidebar now contains a provider selector. Detection checks the active project and its parent directories for local working-copy markers. If several providers are present, Git takes precedence, then Mercurial, then Subversion. Choose explicitly to override that decision; **Detect** returns to automatic selection. Changing the workspace clears that override. Existing `:git` commands explicitly choose Git; `:scm mercurial gui` and `:scm subversion gui` choose their respective graphical pages. Other existing `:scm` text commands remain available.

This is graphical integration for each provider's own model. Git staging is preserved; Mercurial and Subversion do not acquire a simulated staging area. Git worktrees/stashes/graph retain their existing advanced surfaces. Mercurial named branches/shelves and Subversion branch/tag URLs use their own client semantics.

## Graphical operations

| Operation | Git | Mercurial | Subversion |
| --- | --- | --- | --- |
| Changed-file list, selection, Open | Existing | Added | Added, including properties/tree conflicts |
| Inline patch and central Open Diff | Existing | Added | Added; property-only output is retained |
| History and revision comparison | Existing local commits | Local changesets, up to 100 | Explicit server consent, up to 100 revisions |
| Stage / Unstage | Existing | Not a provider concept | Not a provider concept |
| Add | Existing Git staging | Explicit Add / Forget | Explicit Add |
| Revert / Remove / Rename | Existing controls and file operations | Explicit selected-path actions | Explicit selected-path actions |
| Conflict handling | Existing Git workflow | Show conflicts; mark current file resolved | Show text/property/tree conflicts; accept current working file |
| Commit | Existing staged commit | Saved tracked changes | Saved tracked/scheduled changes; server consent |
| Branch / switch / update | Existing Git workflows | Named branch, update/merge to selected branch head or history changeset | Update HEAD/history revision, Switch URL, create branch/tag by working-copy-to-URL copy |
| Shelving / remote operations | Existing stash/remotes | Shelve / Unshelve / Pull / Push | Provider-appropriate commit/update/copy/switch |
| Refresh / cancellation | Existing Git behavior | Background execution and Cancel | Background execution and Cancel |

All Mercurial/Subversion mutations require explicit confirmation. **Commit commits all saved tracked/scheduled changes**; untracked files are not added automatically. Subversion **Update** from Changes updates to HEAD; **Update to revision…** uses the selected history revision. A Subversion branch/tag requires a destination repository URL and a commit message; it copies the saved working copy to that server location. This creates the repository copy, without automatically switching the working copy. Mercurial **New branch** changes the working-copy branch name, recorded on the next commit; merge/update pick an explicit existing branch head. History can also update to an older changeset.

Selected-file **Revert** discards working-copy changes outside editor Undo. **Remove** can delete from disk. **Mark resolved** records the current working file as resolved; it does not implement a merge editor or prove that conflict content is correct. Review the file first. Directories can have provider-recursive effects; the confirmation identifies the target. Subversion root-property Revert/Resolve uses depth empty. Other root mutations are rejected.

Unsaved buffers, pending saves/reload prompts, approved agent ownership, changed workspace generations and stale confirmations block these mutations. Nothing saves automatically. Context is checked again immediately before client execution, on the EDT without waiting there for the client. Successful mutations use the existing external-change reconciler. Cancellation is best effort: a client may have already changed disk or a remote repository. Refresh afterward; cancellation is not rollback. No retry or rollback command is run automatically.

## Clients and execution

Install/configure `hg` or `svn` on the application PATH. Missing clients produce a visible error; status is not reported as clean. These tools are not bundled or installed automatically. Git continues using its existing implementation.

Commands use direct argument lists through Shed's existing bounded process/job services; no generated shell command string or new process pool is introduced. Jobs have a 60-second deadline and 2 MiB output budget. Status is bounded to 10,000 paths; malformed, unsafe or truncated structured output fails closed. Mercurial uses NUL-separated records/templates and UTF-8/plain/noninteractive/no-pager flags. Subversion uses securely parsed XML, internal diff, noninteractive mode and no authentication-cache writes. Unknown paths are not passed to an external merge/diff tool by the new GUI.

Production clients retain their normal inherited environment/configuration, including repository hooks, enabled extensions and existing credentials. This is ordinary user-authorized VCS execution, **not an operating-system sandbox** or a replacement for trusting the selected local client/repository. Missing credentials, unavailable extensions (including shelves on some Mercurial versions), server permission failures and client/version incompatibilities remain visible errors. No password editor or new credential store is added. URLs entered in this GUI reject embedded user information, queries and fragments; displayed URI user information is redacted in errors/location labels. Arbitrary client output can still contain repository-sensitive information.

Mercurial paths are explicit literal `path:` operands. Subversion source operands use an empty peg revision so filenames containing `@` remain filenames. Targets reject traversal, repository metadata, linked paths, nested working copies, leading-option paths and unsupported filename forms. Destination renames never overwrite an existing file. This conservative first version can reject legal but ambiguous filenames (including backslashes or drive-like names); use the native client explicitly for those cases.

Subversion status/info/diff are local. History/revision comparisons and commit/update/switch/branch-tag contact the repository only after confirmation. Mercurial history/diff/branch inspection is local; Pull/Push explicitly contact its configured remote. Pull does not implicitly update. Timed refresh runs only while the new provider surface is showing, ready and idle, and pauses during its dialogs. A failure stops automatic retries until explicit Refresh. Hiding preserves state; application disposal stops timers and cancels owned jobs.

## Scope and qualification

The sidebar, central comparisons and source ownership share Shed's existing Swing workbench. Default theme/font, keymaps, terminal, document model and saved layout identifiers remain unchanged. The internal `GIT` dock identifier is retained for session compatibility while the visible title is **Source Control**.

[Qualification evidence](performance/source-control/INDEX.md) separates pure parser/argv tests, live Swing/controller checks with controlled client doubles, and existing real disposable Git checks. **Real Mercurial/Subversion execution is not yet qualified on this host because neither client is installed.** Network operations and Windows/Linux GUI acceptance remain open. No unqualified native-shell harness is used. Safe mocks prove GUI coordination and command construction; they do not prove that an installed client/server will accept every operation or that every supported client version produces compatible output.

Try `make run <working-copy-folder>`, then **View → Source Control**. `make demo-chat` now also exercises both graphical providers using fixed in-process client responses and disposable data. It continues to use the existing fixed Java chat client, rather than a real coding model.

For an opt-in before/after performance check, use `make check-scm-performance SCM_BASELINE_JAR=/path/to/preserved-before.jar`. See the [matched test definitions and results](performance/source-control-e2e/INDEX.md). Normal startup and unit tests do not enable it.

The [idle-resource follow-up](performance/idle-resource-optimization/INDEX.md) documents worker retirement and batched status/history installation. Add `SCM_PERF_IDLE=1` to the performance target to include the longer idle/resumption checks.
