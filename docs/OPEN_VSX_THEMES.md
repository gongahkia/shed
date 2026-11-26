# Open VSX themes

Shed's existing Theme Gallery can discover Open VSX color-theme packages, inspect adapted palettes, install them for offline use, preview them in the workbench and explicitly apply/save a selection. Shed Light and bundled JetBrains Mono remain the defaults. Installing a package does not select it or change fonts, density, source text or keybindings.

## Try it

Start the updated build with `make run`, then run `:theme gallery` inside Shed.

1. Open **Discover**, enter a theme or publisher name and press **Search**. Searches return up to 30 theme packages; narrow the query to find another package.
2. Select a package and choose **Load Theme Previews**. This downloads and checks the package, then shows its variants, palette, declared license, repository and compatibility notes. It does not install or apply anything.
3. Choose a variant to inspect. **License** shows packaged text supplied under conventional LICENSE filenames. Registry listing is not a claim that every package has an approved open-source license; review the declaration and terms.
4. Choose **Install Package**. All supported variants in the package appear under **Installed**, alongside the unchanged built-in palettes. Theme assets are now available offline.
5. Use **Preview in App** and **Cancel Preview**, or **Apply and Save**. Closing the gallery cancels an unaccepted workbench preview. A save error leaves the preview cancellable and preserves the previous preference through the existing preview lease.

To update, search again, load the desired package version and choose **Update Package**. Updates are explicit; there is no background update service. A downloaded version is pinned to the registry identity and checksum inspected for that request.

To remove a package, first apply and save a theme from another package or a built-in theme. Select an installed variant, choose **Remove Package**, and confirm. Removal affects all variants in that package. Current, previewed and saved theme references block removal or replacement, including saved display-name aliases. Built-in themes cannot be removed.

Manage installed packages with one main Shed window open. The existing workbench preview also retains its single-main-window restriction. Independent Shed processes should not manage the same catalog concurrently; cross-process theme transactions and independent process-wide Swing defaults are not qualified here.

## Compatibility

This is a color-data importer into Shed's existing palette system. It is not a VS Code extension host. Extension activation, commands, scripts, dependencies, language grammars, remote content and icons are not installed or executed.

Supported input is a VSIX containing `extension/package.json` and contributed JSON/JSONC color themes. Comments, trailing commas, UTF-8 BOMs and bounded relative JSON includes are supported. A package with an unsupported/missing theme file is rejected without partial installation. `.tmTheme`/plist files are currently unsupported.

| VS Code data | Shed treatment |
| --- | --- |
| Editor background/foreground | Base editor palette; existing user color overrides retain precedence. |
| Cursor, selection, current line and gutter colors | Corresponding existing editor roles. |
| Status bar, inputs, sidebar, floating surface and borders | Corresponding Shed workbench surfaces; layout and component hierarchy stay unchanged. |
| Common TextMate comments, keywords, strings, numbers, constants, types, functions and annotations | Approximate mapping into Shed's existing syntax categories. The gallery sample uses the translated syntax colors too. |
| Alpha colors | Composited onto the editor background; no transparent windows or new transparency behavior. |
| Missing colors | Existing light/dark base colors and Shed's normal derived-role fallbacks. |
| Full TextMate selector specificity, nested/language-specific selectors, semantic-token rules, font styles, terminal ANSI palettes and icon themes | Not reproduced. Notes appear with downloaded and installed palettes. |

An imported theme can therefore look different from VS Code. Theme authors' colors are not silently replaced to claim visual or accessibility equivalence. Imported-theme contrast and multilingual rendering depend on the selected palette and the existing editor/font environment; the prior glyph and Unicode layout limitations remain open. Fonts are not supplied or changed by imports.

## Storage, networking and lifecycle

Normalized palettes and provenance are stored in `~/.shed/themes/open-vsx.json`, schema version 1. The catalog includes package identity/version, theme identity, mapped hex colors, license declaration/text, repository and compatibility notes. Theme identity includes the package and a digest of its normalized theme path, so normal version updates preserve IDs. Archives and executable assets are not stored.

The catalog loads locally at configuration initialization and refreshes in the background when opening the gallery. Missing storage does not create files. Corrupt, oversized, unsupported-version or symlinked storage is preserved and reported; built-in themes remain usable. Writes reuse Shed's checked atomic writer with catalog-change preconditions. This is not a security boundary against a same-user process concurrently replacing filesystem objects, nor a multi-process transaction lock.

Network access starts only with Search or Load Theme Previews. Requests contain the search query/package identity, not editor source or project data. The client uses HTTPS, fixed registry/CDN hosts, bounded redirects and responses, timeouts, registry-published SHA-256 verification and the existing background job service. The checksum checks consistency with registry metadata; it is not publisher-signature verification or an audit of a theme author.

Only one gallery operation is admitted at a time. Loading/installing/removing disables competing controls. Closing cancels the owned job and rejects late completions. A catalog write already committed when the gallery closes can remain installed, but it does not apply a theme; reopening the gallery refreshes that catalog. No new permanent worker, polling loop or startup network request is added.

Current bounds: 120-character queries, 30 search results, 1 MiB metadata/individual expanded entry, 20 MiB compressed VSIX, 16 MiB expanded total, 4,096 entries, eight include levels, 8,192 syntax rules, 64 variants per package and 128 installed variants in a 1 MiB catalog. Unsupported hosts, missing checksums/licenses, identity mismatches and exceeded limits fail with a gallery error. No archive entries are extracted to disk.

## Qualification

See [the evidence index](performance/open-vsx-themes/INDEX.md) for commands, tests, actual macOS desktop captures, the public registry smoke and explicit platform/suite exclusions. Native-shell benchmarking remains blocked. This feature does not resolve the earlier multi-split checked-open/read rejection, Unicode first-edit stalls, missing glyphs, HTML preview stalls or incomplete native-memory qualification.
