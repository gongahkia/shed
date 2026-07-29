# Project Replace

Project replacement is disabled by default. Run `:projectreplace enable`, then use `:projectreplace preview /find/replacement/` to create an in-memory literal replacement plan. Preview does not write to disk. The preview lists numbered files and matches, each selected by default. Use `:projectreplace file <id> on|off|toggle` or `:projectreplace match <id> on|off|toggle` to adjust the selection, then run `:projectreplace apply confirm` explicitly.

Apply reads every selected file again and skips it if its content changed after preview. With the default policy, it retains each original UTF-8 file in `~/.shed/project-replace-backups` before writing through `AtomicFileWriter`; results list changed, skipped, and failed files. `:jobcancel <id>` cancels a preview or apply job between files, while `:projectreplace cancel` discards the in-memory preview.

Use `:projectreplace settings` to view controls. `:projectreplace preview-required on|off`, `:projectreplace confirm on|off`, `:projectreplace backup on|off`, and `:projectreplace scope workspace|current-file` persist the matching TOML safety settings. Disabling preview deliberately enables `:projectreplace replace /find/replacement/`; default confirmation still requires its `confirm` suffix. The non-preview path refuses a truncated plan.
