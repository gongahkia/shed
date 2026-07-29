# External Conflict Resolution

When a dirty buffer is externally changed, deleted, replaced, or becomes unsupported, Shed presents four explicit actions:

| Action | Outcome |
| --- | --- |
| Keep Mine | Retains the dirty in-memory buffer and acknowledges the observed external state. |
| Reload Theirs | Replaces the buffer with the regular on-disk version. Deleted and unsupported states block this action and retain the dirty buffer. |
| View Both | Opens a scratch comparison. Deleted and unsupported states show the retained buffer with the external state. |
| Save Mine As | Prompts for a target path, confirms replacement of an existing target, writes the dirty buffer to that target, and retargets the buffer only after the write succeeds. |

Cancelling Save As or a target-replacement confirmation keeps the buffer dirty. A failed Save As keeps both the source binding and dirty content unchanged.
