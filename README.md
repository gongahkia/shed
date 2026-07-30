[![](https://img.shields.io/badge/shed_1.0-passing-dark_green)](https://github.com/gongahkia/shed/releases/tag/1.0)
[![](https://img.shields.io/badge/shed_2.0-passing-green)](https://github.com/gongahkia/shed/releases/tag/2.0)
![](https://github.com/gongahkia/shed/actions/workflows/ci.yml/badge.svg)

# `Shed` - a SHit EDitor

Shed is a [bare-bones](https://www.merriam-webster.com/dictionary/bare-bones), [opinionated](https://dictionary.cambridge.org/dictionary/english/opinionated), [modal text editor](https://carlosbecker.com/posts/ed/) with native [Vim](https://www.vim.org/) bindings.

<div align="center">
    <img src="./assets/logo/shed.png" width="20%">
</div>

## Features

* Tiny ~1MB executable
* Written entirely in Java with Java Swing
* Sensible defaults out-of-the-box
* First-class [Vim Bindings](./docs/KEYBINDS.md)
* Highly customisable via [`~/.shed/config.toml`](./docs/CONFIG.md)
* Rich [Command](./docs/COMMANDS.md) Palette
* Extensible [Plugin](./docs/PLUGINS.md) System
* No telemetry; app-owned network paths require explicit action or consent ([boundary audit](./docs/NETWORK_PRIVACY.md))

## Video of `Shed` editing its own source code

<div align="center">
  <video width="85%" src="https://github.com/user-attachments/assets/6f939653-e8ad-4346-8c46-95f1ed521b27" controls muted playsinline preload="metadata"></video>
</div>

## Stack

* *Scripting*: [Java 21](https://www.oracle.com/java/technologies/downloads/#java21), [Java Swing](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/swing/package-summary.html)
* *Build*: [Maven](https://maven.apache.org/download.cgi)
* *Test*: [JUnit 5](https://junit.org/junit5/)

## Usage

> [!IMPORTANT]
> Please use [JDK 21](https://www.oracle.com/java/technologies/downloads/#java21) for either of these instructions.

Note you can either choose to run the precompiled [`Shed.jar`](build/Shed.jar) file or build Shed yourself. 

### Running the precompiled [`Shed.jar`](build/Shed.jar) file

1. First install the [Java Runtime Environment (JRE)](https://www.oracle.com/java/technologies/downloads/) or [Java Development Kit (JDK)](https://www.oracle.com/java/technologies/downloads/) to enable running the `.jar` file. 
2. Then follow the relevant instructions for your respective operating system.

#### Linux

1. Download the Java Development Kit (JDK) 21 [Linux distribution](https://www.oracle.com/java/technologies/downloads/#java21).
2. Assuming the JDK file has been downloaded to the *Downloads* directory, run the following commands in your terminal.

```console
$ cd Downloads
$ sudo apt install ./jdk-21_linux-x64_bin.deb # Debian/Ubuntu
$ sudo dnf install ./jdk-21_linux-x64_bin.rpm # Fedora
```

3. Download the [`Shed.jar`](build/Shed.jar) file.q
4. Run `Shed.jar` as you would any other file on your machine, by *double-clicking* it. 

#### OSX

1. Download the [macOS distribution of JDK 21](https://www.oracle.com/java/technologies/downloads/#java21).
2. Follow the JDK installer to install JDK to your machine.
3. Download the [`Shed.jar`](build/Shed.jar) file.
4. Run `Shed.jar` as you would any other file on your machine, by *double-clicking* it.

<p align="center">
  <a href="https://docs.oracle.com/en/java/javase/21/install/installation-guide.pdf">JDK 21 installation guide</a>
</p>

#### Windows

1. Download the JDK 21 [Windows distribution](https://www.oracle.com/java/technologies/downloads/#java21).
2. Follow the set-up instructions to install JDK to your machine.
3. Download the [`Shed.jar`](build/Shed.jar) file.
4. Run `Shed.jar` as you would any other file on your machine, by *double-clicking* it.

<p align="center">
  <img src="https://phoenixnap.com/kb/wp-content/uploads/2021/12/java-installation-wizard-complete.png" width="60%"/>
</p>

### Building `Shed` yourself

1. First run the below commands to install `Shed` locally on your machine.

```console
$ git clone https://github.com/gongahkia/shed && cd shed
```

2. Then run the below commands to build and run `Shed`.

```console
$ mvn -q -DskipTests package
$ java -jar target/shed-2.0.0.jar
$ java -jar target/shed-2.0.0.jar # opens file chooser dialog
$ java -jar target/shed-2.0.0.jar filename.txt # opens specific file
```

### macOS arm64 installer

On macOS arm64 with JDK 21, run `bash scripts/package-macos.sh` to create a DMG with a bundled runtime. See [macOS packaging](./docs/MACOS_PACKAGING.md) for verification and signing/notarization boundaries.

### Windows x64 installer

On Windows x64 with JDK 21 and WiX available to `jpackage`, run `./scripts/package-windows.ps1` to create an MSI with a bundled runtime. See [Windows packaging](./docs/WINDOWS_PACKAGING.md) for verification and signing boundaries.

### Linux x64 installer

On Debian-family Linux x64 with JDK 21, `dpkg-deb`, and `fakeroot`, run `bash scripts/package-linux.sh` to create a DEB with a bundled runtime. See [Linux packaging](./docs/LINUX_PACKAGING.md) for installation, verification, and signing boundaries.
