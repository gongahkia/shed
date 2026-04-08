[![](https://img.shields.io/badge/shed_1.0-passing-dark_green)](https://github.com/gongahkia/shed/releases/tag/1.0)
[![](https://img.shields.io/badge/shed_2.0-passing-green)](https://github.com/gongahkia/shed/releases/tag/2.0)

# `Shed` - a SHit EDitor

Shed is a [bare-bones](https://www.merriam-webster.com/dictionary/bare-bones), [opinionated](https://dictionary.cambridge.org/dictionary/english/opinionated), [modal text editor](https://carlosbecker.com/posts/ed/) with native [Vim](https://www.vim.org/) bindings.

## Features

* Tiny 32KB executable
* Written entirely with Java Swing
* No telemetry whatsoever
* Sensible defaults out-of-the-box
* First-class [Vim Bindings](./KEYBINDS.md)
* Highly customisable via [`~/.shed/shedrc`](./CONFIG.md)
* Rich [Command](./COMMANDS.md) Palette
* Extensible [Plugin](./PLUGINS.md) System

## Video of `Shed` editing its own source code

<div align="center">
  <video width="85%" src="https://github.com/user-attachments/assets/6f939653-e8ad-4346-8c46-95f1ed521b27"></video>
</div>

## Stack

* *Scripting*:
* *Package*:

## Usage

> ![IMPORTANT]  
> Please use either [JDK 17](https://www.oracle.com/java/technologies/downloads/#java17) or [JDK 20](https://www.oracle.com/java/technologies/downloads/#java20) as the specified JDK version for either of these instructions.

Note you can either choose to run the precompiled [`Shed.jar`](build/Shed.jar) file or build Shed yourself. 

### Running the precompiled [`Shed.jar`](build/Shed.jar) file

1. First install the [Java Runtime Environment (JRE)](https://www.oracle.com/java/technologies/downloads/) or [Java Development Kit (JDK)](https://www.oracle.com/java/technologies/downloads/) to enable running the `.jar` file. 
2. Then follow the relevant instructions for your respective operating system.

#### Linux

1. Download the Java Development Kit (JDK) [Linux distribution](https://www.oracle.com/java/technologies/downloads/#jdk20-linux).
2. Assuming the JDK file has been downloaded to the *Downloads* directory, run the following commands in your terminal.

```console
$ cd Downloads
$ sudo apt install jdk-20_linux-x64_bin.rpm # debain
$ sudo dnf install jdk-20_linux-x64_bin.rpm` # fedora
```

3. Download the [`Shed.jar`](build/Shed.jar) file.q
4. Run `Shed.jar` as you would any other file on your machine, by *double-clicking* it. 

#### OSX

1. Download the [MacOS distribution of the Java Development Kit (JDK)](https://www.oracle.com/java/technologies/downloads/#jdk20-mac).
2. Follow the JDK installer to install JDK to your machine.
3. Download the [`Shed.jar`](build/Shed.jar) file.
4. Run `Shed.jar` as you would any other file on your machine, by *double-clicking* it.

<p align="center">
  <img src="https://www.codejava.net/images/articles/javase/install-jdk-17/oracle_jdk_17_installer_macos.png" width="60%"/>
</p>

#### Windows

1. Download the Java Development Kit (JDK) [Windows distribution](https://www.oracle.com/java/technologies/downloads/#jdk20-windows).
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