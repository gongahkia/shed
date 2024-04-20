![](https://img.shields.io/badge/shed_1.0-passing-green)

# Shed - a SHit EDitor

Shed is a bare-bones, opinionated, modal text editor with limited Vim bindings, written in Java.

## Features

* Open source, MIT LICENSE
* Highly customisable using Java Swing components *(self-documenting code)*
* Stable, crash-proof
* 4KB executable
* Respects your privacy, no telemetry whatsoever
* Limited VIM bindings *(a good primer for newcomers interested in VIM/NEOVIM)*

As per custom, here is a video of ***Shed*** editing its own source code.

https://user-images.githubusercontent.com/117062305/226877220-1900ca35-50b4-4623-a008-e86f8c9cace0.mp4

## Key-bindings

### `Normal` mode

| Key-binds | Function |
| :---: | :---: |
| `↑` | Move one line up |
| `↓` | Move one line down |
| `→` | Move one character right |
| `←` | Move one character left |
| `i` | Enter `Insert` mode |
| `:w` | Save changes made to file |
| `:q` | Exit file, changes automatically saved |

### `Insert` mode

| Key-binds | Function |
| :---: | :---: |
| `ESC` | Exit to `Normal` mode |


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
cd src
javac Texteditor.java
java Texteditor
```

> Note that the instructions above assume JDK 17 or JDK 20 have already been downloaded and added to PATH.

## Fonts

* Hack Nerd Font: https://www.nerdfonts.com/font-downloads
