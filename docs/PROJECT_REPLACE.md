# Project Replace

Use `:projectreplace preview /find/replacement/` to create an in-memory literal replacement plan. Preview does not write to disk. The preview lists numbered files and matches, each selected by default. Use `:projectreplace file <id> on|off|toggle` or `:projectreplace match <id> on|off|toggle` to adjust the selection, then run `:projectreplace apply` explicitly.

Apply reads every selected file again and skips it if its content changed after preview. It writes accepted changes through `AtomicFileWriter`; results list changed, skipped, and failed files. `:jobcancel <id>` cancels a preview or apply job between files, while `:projectreplace cancel` discards the in-memory preview.
