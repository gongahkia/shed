# Production UI evidence

These are actual production Swing root paints from isolated fixtures. They exclude native window decorations and do not measure monitor scanout. The gallery renders at 2× logical resolution; journey images use the actual logical root size. Background native-window capture attempts were sometimes stale and are not used as state proof.

The four selected before images use the preserved baseline JAR `c051395ad94d4aa1cbfcc39ddf79b22700091853701e163a9696d5f97fa4d9b1`. The after gallery and journeys use final JAR `bd2243bb1ce36715de9677c461a7b54f95cf96f5cbdea58a867a88684d14e20c`. [File hashes](manifest.json), [gallery metadata](after/gallery.json), [journey assertions/metadata](journeys/journeys.json).

| Matched fixture | Before | After |
| --- | --- | --- |
| Editor/navigation | [Before](before/01-editor.png) | [After](after/01-editor.png) |
| Settings | [Before](before/15-settings.png) | [After](after/15-settings.png) |
| Git unavailable/empty state | [Before](before/03-tool-git.png) | [After](after/03-tool-git.png) |
| Tasks empty state | [Before](before/05-tool-tasks.png) | [After](after/05-tool-tasks.png) |

The complete current gallery contains 38 captures, including every tool at narrow width/150% text, shared control states, detached search, settings, command/keymap/theme/help dialogs, notebook/outline/Git graph and ordinary confirmation/chooser surfaces. The selected before subset is not presented as the complete baseline gallery; all 30 original baseline captures remain outside Maven output under `/tmp/shed-ui-stage4-baseline/gallery-corrected`.

| Production journey/state | Evidence |
| --- | --- |
| Real file/location picker | [Query and selection](journeys/01-file-finder-location.png) |
| Edit/undo/redo/save and dirty close | [Edited/saved document](journeys/02-edited-saved-requested-focus.png), [actual confirmation](journeys/03-dirty-close-confirmation.png) |
| Search and diagnostic navigation | [Search](journeys/04-search-result-navigation.png), [Problems](journeys/05-problem-navigation.png) |
| Appearance and recoverable layout | [Actual zoom setting](journeys/06-settings-scale-150.png), [narrow light view](journeys/07-narrow-light-150.png), [detached search](journeys/08-detached-search-light.png), [restored session/split](journeys/09-restored-session-active-split.png) |
| Normal approval path | [Pending proposal](journeys/10-review-pending-requested-focus.png), [narrow/enlarged decisions](journeys/11-review-narrow-light-150.png), [local draft](journeys/12-review-local-draft.png), [explicitly applied unsaved buffer](journeys/13-review-applied-unsaved.png) |
| Background task feedback | [Running](journeys/14-task-running.png), [failed/retained source](journeys/15-task-failed-retained-source.png), [cancelled](journeys/16-task-cancelled.png) |
| Populated narrow/enlarged task data | [Readable job/status row](journeys/17-task-narrow-light-150-job-status.png), [readable output](journeys/18-task-narrow-light-150-cancelled.png) |

A short dock scrolls its virtual content. The two final task images deliberately show different scroll positions: the job row and output must each be reachable and readable, rather than all content being squeezed into one viewport. Review decisions similarly remain reachable in the scrollable full-width footer. Long data values retain normal table/text scrolling and full metadata where supplied.

The [preserved task-divider failure](failures/task-divider-before.png) belongs to the pre-clamp artifact `4a13e5a79533bc9fb6d2a71d5882e6c4104981ffba6fc72dab45bb831e9d146a`; it is not a current-after screenshot. Its [geometry trace](../../performance/ui-stage4/task-divider-before.log) identified a one-pixel data viewport despite sufficient minimum-size space. Other intermediate failures and their corrected behavior are explained in [UI modernization](../../UI_MODERNIZATION.md).

Focus-labelled filenames record a requested state; the JSON records the actual focus owner. The final native focus integration test passed, while some final gallery Debug form focus attempts were unobserved and explicitly recorded. Journey controls use real production/accessible actions and mapped keys with Robot disabled. OS key injection, IME, external readers and Windows/Linux native interaction remain unverified.
