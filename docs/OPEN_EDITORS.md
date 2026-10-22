# Open editors

The document strip above the editor shows all open buffers, including unnamed documents and tool buffers. A dot marks unsaved changes. Duplicate filenames include their parent paths; tooltips and accessible names expose the full path and unsaved state. All opens are retained until explicitly closed. There is no disposable preview/pinning mode.

Click a document to switch the active pane. View → Focus Open Editors focuses the strip; Left/Right switch documents, Enter returns to the source, and Escape restores source focus. The strip scrolls horizontally when needed and follows the active document when changing splits. Each pane keeps its document's caret, selection and scroll position.

Close or Delete closes the selected document. Unsaved changes require confirmation; cancel preserves the document. Save through the usual editor action before closing if the changes should be kept. Closing a document updates every split displaying it. Closing the final document opens an empty buffer and keeps the application running.

View → Toggle Open Editors and the typed `ui.open_editors.visible` setting control visibility. Focus modes hide it along with other editor chrome. It uses existing theme colors, UI fonts and zoom; selection, dirty and filename updates only change the list model when their displayed state changes.
