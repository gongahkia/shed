[![](https://img.shields.io/badge/shed_1.0-passing-dark_green)](https://github.com/gongahkia/shed/releases/tag/1.0)
[![](https://img.shields.io/badge/shed_2.0-passing-green)](https://github.com/gongahkia/shed/releases/tag/2.0)

# `Shed` - a SHit EDitor

Shed is a [bare-bones](https://www.merriam-webster.com/dictionary/bare-bones), [opinionated](https://dictionary.cambridge.org/dictionary/english/opinionated), [modal text editor](https://carlosbecker.com/posts/ed/) with native [Vim](https://www.vim.org/) bindings.

## Features

* Open source, MIT LICENSE
* Highly customisable using Java Swing components and config file `~/.shed/shedrc`
* Stable, crash-proof
* Java-only with a tiny 32KB executable
* Respects your privacy, no telemetry whatsoever
* Extended VIM bindings 
* Multiple file editing with buffer management
* Search and replace with visual highlighting
* Undo/redo with per-buffer history
* Line numbers and word count
* 5 editor modes: Normal, Insert, Visual, Replace, Command

## Video of `Shed` editing its own source code

<div align="center">
  <video width="85%" src="https://github.com/user-attachments/assets/6f939653-e8ad-4346-8c46-95f1ed521b27"></video>
</div>

## [Key-bindings](./KEYBINDS.md)

## Installation

### Pre-requisites

The Java Runtime Environment (JRE) or Java Development Kit (JDK) is required to run the `.jar` file. It can be downloaded [here](https://www.oracle.com/java/technologies/downloads/).

> Please use either JDK 17 or JDK 20 as the specified JDK version.

## Windows

1. Download the Java Development Kit (JDK) [Windows distribution](https://www.oracle.com/java/technologies/downloads/#jdk20-windows).
2. Follow the set-up instructions to install JDK to your machine.
3. Download the [`Shed.jar`](build/Shed.jar) file.
4. Run `Shed.jar` as you would any other file on your machine, by *double-clicking* it.

<p align="center">
  <img src="https://phoenixnap.com/kb/wp-content/uploads/2021/12/java-installation-wizard-complete.png" />
</p>

## Linux

1. Download the Java Development Kit (JDK) [Linux distribution](https://www.oracle.com/java/technologies/downloads/#jdk20-linux).
2. Assuming the JDK file has been downloaded to the *Downloads* directory, run the following commands in your terminal.

```console
$ cd Downloads
$ sudo apt install jdk-20_linux-x64_bin.rpm 
```

> Note that the instructions above assume a Debian-based distro. Run the relevant commands for your distro.  
> *(eg. Fedora-based distros would run `sudo dnf install jdk-20_linux-x64_bin.rpm`)*

3. Download the [`Shed.jar`](build/Shed.jar) file.
4. Run `Shed.jar` as you would any other file on your machine, by *double-clicking* it. 

## MacOS

1. Download the Java Development Kit (JDK) [MacOS distribution](https://www.oracle.com/java/technologies/downloads/#jdk20-mac).
2. Follow the JDK installer to install JDK to your machine.
3. Download the [`Shed.jar`](build/Shed.jar) file.
4. Run `Shed.jar` as you would any other file on your machine, by *double-clicking* it.

<p align="center">
  <img src="https://www.codejava.net/images/articles/javase/install-jdk-17/oracle_jdk_17_installer_macos.png" />
</p>

## Build Shed yourself

1. In your terminal, run the following commands.

```console
$ git clone https://github.com/gongahkia/shed && cd shed
$ mvn -q -DskipTests package
$ java -jar target/shed-2.0.0.jar
```

> Note that the instructions above assume JDK 17 or JDK 20 have already been downloaded and added to PATH.

## Usage

Run Shed from the command line:

```console
$ java -jar target/shed-2.0.0.jar                 # Opens file chooser dialog
$ java -jar target/shed-2.0.0.jar filename.txt    # Opens specific file
```

## Themes

Configure in `~/.shed/shedrc`:

```ini
theme=one-dark-pro
```

Switch in editor:

```console
:themes
:set theme=gruvbox-dark
```

Built-in themes (ordered list):
`one-dark-pro`, `dracula`, `material-theme`, `night-owl`, `ayu-mirage`, `monokai-pro`, `tokyo-night`, `nord`, `gruvbox-dark`, `shades-of-purple`, `palenight`, `catppuccin-mocha`, `github-dark`, `rose-pine`, `synthwave-84`, `cobalt2`, `andromeda`, `everforest-dark`, `kanagawa`, `poimandres`, `solarized-dark`, `noctis`, `oxocarbon-dark`, `vesper`, `sonokai`, `doom-one`, `horizon`, `papercolor-dark`, `xcode-dark`, `dimmed-monokai`, `fleet-dark`, `nightfox`.

## Fonts

* Hack Nerd Font: https://www.nerdfonts.com/font-downloads

## Advanced Features (2.x)

### Quickfix + Diagnostics

Use quickfix as a shared navigation list for grep, shell output, and LSP diagnostics/references.

```console
:copen
:cnext
:cprev
:cc 3
:diagnostics
:dnext
:dprev
```

When cursoring inside quickfix or on a diagnosed line, the command bar shows an inline peek message before you jump.

### LSP Commands

LSP completion is available via `Ctrl-n`. Additional LSP flows are available in command mode:

```console
:lsp definition
:lsp hover
:lsp references
:lsp rename NewSymbolName
:lsp codeaction
```

### Tree Actions

`:`-commands for file-tree workflows:

```console
:tree
:tree /absolute/or/relative/path
:tree refresh
:tree reveal
:tree new src/NewFile.java
:tree mkdir src/new_folder
:tree rename old/path new/path
:tree rm old/path
:tree rm! old/non_empty_directory
```

### Git Ergonomics

Extended `:git` subcommands:

```console
:git status
:git add src/main/java/shed/Texteditor.java
:git stage src/test/java/shed
:git unstage src/test/java/shed
:git checkout main
:git switch feature-branch
:git commit "message"
:git amend --no-edit
:git amend "updated message"
```

### Sessions

Save and restore editor sessions:

```console
:session save
:session save worktree_a
:session load worktree_a
:session load! worktree_a
:session list
:clean
```

### Async Shell + Drop Runner

`Shed` now runs shell/filter flows asynchronously and exposes job controls.

```console
:!rg TODO src
:1,20!sort
:drop javac %
:jobs
:jobcancel 3
```

`%` in `:drop` is replaced with current file path. If `%` is omitted, current file path is appended.

### Runtime Hardening Settings

Set these in `~/.shed/shedrc`:

```ini
process.timeout.ms=15000
process.output.max.bytes=1048576
shell.command.max.length=4096
session.restore.on.start=false
session.autoload=default
session.dir=/Users/you/.shed/sessions
```

### Theater Mode (Dramatic UI)

Enable cinematic UI effects and tune each category independently:

```ini
ui.dramatic=true
ui.dramatic.identity=true
ui.dramatic.mode.transitions=true
ui.dramatic.command.palette=true
ui.dramatic.editing.feedback=true
ui.dramatic.panel.animations=true
ui.dramatic.sound=false
ui.dramatic.sound.pack=default
ui.dramatic.sound.volume=75
ui.dramatic.sound.cue.mode=true
ui.dramatic.sound.cue.navigate=true
ui.dramatic.sound.cue.success=true
ui.dramatic.sound.cue.error=true
ui.dramatic.reduced.motion=false
ui.dramatic.reduced.motion.sync=true
ui.dramatic.performance.guardrails=true
ui.dramatic.performance.cpu.threshold=0.80
ui.dramatic.performance.line.threshold=20000
ui.dramatic.animation.ms=220
ui.dramatic.minimap.width=84
```

All settings can be toggled at runtime via `:set key=value` (for example `:set ui.dramatic=false`).
Use `:theater off`, `:theater subtle`, or `:theater full` for one-shot presets.
Use `:set! key=value` to persist a single setting, or `:config save` to write all current runtime settings.

### Per-Project Overrides

Shed auto-loads `.shedrc.local` from the current file's project tree (nearest parent directory).
Use this for repo-specific settings without changing global `~/.shed/shedrc`.

### Markdown / Orgmode Features

Shed provides orgmode-inspired editing for markdown (`.md`) files.

#### Heading Folding

| Key-binds | Function |
| :---: | :---: |
| `TAB` (on heading) | Toggle fold/unfold heading section |
| `S-TAB` | Cycle global fold state (fold all / unfold all) |
| `za` | Toggle fold at cursor |
| `zM` | Fold all headings |
| `zR` | Unfold all headings |

#### Heading Navigation

| Key-binds | Function |
| :---: | :---: |
| `]]` | Jump to next heading |
| `[[` | Jump to previous heading |
| `]1`..`]6` | Jump to next heading at level N |
| `[1`..`[6` | Jump to previous heading at level N |
| `gO` | Open outline/TOC in split |
| `:toc` | Open table of contents buffer |

#### Heading Promotion/Demotion

| Key-binds | Function |
| :---: | :---: |
| `>>` (on heading) | Demote heading (add `#`) |
| `<<` (on heading) | Promote heading (remove `#`) |
| `>r` (on heading) | Demote entire subtree |
| `<r` (on heading) | Promote entire subtree |

#### Table Editing

| Key-binds / Commands | Function |
| :---: | :---: |
| `TAB` (in table, Insert mode) | Move to next cell |
| `S-TAB` (in table, Insert mode) | Move to previous cell |
| `Enter` (last column) | Create new table row |
| `:table` | Insert 3x2 table template |
| `:table NxM` | Insert NxM table template |
| `:table align` | Auto-align table columns |
| `:table sort N` | Sort table by column N |
| `:table sort N desc` | Sort table by column N descending |
| `:table insert-col` | Insert column after cursor |
| `:table delete-col` | Delete column at cursor |

#### Checkboxes

| Key-binds | Function |
| :---: | :---: |
| `:toggle` | Toggle `- [ ]` / `- [x]` checkbox |

#### Smart List Continuation

In insert mode, pressing `Enter` on a list item auto-continues the list:
* `- item` → `- ` on next line
* `1. item` → `2. ` on next line
* `- [ ] task` → `- [ ] ` on next line
* Empty list item on Enter → exits the list

#### Links

| Commands | Function |
| :---: | :---: |
| `:link` | Insert `[text](url)` template |
| `:img` | Insert `![alt](path)` template |
| `gf` (on link) | Open linked file or markdown link target |
| `gx` (on URL) | Open URL in browser |

#### Concealment

```console
:set conceallevel=0   # Show all markup
:set conceallevel=1   # Partial conceal
:set conceallevel=2   # Full conceal (hide markers)
```

### Snippets

Expand code snippets with `Ctrl-j` in insert mode. Built-in snippets for Java, Python, JavaScript, TypeScript, Rust, Go, C/C++, Markdown, and HTML.

```console
:snippets          # List available snippets for current file type
```

Example: type `main` then press `Ctrl-j` in a Java file to expand to `public static void main(String[] args) { }`.

### Bracket Pair Colorization

```console
:bracketcolor      # Toggle bracket pair colorization
:set bracketcolor  # Same
```

Nested `()`, `{}`, `[]` are colored with distinct colors to aid readability.

### Fuzzy Command Completion

TAB completion in command mode now uses fuzzy matching. Type a partial or approximate command name and press TAB to find the best match.

### Integrated Terminal

```console
:term              # Open a terminal split
:terminal          # Same
```

### File Auto-Reload

Files are now monitored via `java.nio.file.WatchService` for external changes, providing faster and more reliable reload prompts than polling.

### [Plugins](./PLUGINS.md)

### LSP Management

```console
:lsp status              # show running servers and errors
:lsp servers             # list all configured + builtin servers
:lsp restart [ext]       # restart server (defaults to current file ext)
:lsp stop [ext]          # stop a server
:lsp log                 # show error log
```