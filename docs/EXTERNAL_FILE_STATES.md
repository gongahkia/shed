# External File States

`FileBuffer` records a filesystem signature after load, save, and explicit acknowledgement. Polling or watcher events classify the current path as one of:

| State | Meaning | Clean buffer handling | Dirty buffer handling |
| --- | --- | --- | --- |
| `UNCHANGED` | Signature matches the acknowledged file. | No action. | No action. |
| `EXTERNALLY_CHANGED` | Same file identity with different contents or metadata. | Reload. | Prompt to keep, reload, or compare. |
| `DELETED` | An acknowledged regular file is absent. | Retain the buffer. | Retain the dirty buffer and prompt. |
| `REPLACED` | A path changed from absent or a different file identity to a regular file. | Reload. | Prompt to keep, reload, or compare. |
| `UNSUPPORTED` | The path is a symlink, non-regular target, unreadable, or inaccessible. | Retain the buffer. | Retain the dirty buffer and prompt. |

Acknowledging a deletion or unsupported target updates only the external signature; it does not discard editor content. `Reload Theirs` is blocked for those states and retains the dirty buffer. The watcher registers create, modify, and delete events; polling classifies the resulting state.
