# Local diagnostics

Shed records unexpected application, UI, and asynchronous-job failures in `~/.shed/shed-diagnostics.jsonl`. The file is local-only: Shed does not send diagnostic records, error details, or telemetry to any service.

Each line is a JSON object with a UTC timestamp, `ERROR` severity, subsystem, context, cause type/message/stack trace, and a repository-relative remediation reference. User notifications remain sanitized and point only to the local file.

The log retains the newest records within 1 MiB. Individual cause messages and stack traces are truncated before writing, so the file remains bounded. Delete `~/.shed/shed-diagnostics.jsonl` to clear it while Shed is not running.

Current remediation references include [threading policy](THREADING.md) for UI and async failures and [controller architecture](ARCHITECTURE.md) for application-composition failures.
