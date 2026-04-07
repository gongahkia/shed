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

## [Plugins](./PLUGINS.md)

## [Commands](./COMMANDS.md)

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