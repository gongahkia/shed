# Command Palette

The command palette is Shed’s discoverable surface for controls that run without user-supplied arguments. It omits its own opener and commands that require a target, path, query, replacement, or explicit confirmation; use the `:` command bar for those.

Each action is unique by visible label and normalized command. The application rejects duplicate entries when it starts.

## Files and buffers

| Action | Command | Purpose |
| :--- | :--- | :--- |
| Open File | `:open file` | Choose and open a local file. |
| Open Folder | `:open folder` | Choose a local folder, activate it as the workspace, and show its file tree. |
| Toggle File Tree | `:tree` | Open or close the workspace file tree. |
| File Finder | `:files` | Open the project file finder. |
| Recent Files | `:recent` | Open the recently used files list. |
| Buffer Picker | `:buffers` | Show every open buffer, filter the list, and switch to a selected buffer. |
| Switch to Next Open Buffer | `:bn` | Switch to the next open buffer. |
| Switch to Previous Open Buffer | `:bp` | Switch to the previous open buffer. |
| Close Current Buffer | `:bdelete` | Close the current buffer, prompting when needed. |
| Discard Current Buffer | `:discard current buffer` | Close the current buffer without saving its changes. |
| Save Current Buffer | `:write` | Write the current buffer. |
| Save All Buffers | `:wall` | Write all modified file-backed buffers. |
| Save and Close Active Window | `:wq` | Write the current buffer, then close its editor window. |
| Save All and Quit | `:wqa` | Write all modified buffers and quit. |
| Close Active Window | `:q` | Close the active editor window, or its last buffer and show the landing page. |
| Close Active Window Without Saving | `:q!` | Close the active editor window without saving its changes. |
| Quit All | `:qa` | Quit all buffers, prompting when needed. |
| Quit All Without Saving | `:qa!` | Quit Shed without saving modified buffers. |

## Splits and interface scale

| Action | Command | Purpose |
| :--- | :--- | :--- |
| Split Below | `:s` | Create a horizontal split below the active editor. |
| Split Right | `:vs` | Create a vertical split beside the active editor. |
| Focus Next Split | `:window next` | Focus the next editor split. |
| Focus Split Left | `:window left` | Focus the split to the left. |
| Focus Split Right | `:window right` | Focus the split to the right. |
| Focus Split Above | `:window up` | Focus the split above. |
| Focus Split Below | `:window down` | Focus the split below. |
| Equalize Splits | `:window equalize` | Give each editor split an equal share of space. |
| Grow Current Split | `:window grow` | Increase the active split's size. |
| Shrink Current Split | `:window shrink` | Decrease the active split's size. |
| Zoom In | `:zoom in` | Increase the entire interface scale by 10%. |
| Zoom Out | `:zoom out` | Decrease the entire interface scale by 10%. |
| Reset UI Zoom | `:zoom reset` | Restore the interface scale to 100%. |

## Quickfix and diagnostics

| Action | Command | Purpose |
| :--- | :--- | :--- |
| Open Quickfix | `:copen` | Open the current quickfix list. |
| Next Quickfix Result | `:cnext` | Move to the next quickfix result. |
| Previous Quickfix Result | `:cprev` | Move to the previous quickfix result. |
| First Quickfix Result | `:cfirst` | Move to the first quickfix result. |
| Last Quickfix Result | `:clast` | Move to the last quickfix result. |
| Open Current Quickfix Result | `:cc` | Open the selected quickfix result. |
| Problems | `:problems` | Open the unified diagnostics and quickfix Problems panel. |
| Show Diagnostics | `:diagnostics` | Show diagnostics for the active buffer. |
| Next Diagnostic | `:dnext` | Move to the next diagnostic. |
| Previous Diagnostic | `:dprev` | Move to the previous diagnostic. |

## Configuration and appearance

| Action | Command | Purpose |
| :--- | :--- | :--- |
| Settings | `:settings` | Open the Settings inspector, including font, landing-buffer, and Markdown-preview settings. |
| Open Settings TOML | `:settings file` | Open the persisted settings.toml buffer. |
| Configuration Status | `:config status` | Show configuration loading and recovery details. |
| Apply Suggested Config Repairs | `:config heal` | Persist the reviewed deterministic configuration repairs. |
| Reload Configuration | `:reload` | Reload configuration from disk. |
| Keymap Inspector | `:keymap` | Inspect and edit validated keymap overlays. |
| Themes | `:themes` | Show built-in themes. |
| Toggle Zen Mode | `:zen` | Toggle the distraction-free Zen layout. |
| Toggle Goyo Mode | `:goyo` | Toggle the Goyo layout. |
| Toggle Limelight | `:limelight` | Toggle paragraph focus dimming. |
| Toggle Minimap | `:minimap` | Toggle the minimap panel. |
| Undo History | `:undolist` | Show the undo history summary. |
| Clear Search Highlights | `:noh` | Clear active search highlights. |
| Command Log | `:log` | Open the command log buffer. |
| Show Help | `:help` | Open the built-in help buffer. |
| About Shed | `:version` | Show Shed version and local runtime details. |

