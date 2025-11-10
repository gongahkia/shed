![](https://img.shields.io/badge/shed_2.0-passing-green)

# Shed - a SHit EDitor

Shed is a bare-bones, opinionated, modal text editor with extended Vim bindings, written in Java.

## Features

* Open source, MIT LICENSE
* Highly customisable using Java Swing components and config file *(~/.shedrc)*
* Stable, crash-proof
* 32KB executable
* Respects your privacy, no telemetry whatsoever
* Extended VIM bindings *(a great primer for newcomers interested in VIM/NEOVIM)*
* Multiple file editing with buffer management
* Search and replace with visual highlighting
* Undo/redo with per-buffer history
* Line numbers and word count
* 5 editor modes: Normal, Insert, Visual, Replace, Command

As per custom, here is a video of ***Shed*** editing its own source code.

https://user-images.githubusercontent.com/117062305/226877220-1900ca35-50b4-4623-a008-e86f8c9cace0.mp4

## Key-bindings

### Mode Switching

| Key-binds | Function |
| :---: | :---: |
| `i` | Enter `Insert` mode |
| `v` | Enter `Visual` mode |
| `R` | Enter `Replace` mode |
| `:` | Enter `Command` mode |
| `/` | Enter `Command` mode (search) |
| `ESC` | Return to `Normal` mode |

### `Normal` mode

#### Character Movement

| Key-binds | Function |
| :---: | :---: |
| `h` or `←` | Move one character left |
| `j` or `↓` | Move one line down |
| `k` or `↑` | Move one line up |
| `l` or `→` | Move one character right |

#### Word Movement

| Key-binds | Function |
| :---: | :---: |
| `w` | Move to start of next word |
| `b` | Move to start of previous word |
| `e` | Move to end of current/next word |

#### Line Movement

| Key-binds | Function |
| :---: | :---: |
| `0` | Move to start of line |
| `$` | Move to end of line |

#### File Movement

| Key-binds | Function |
| :---: | :---: |
| `gg` | Move to start of file |
| `G` | Move to end of file |
| `Ctrl+d` | Scroll half-page down |
| `Ctrl+u` | Scroll half-page up |

#### Copy (Yank)

| Key-binds | Function |
| :---: | :---: |
| `yy` | Yank (copy) entire line |

#### Delete

| Key-binds | Function |
| :---: | :---: |
| `dd` | Delete entire line |
| `dw` | Delete word forward |
| `D` | Delete to end of line |
| `x` | Delete character under cursor |

#### Change (Delete + Insert Mode)

| Key-binds | Function |
| :---: | :---: |
| `cc` | Change entire line |
| `cw` | Change word forward |
| `C` | Change to end of line |

#### Paste

| Key-binds | Function |
| :---: | :---: |
| `p` | Paste after cursor/line |
| `P` | Paste before cursor/line |

#### Undo/Redo

| Key-binds | Function |
| :---: | :---: |
| `u` | Undo last change |
| `Ctrl+r` | Redo undone change |

#### Search

| Key-binds | Function |
| :---: | :---: |
| `/pattern` | Search forward for pattern |
| `n` | Jump to next match |
| `N` | Jump to previous match |

#### Repeat

| Key-binds | Function |
| :---: | :---: |
| `.` | Repeat last command |

### `Insert` mode

| Key-binds | Function |
| :---: | :---: |
| `ESC` | Exit to `Normal` mode |
| Any text | Insert text at cursor |

### `Visual` mode

| Key-binds | Function |
| :---: | :---: |
| `h/j/k/l` | Navigate (same as Normal mode) |
| `w/b/e` | Word movement (same as Normal mode) |
| `0/$` | Line start/end (same as Normal mode) |
| `y` | Yank (copy) selection |
| `d` | Delete selection |
| `c` | Change selection (delete + Insert mode) |
| `ESC` | Exit to `Normal` mode |

### `Replace` mode

| Key-binds | Function |
| :---: | :---: |
| `ESC` | Exit to `Normal` mode |
| Any character | Overwrite character at cursor |

### `Command` mode

#### File Operations

| Key-binds | Function |
| :---: | :---: |
| `:w` | Save changes to file |
| `:q` | Quit (prompts if unsaved changes) |
| `:q!` | Force quit (discard changes) |
| `:wq` or `:x` | Save and quit |
| `:e filename` | Edit file (add to buffers) |

#### Buffer Management

| Key-binds | Function |
| :---: | :---: |
| `:bn` | Switch to next buffer |
| `:bp` | Switch to previous buffer |
| `:ls` | List all open buffers |
| `:bd` | Delete current buffer |
| `:bd!` | Force delete buffer (discard changes) |

#### Search & Replace

| Key-binds | Function |
| :---: | :---: |
| `/pattern` | Search forward for pattern |
| `:%s/old/new/g` | Replace all occurrences |
| `:%s/old/new` | Replace first occurrence only |

#### Settings

| Key-binds | Function |
| :---: | :---: |
| `:set nu` | Enable line numbers |
| `:set nonu` | Disable line numbers |

#### Navigation

| Key-binds | Function |
| :---: | :---: |
| `:45` | Go to line 45 |
| `45gg` | Go to line 45 (also works in Normal mode) |

#### Utilities

| Key-binds | Function |
| :---: | :---: |
| `:wc` | Show word count |
| `:help` | Display help dialog |

#### Exit Command Mode

| Key-binds | Function |
| :---: | :---: |
| `ENTER` | Execute command |
| `ESC` | Cancel command |
| `BACKSPACE` | Delete character |


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

```bash
cd Downloads
sudo apt install jdk-20_linux-x64_bin.rpm 
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

```bash
git clone https://github.com/gongahkia/shed
cd shed/src
javac *.java
jar cfm ../build/Shed.jar ../build/Manifest.txt *.class
java -jar ../build/Shed.jar
```

> Note that the instructions above assume JDK 17 or JDK 20 have already been downloaded and added to PATH.

## Usage

Run Shed from the command line:

```bash
java -jar Shed.jar                 # Opens file chooser dialog
java -jar Shed.jar filename.txt    # Opens specific file
```

## Documentation

* **[FEATURES.md](FEATURES.md)** - Comprehensive feature documentation
* **[KEYBINDINGS.md](KEYBINDINGS.md)** - Quick keybinding reference
* **[ARCHITECTURE.md](ARCHITECTURE.md)** - Developer documentation
* **[CHANGELOG.md](CHANGELOG.md)** - Version history and upgrade guide

## Fonts

* Hack Nerd Font: https://www.nerdfonts.com/font-downloads
