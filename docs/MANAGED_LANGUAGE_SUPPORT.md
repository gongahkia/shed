# Managed Language Support Trust Model

This model governs future Shed-managed language runtimes and language servers. It does not alter existing `lsp.<ext>.command` or `lsp.<ext>.args` launches: those continue to execute a user-managed local command and Shed neither downloads, updates, caches, nor revokes that tool.

## Ownership Boundary

| Tool kind | Owner | Network and cache behaviour |
| :--- | :--- | :--- |
| User-managed | User | Shed launches only the configured/local executable. It performs no managed download, update, cache write, integrity claim, or revocation action. |
| Shed-managed | Shed | The exact cataloged artifact is eligible only after all policy checks below pass. |

Shed-managed support is not automatic. Startup, file open, LSP detection, and background refresh must not issue a network request. A user must invoke an install or update action and explicitly approve that action; absence of approval produces `CONSENT_REQUIRED`, which permits neither a managed network request nor a cache write.

## Catalog Provenance and Artifact Identity

The managed catalog is bundled with a Shed release, reviewed with that release, and identifies every artifact by an immutable `toolId@version` coordinate. A catalog entry contains:

- HTTPS source URI;
- SHA-256 of the downloaded bytes;
- signing-key identifier and detached artifact signature;
- exact supported-platform set; and
- the coordinate for revocation matching.

An artifact not present at that exact coordinate is rejected. An entry with an HTTP/non-host source, malformed SHA-256, missing signing-key id, missing detached signature, or no supported platform is rejected before a download can start. The installer validates downloaded bytes against the catalog digest before activation and preserves the catalog signing identity in its receipt; cryptographic detached-signature verification remains required before a production managed artifact is admitted. Metadata is never trusted merely because a URL was supplied by a user or server.

`ManagedLanguageSupportTrust` is the no-I/O policy gate. Its `Assessment.permitsManagedNetwork()` and `permitsManagedCacheWrite()` return true only for an approved, cataloged, signed, integrity-pinned, platform-supported, non-revoked Shed-managed artifact. The class intentionally contains no downloader, process launcher, or background work.

## Platform and Cache Ownership

Catalog entries enumerate supported platforms; an absent platform is rejected. Managed files belong only under `~/.shed/managed-languages/<toolId>/<version>/`. Coordinate validation rejects separators and traversal segments, and cache resolution verifies that the final path remains below that root.

Shed may create, replace, quarantine, or remove only that managed-language cache. It must not modify a user-managed executable, a user-managed package-manager directory, or a workspace. A failed validation leaves the prior known-good managed artifact untouched.

## Revocation

A release may list an exact `toolId@version` coordinate as revoked. Revocation rejects both installation and activation, even if that artifact remains in the cache. Shed must mark cached revoked content unavailable and present remediation; it must not fetch a replacement in the background or delete a user-managed tool. Removing quarantined managed cache content requires an explicit user action.

Revocation data changes arrive only with an explicit Shed release/update flow. Shed does not poll a catalog endpoint.

## Current Scope

The catalog records Eclipse JDT LS (`java.eclipse-jdtls@1.50.0`, Java 21+, Eclipse Public License 2.0), Pyright (`python.pyright@1.1.411`, Node.js 14+, MIT License), TypeScript Language Server (`typescript.typescript-language-server@5.3.0`, Node.js 22.22.2+, Apache License 2.0), gopls (`go.gopls@0.23.0`, Go 1.21+, BSD 3-Clause License), rust-analyzer (`rust.rust-analyzer@2026-07-27`, latest stable Rust with `rust-src`, MIT OR Apache-2.0), and clangd (`c-cpp.clangd@22.1.8`, clangd 7+, Apache License 2.0 with LLVM Exceptions) for macOS, Windows, and Linux. The TypeScript entry serves `.js`, `.jsx`, `.ts`, and `.tsx`; clangd serves `.c`, `.cc`, `.cpp`, `.cxx`, `.h`, `.hpp`, and `.hxx`. It records platform-specific local command names and evaluates missing executable, unknown runtime, incompatible runtime, consent-required, and untrusted-artifact states with a manual configuration remediation. Runtime parsing preserves Go's `1.x` version scheme separately from legacy Java `1.x`; rust-analyzer instead requires a locally validated latest-stable toolchain and `rust-src`. It does not contain an approved binary source, checksum, or signature for a catalog entry, so a managed install remains unavailable until a later catalog revision supplies a signed, integrity-pinned artifact and the user approves the install. User-managed `jdtls`, `pyright-langserver`, `typescript-language-server`, `gopls`, `rust-analyzer`, and `clangd` commands can run locally after their runtimes are validated.

JSON adds `vscode-json-languageserver --stdio` for `.json` and `.jsonc`; upstream declares no Node.js version floor. Markdown adds `remark-language-server --stdio` for `.md` and `.markdown`, requiring Node.js 16+. These are user-managed commands until a signed, integrity-pinned managed artifact is added and approved.

`LanguageServerDetector` resolves a local executable and invokes only bounded `--version` probes. It does not alter the environment, install or update tools, create cache files, or make network requests. Its result preserves the executable, server version, runtime version, failure, availability state, and manual remediation.

`ManagedLanguageInstaller` accepts an explicit review containing source, version, size when known, license, and target filename. It opens a transfer only after the trust gate returns `ALLOWED` for explicit consent, supports cancellation before and during transfer, writes only under the managed cache root, validates size and SHA-256 before activation, and never invokes privilege elevation or a package manager. A successful transfer writes an atomic receipt that records the exact coordinate, source, signing identity, digest, size, and filename. `ManagedLanguageArtifactStore` resolves a managed launch path only when that receipt matches the currently trusted catalog and the cached bytes still match its recorded size and SHA-256; missing, malformed, stale, revoked, or tampered entries return no launch path.

Each managed tool retains at most its active version and one prior verified version. Installing a user-approved update atomically selects it and keeps the previous verified version for explicit rollback; superseded managed-cache versions are pruned. Rollback re-verifies the retained prior artifact before selecting it and never fetches a replacement. This cache policy performs no startup, detection, or background network activity.

Subsequent catalog, installer, integrity, cache-policy, and UI work must call this gate before performing I/O. Eclipse JDT LS documents its Java 21 minimum runtime, platform-specific configuration directories, and Eclipse Public License 2.0 source at [its project repository](https://github.com/eclipse-jdtls/eclipse.jdt.ls). Pyright documents its npm distribution and Node.js dependency in [its installation guide](https://github.com/microsoft/pyright/blob/main/docs/installation.md); its package metadata declares Node.js 14+ and the `pyright-langserver` executable. TypeScript Language Server documents its combined JavaScript/TypeScript scope and required `--stdio` invocation in [its repository](https://github.com/typescript-language-server/typescript-language-server); its package metadata declares Node.js 22.22.2+. [The official gopls documentation](https://go.dev/gopls/) specifies the Go 1.21+ toolchain requirement and its installation command. [rust-analyzer's installation guide](https://rust-analyzer.github.io/book/installation.html) requires its binary, a latest-stable Rust toolchain, and `rust-src`. [clangd's installation guide](https://clangd.llvm.org/installation.html) documents desktop-platform support, `clangd` stdio launch, and the compile-command requirement for accurate analysis.
