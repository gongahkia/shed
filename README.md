[![](https://img.shields.io/badge/shed_1.0-passing-dark_green)](https://github.com/gongahkia/shed/releases/tag/1.0)
[![](https://img.shields.io/badge/shed_2.0-passing-green)](https://github.com/gongahkia/shed/releases/tag/2.0)

# `Shed` - a SHit EDitor

Shed is a [bare-bones](https://www.merriam-webster.com/dictionary/bare-bones), [opinionated](https://dictionary.cambridge.org/dictionary/english/opinionated), [modal text editor](https://carlosbecker.com/posts/ed/).

<div align="center">
    <img src="./assets/logo/shed.png" width="20%">
</div>

## Stack

* *Scripting*: [Java 21](https://www.oracle.com/java/technologies/downloads/#java21), [Java Swing](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/swing/package-summary.html)
* *Build*: [Maven](https://maven.apache.org/download.cgi)
* *Test*: [JUnit 5](https://junit.org/junit5/)

## Features

* Written entirely in [Java](https://www.java.com/en/) & [Swing](https://en.wikipedia.org/wiki/Swing_(Java))
* Sensible defaults [out-of-the-box](https://en.wikipedia.org/wiki/Out_of_the_Box)
* First-class [Vim](./docs/KEYBINDS.md) and [Emacs](./docs/KEYBINDS.md) bindings
* Highly customisable via [`~/.shed/config.toml`](./docs/CONFIG.md)
* Command Palette, Snippets, LSP completion & Symbol navigation, Incremental Lexical Highlighting 
* Fully Extensible [Plugin](./docs/PLUGINS.md) System
* Java [extension API](./docs/EXTENSIONS.md) for language services, debuggers, test/SCM providers, terminal profiles, custom editors, remote workspaces, and docked tool views
* Multi-root workspaces, portable [`.shed-workspace` / `.code-workspace` folder lists](./docs/WORKSPACE_MANIFESTS.md), notebooks, integrated terminals, and explicit [remote](./docs/REMOTE_WORKSPACES.md)/Dev Container workflows
* No [telemetry](https://en.wikipedia.org/wiki/Telemetry) whatsoever
* Bundled-runtime platform installers

## Video of `Shed` editing its own source code

<div align="center">
  <video width="85%" src="https://github.com/user-attachments/assets/6f939653-e8ad-4346-8c46-95f1ed521b27" controls muted playsinline preload="metadata"></video>
</div>

## Usage

> [!IMPORTANT]  
> Use Java 21 for the JAR or source build.

Choose a published `shed-<version>.jar`, a bundled-runtime platform installer, or a local source build. See [install and troubleshooting](./docs/INSTALL.md) for supported targets and verification.

### Running a published JAR file

1. Install a Java 21 runtime or JDK to run the `.jar` file.
2. Then follow the relevant instructions for your respective operating system.

#### OSX

1. Install a Java 21 runtime or JDK for macOS.
2. From the directory containing the published JAR, run `java -jar shed-<version>.jar [file]`.
3. See [install and troubleshooting](./docs/INSTALL.md) for the bundled-runtime macOS arm64 target.
4. For additional reference, see the [JDK 21 installation guide](https://docs.oracle.com/en/java/javase/21/install/installation-guide.pdf).

#### Windows

1. Install a Java 21 runtime or JDK for Windows.
2. From PowerShell in the JAR directory, run `java -jar shed-<version>.jar [file]`.
3. See [install and troubleshooting](./docs/INSTALL.md) for the bundled-runtime Windows x64 target.

<p align="center">
  <img src="https://phoenixnap.com/kb/wp-content/uploads/2021/12/java-installation-wizard-complete.png" width="60%"/>
</p>

#### Linux

1. Install a Java 21 runtime or JDK for Linux.
2. From the directory containing the published JAR, run:

```console
$ java -jar shed-<version>.jar [file]
```

3. See [install and troubleshooting](./docs/INSTALL.md) for the bundled-runtime DEB target.

### Building `Shed` yourself

1. First run the below commands to install `Shed` locally on your machine.

```console
$ git clone https://github.com/gongahkia/shed && cd shed
```

2. Then run the below commands to build and run `Shed`.

```console
$ mvn -q -DskipTests package
$ java -jar target/shed-2.0.0.jar
```

### Native installation methods

If you'd prefer, `Shed` also features native installers for the below major platforms. These do not require a separate JDK or JRE.

#### OSX arm64 installer

On macOS arm64 with JDK 21, run `bash scripts/package-macos.sh` to create a DMG with a bundled runtime. See [macOS packaging](./docs/MACOS_PACKAGING.md) for verification and signing/notarization boundaries.

#### Windows installer

On Windows x64 with JDK 21 and WiX available to `jpackage`, run `./scripts/package-windows.ps1` to create an MSI with a bundled runtime. See [Windows packaging](./docs/WINDOWS_PACKAGING.md) for verification and signing boundaries.

#### Linux installer

On Debian-family Linux x64 with JDK 21, `dpkg-deb`, and `fakeroot`, run `bash scripts/package-linux.sh` to create a DEB with a bundled runtime. See [Linux packaging](./docs/LINUX_PACKAGING.md) for installation, verification, and signing boundaries.