## Editing and local information

| Action | Command | Purpose |
| :--- | :--- | :--- |
| Format Current Buffer | `:format` | Format the active buffer with its selected formatter. |
| Formatter Policy | `:formatter` | Configure the current language's formatter and format-on-save policy. |
| Markdown Preview | `:markdownpreview` | Open the live native Markdown preview beside the source buffer. |
| Table of Contents | `:toc` | Open the current Markdown document's table of contents. |
| Document Outline | `:outline` | Open the current document outline. |
| Toggle Markdown Checkbox | `:toggle` | Toggle the checkbox at the caret. |
| Insert Markdown Table | `:table` | Insert the default Markdown table template. |
| Insert Link | `:link` | Insert a link at the caret. |
| Insert Image | `:image` | Choose and insert a local image reference. |
| Edit Snippets | `:snippets edit` | Open the user snippets buffer for editing. |
| Toggle Bracket Colors | `:bracketcolor` | Toggle matching bracket colorization. |
| Integrated Terminal | `:terminal` | Open the integrated terminal split. |
| Word Count | `:wordcount` | Show line, word, and character counts. |
| Registers | `:registers` | Show register contents. |
| Yank Ring | `:yankring` | Open the copied and deleted text history. |
| Marks | `:marks` | Show marks for the active buffer. |

## Language services

| Action | Command | Purpose |
| :--- | :--- | :--- |
| Language Services | `:lsp manage` | Open the local Language Services panel. |
| Show Completions | `:lsp completion` | Request completion candidates at the caret. |
| Go to Definition | `:definition` | Go to the definition at the caret. |
| Go to Type Definition | `:typedefinition` | Go to the type definition at the caret. |
| Go to Implementation | `:implementation` | Go to the implementation at the caret. |
| Highlight Symbol Occurrences | `:lsp highlights` | Highlight server-reported occurrences for the symbol at the caret. |
| Show Hover Information | `:hover` | Show language-service hover information at the caret. |
| Find References | `:references` | Find references for the symbol at the caret. |
| Code Actions | `:lsp codeaction` | Show diagnostic-anchored code actions at the caret. |
| Peek Definition | `:peek definition` | Open a temporary read-only definition preview. |
| Peek Type Definition | `:peek type` | Open a temporary read-only type-definition preview. |
| Incoming Call Hierarchy | `:lsp calls incoming` | Open the incoming-call hierarchy for the symbol at the caret. |
| Outgoing Call Hierarchy | `:lsp calls outgoing` | Open the outgoing-call hierarchy for the symbol at the caret. |
| Type Supertypes | `:lsp typehierarchy supertypes` | Open the supertype hierarchy for the symbol at the caret. |
| Type Subtypes | `:lsp typehierarchy subtypes` | Open the subtype hierarchy for the symbol at the caret. |
| Document Symbols | `:symbols` | Open the document-symbol picker, with a local fallback. |

## Workspace and tools

| Action | Command | Purpose |
| :--- | :--- | :--- |
| Workspace Folders | `:workspace ui` | Open the workspace-folder manager. |
| Workspace Index Status | `:workspace index status` | Show the workspace search index status. |
| Project Replace | `:projectreplace` | Open the reviewed project-wide replacement panel. |
| Tasks | `:task` | Open the workspace Tasks panel. |
| Tests | `:test` | Open the Test Explorer. |
| Import Coverage Report | `:coverage ui` | Open Tests, then use Import Coverage to choose a local coverage report. |
| Debug | `:debug` | Open the Debug tool panel. |
| Toolchain Status | `:toolchain status` | Show selected local toolchains and advisory candidates. |
| Large File Status | `:largefile` | Show active large-file limits and status. |
| Async Jobs | `:jobs` | Show asynchronous jobs. |
| Git Changes | `:git workbench` | Open the docked Git Changes workbench. |
| Git Status | `:git status` | Show the active repository's Git status. |
| Git Branches | `:git branches` | Show the active repository's branches. |
| Git Conflict Resolution | `:git conflict` | Open the graphical conflict-resolution view. |
| Git History and Remotes | `:git history` | Open graphical local history and explicit remote controls. |
| Git Worktrees and Stashes | `:git worktrees` | Open graphical worktree and stash controls. |
| Git Graph | `:git log` | Open graphical local Git history when enabled. |
| GitHub Pull Requests | `:github prs` | Open pull-request review after GitHub review consent has been granted. |
| Remote Workspaces | `:remote list` | Inspect explicit remote connections, active execution sessions, and loopback SSH forwards. |
| Dev Container | `:container status` | Inspect the active workspace's Dev Container configuration and session routing state. |
| Compose Status | `:compose status` | Show workspace Compose configuration status. |
| Database Status | `:database status` | Show workspace database configuration status. |
| Workspace Integrations | `:integration` | Show configured workspace integrations. |
| Plugin Manager | `:plugin` | Show installed plugins. |
| Extension Manager | `:extension` | Show installed extensions and their contributions. |
| Custom Editors | `:customeditor` | Show available custom editors. |
| Update Status | `:update` | Show the configured update channel's status. |
