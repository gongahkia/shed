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

An artifact not present at that exact coordinate is rejected. An entry with an HTTP/non-host source, malformed SHA-256, missing signing-key id, missing detached signature, or no supported platform is rejected before a download can start. The installer work will validate downloaded bytes against the catalog digest and verify the signature before activation; metadata is never trusted merely because a URL was supplied by a user or server.

`ManagedLanguageSupportTrust` is the no-I/O policy gate. Its `Assessment.permitsManagedNetwork()` and `permitsManagedCacheWrite()` return true only for an approved, cataloged, signed, integrity-pinned, platform-supported, non-revoked Shed-managed artifact. The class intentionally contains no downloader, process launcher, or background work.

## Platform and Cache Ownership

Catalog entries enumerate supported platforms; an absent platform is rejected. Managed files belong only under `~/.shed/managed-languages/<toolId>/<version>/`. Coordinate validation rejects separators and traversal segments, and cache resolution verifies that the final path remains below that root.

Shed may create, replace, quarantine, or remove only that managed-language cache. It must not modify a user-managed executable, a user-managed package-manager directory, or a workspace. A failed validation leaves the prior known-good managed artifact untouched.

## Revocation

A release may list an exact `toolId@version` coordinate as revoked. Revocation rejects both installation and activation, even if that artifact remains in the cache. Shed must mark cached revoked content unavailable and present remediation; it must not fetch a replacement in the background or delete a user-managed tool. Removing quarantined managed cache content requires an explicit user action.

Revocation data changes arrive only with an explicit Shed release/update flow. Shed does not poll a catalog endpoint.

## Current Scope

This issue defines the policy and its executable decision model only. There is no managed-language catalog, download, installer, network request, cache creation, or consent UI yet. Subsequent catalog, installer, integrity, cache-policy, and UI work must call this gate before performing I/O.
