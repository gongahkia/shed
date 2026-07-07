# Extensions Design

Status: Phase 35 v1 design baseline.

## Goal

Let extensions contribute non-executable editor metadata that Itsy can register deterministically after install-time integrity and trust checks.

The v1 system covers:

- commands shown in the command palette.
- keybindings mapped to contributed commands.
- themes loaded from extension files.
- snippets surfaced by language.
- language and grammar metadata.
- problem matcher metadata.
- existing task contributions.

## Manifest ABI

Extensions are directories with `extension.json` at the root after installation. Workspace-local development manifests may still be discovered from `.itsy/extensions/*.json`.

Schema v1 is retained for task-only manifests. Schema v2 is the v1 extension ABI for installable extensions:

```json
{
  "schemaVersion": 2,
  "identifier": "dev.example.tools",
  "name": "Example Tools",
  "version": "0.1.0",
  "contributes": {
    "commands": [],
    "keybindings": [],
    "themes": [],
    "snippets": [],
    "languages": [],
    "problemMatchers": [],
    "tasks": []
  }
}
```

`identifier` is globally unique inside the local Itsy install. Contribution IDs are scoped as `extension:<identifier>:<id>` before registration.

Contribution payloads are declarative. v1 does not load extension code, dynamic libraries, scripts, or network resources at activation time.

## Registration

Registration happens after an extension is installed and trusted:

1. Load manifest.
2. Validate schema version and required fields.
3. Prefix contribution IDs with the manifest identifier.
4. Register contributions with each local registry.
5. Persist enabled/disabled state.

Commands are command-palette entries only unless backed by a built-in Itsy action. A command contribution may reference only a supported built-in action or a task contribution from the same extension.

Keybindings target registered command IDs. Invalid targets fail registration for that extension.

Themes, snippets, grammars, and problem matchers resolve paths relative to the extension root. Resolved paths must stay inside the extension root.

## Storage

Installed extensions live under:

```text
~/.config/itsy/extensions/<identifier>/<version>/
```

Mutable extension state lives under:

```text
~/.config/itsy/extension-state/<identifier>/
```

Marketplace/cache metadata lives under:

```text
~/.config/itsy/extension-cache/
```

Project development manifests remain under:

```text
<workspace>/.itsy/extensions/*.json
```

Project manifests are trusted only for the active workspace and never promoted into the global install directory without the install flow.

## Install

Install flow:

1. Fetch or locate archive.
2. Compute SHA-256 before extraction.
3. Verify requested SHA-256 against install request or marketplace metadata.
4. Extract into a temp directory.
5. Reject archives with absolute paths, `..`, symlink escapes, nested app bundles, executable quarantine-bypass helpers, or missing manifest.
6. Validate manifest.
7. Check Vouch trust policy.
8. Move into the installed extension directory atomically.
9. Register contributions.

The installed directory name includes version to make rollback and uninstall deterministic.

## Uninstall

Uninstall disables the extension first, unregisters contributions, then removes the installed version directory.

User state under `extension-state` is retained by default. A later UI can expose "remove state" as a separate destructive action.

## Publish

`scripts/package_extension.sh <extension-dir> [output-dir]` creates a `.itsyext.zip` archive, writes a `.sha256` file, and prints the matching local `allow sha256:<hex> id:<extension-id> version:<version> signer:<signer>` line for review before adding it to a VOUCHED store.

The script rejects missing manifests, symlinks, nested `.app` bundles, and executable files before packaging. Marketplace submission remains a separate distribution step that should publish the archive URL and SHA-256 in an index compatible with `ExtensionMarketplaceCache`.

## Trust

Trust is checked before registration.

Vouch stores are searched in this order:

```text
<repo>/VOUCHED
~/.config/itsy/VOUCHED
<workspace>/.itsy/VOUCHED
```

The first matching deny result blocks install. A matching allow result permits install only when the archive SHA-256 and manifest identifier also match.

Until an external `vouch` CLI file contract is pinned, Itsy-owned VOUCHED lines use:

```text
allow sha256:<hex> id:<extension-id> version:<semver> signer:<label>
deny sha256:<hex> id:<extension-id> reason:<text>
```

Blank lines and `#` comments are ignored. Unknown directives fail closed.

When `vouch` CLI support is added, CLI verification may add allow/deny evidence, but local parser output remains auditable and deterministic.

## ABI Stability

Schema v2 is additive. New contribution kinds require a schema bump only when existing fields change meaning.

Unknown top-level manifest fields are ignored. Unknown fields inside known contribution objects are ignored unless they change security-sensitive behavior.

Known security-sensitive fields are:

- contribution IDs.
- command target/action.
- file paths.
- archive SHA-256.
- manifest identifier/version.
- trust directives.

## Non-Goals

- No JavaScript, WASM, native, or subprocess extension host in v1.
- No runtime network access for installed extensions.
- No automatic marketplace install without SHA-256 verification.
- No mutable files inside installed extension directories.
- No workspace-local manifest auto-install into the global extension directory.
- No compatibility claim with VS Code extension packages.

## References

- VS Code contribution points: https://code.visualstudio.com/api/references/contribution-points
- Tree-sitter code navigation tags: https://tree-sitter.github.io/tree-sitter/4-code-navigation.html
