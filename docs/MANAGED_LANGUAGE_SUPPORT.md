# Managed Language Support Trust Model

This model governs Shed-managed language runtimes and language servers. Existing `lsp.<ext>.command` and `lsp.<ext>.args` launches remain user-managed; the Language Services panel changes only the extensions selected during an explicitly approved managed install.

## Ownership Boundary

| Tool kind | Owner | Network and cache behaviour |
| :--- | :--- | :--- |
| User-managed | User | Shed launches only the configured/local executable. It performs no managed download, update, cache write, integrity claim, or revocation action. |
| Shed-managed | Shed | The exact cataloged artifact is eligible only after all policy checks below pass. |

Shed-managed support is not automatic. Startup, file open, LSP detection, and background refresh must not issue a network request. A user must invoke an install or update action and explicitly approve that action in the Language Services panel. The panel creates a one-use approval capability bound to the selected catalog entry; an install call without that fresh capability is rejected before it opens a connection, launches npm, or writes a cache file.

## Catalog Provenance and Artifact Identity

The managed catalog is bundled with a Shed release, reviewed with that release, and identifies every artifact by an immutable `toolId@version` coordinate. A catalog entry contains:

- HTTPS source URI;
- SHA-256 of the downloaded bytes;
- verification provenance and, where published, signing metadata;
- exact supported-platform set; and
- the coordinate for revocation matching.

An artifact not present at that exact coordinate is rejected. An entry with an HTTP/non-host source, malformed SHA-256, missing verification provenance, or no supported platform is rejected before a download can start. The installer validates downloaded bytes against the catalog digest before activation and preserves the catalog verification identity in its receipt. Metadata is never trusted merely because a URL was supplied by a user or server.

`ManagedLanguageSupportTrust` is the no-I/O policy gate. Its `Assessment.permitsManagedNetwork()` and `permitsManagedCacheWrite()` return true only for an approved, cataloged, integrity-pinned, platform-supported, non-revoked Shed-managed artifact. The class intentionally contains no downloader, process launcher, or background work.

## Platform and Cache Ownership

Catalog entries enumerate supported platforms; an absent platform is rejected. Managed files belong only under `~/.shed/managed-languages/<toolId>/<version>/`. Coordinate validation rejects separators and traversal segments, and cache resolution verifies that the final path remains below that root.

Shed may create, replace, quarantine, or remove only that managed-language cache. It must not modify a user-managed executable, a user-managed package-manager directory, or a workspace. A failed validation leaves the prior known-good managed artifact untouched.

## Revocation

A release may list an exact `toolId@version` coordinate as revoked. Revocation rejects both installation and activation, even if that artifact remains in the cache. Shed must mark cached revoked content unavailable and present remediation; it must not fetch a replacement in the background or delete a user-managed tool. Removing quarantined managed cache content requires an explicit user action.

Revocation data changes arrive only with an explicit Shed release/update flow. Shed does not poll a catalog endpoint.

## Current Scope

The Language Services panel is available from Settings or `:lsp manage`. Every listed install/update is a separate GUI approval; selecting the panel, opening a file, or running `:lsp manage install <ext>` only opens the local panel and does not start an install.

Eclipse JDT LS `java.eclipse-jdtls@1.60.0` is available for macOS, Windows, and Linux from a bundled, SHA-256-pinned Eclipse archive. Eclipse publishes that checksum but not a detached signature for this archive; the approval dialog states this before download. After approval Shed downloads without redirects, verifies the exact hash, and safely extracts only regular files/directories under `~/.shed/managed-languages/`.

