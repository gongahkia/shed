# Backups

Shed creates local versioned backups when a file buffer becomes dirty. Each backup is written as a new file and verified before older backups are pruned, so retention cleanup never overwrites the only prior backup.

`backup.enabled` controls creation, `backup.directory` selects the directory, and `backup.retention.count` retains `1..100` backups per source file. Defaults are enabled, `~/.shed/backups`, and `10` copies. Backup write or cleanup failures are recorded in the local diagnostic log; the editor buffer remains dirty and recoverable.
