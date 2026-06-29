# Extension ABI Gap

## Sources checked

- VS Code Contribution Points documentation: extension capabilities are declared through JSON contribution points, including commands, languages, problem matchers, and task definitions.

## Current implementation slice

- Added a schema-versioned JSON extension manifest ABI under `.itsy/extensions/*.json`.
- Added declarative task contributions.
- Wired extension-contributed tasks into workspace task discovery.
- Added manifest validation and discovery tests.

## Not done yet

- No executable plugin host.
- No command/menu contribution wiring.
- No language/snippet/theme contribution wiring.
- No trust/sandbox model.

## Next slice

Add command-palette contribution metadata and a trust model before any executable extension host.