Pyright (`pyright@1.1.411`), TypeScript/JavaScript (`typescript-language-server@5.3.0` with `typescript@6.0.3`), JSON/HTML/CSS (`@zed-industries/vscode-langservers-extracted@4.10.8`), and Markdown (`remark-language-server@3.0.0`) are available through an explicitly approved npm action. HTML routes to `vscode-html-language-server`; CSS, SCSS, and Less route to `vscode-css-language-server`. Shed writes a minimal private `package.json` under its cache and runs `npm install` there only after approval. It requests the exact listed top-level package versions, keeps npm's generated package lockfile (which records registry-provided package-integrity values for the resolved dependency tree), and disables lifecycle scripts, audit, funding, and update notifications. This route does **not** claim an independently published SHA-256 for every npm dependency; that distinction is disclosed in the review dialog. It never installs globally or invokes Homebrew, pip, cargo, rustup, or Go tooling.

On a successful managed install, Shed saves the exact cached launcher and LSP arguments for every extension that service covers, then restarts those clients. The installed server still runs as a user-controlled child process, and may have its own file or network behaviour.

gopls, rust-analyzer, and clangd remain user-managed. Their supported upstream paths rely on the user's Go/Rust/LLVM toolchains or platform packages, which Shed will not modify from this panel. Configure them with `lsp.<ext>.command` and `lsp.<ext>.args` instead.

`LanguageServerDetector` resolves a local executable and invokes only bounded `--version` probes. It does not alter the environment, install or update tools, create cache files, or make network requests. Its result preserves the executable, server version, runtime version, failure, availability state, and manual remediation.

`ManagedLanguageInstaller` accepts an explicit review containing source, version, size when known, license, and target filename. It opens a transfer only after the trust gate returns `ALLOWED` for explicit consent, supports cancellation before and during transfer, writes only under the managed cache root, validates size and SHA-256 before activation, and never invokes privilege elevation or a package manager. A successful transfer writes an atomic receipt that records the exact coordinate, source, signing identity, digest, size, and filename. `ManagedLanguageArtifactStore` resolves a managed launch path only when that receipt matches the currently trusted catalog and the cached bytes still match its recorded size and SHA-256; missing, malformed, stale, revoked, or tampered entries return no launch path.

Pinned-archive tools retain at most their active version and one prior verified version. Installing a user-approved archive update atomically selects it and keeps the previous verified version for explicit rollback; superseded archive-cache versions are pruned. Rollback re-verifies the retained prior artifact before selecting it and never fetches a replacement. npm tools keep their package directory and lockfile in their own fixed version cache; no automatic update, resolution, or package-manager action occurs after installation. Both cache paths perform no startup, detection, or background network activity.

`:lsp manage` opens the Language Services panel. `:lsp manage status` performs no probe or network request. `:lsp manage detect <ext>` and `retry <ext>` start an explicit local-only, bounded version probe in a background job; `install` and `update` open the explicit review panel, while `remove` affects only the managed cache. The panel permits cancellation during transfer and extraction; a failed or cancelled action does not configure a launcher.

Subsequent installer and UI work must route managed installation through `ManagedLanguageSupportService`, which consumes the one-use GUI approval before performing I/O. Pinned archives must also satisfy `ManagedLanguageSupportTrust` before a download or cache write. Eclipse JDT LS documents its Java 21 minimum runtime, platform-specific configuration directories, and Eclipse Public License 2.0 source at [its project repository](https://github.com/eclipse-jdtls/eclipse.jdt.ls). Pyright documents its npm distribution and Node.js dependency in [its installation guide](https://github.com/microsoft/pyright/blob/main/docs/installation.md); its package metadata declares Node.js 14+ and the `pyright-langserver` executable. TypeScript Language Server documents its combined JavaScript/TypeScript scope and required `--stdio` invocation in [its repository](https://github.com/typescript-language-server/typescript-language-server); its v5.3.0 package declares Node.js 20+. [The official gopls documentation](https://go.dev/gopls/) specifies the Go 1.21+ toolchain requirement and its installation command. [rust-analyzer's installation guide](https://rust-analyzer.github.io/book/installation.html) requires its binary, a latest-stable Rust toolchain, and `rust-src`. [clangd's installation guide](https://clangd.llvm.org/installation.html) documents desktop-platform support, `clangd` stdio launch, and the compile-command requirement for accurate analysis.
