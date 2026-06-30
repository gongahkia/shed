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

Current TODO keeps extensions tasks-only via id:411; no Phase18/19/20 task id adds command/menu contributions, trust/sandboxing, or an executable host. Next extension slice should be a new post-Phase20 TODO for command-palette contribution metadata plus trust model before host work.
