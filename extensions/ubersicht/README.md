# Olly Übersicht Widget

Simple-bar-compatible Übersicht widget that reads `ollyctl state --json` and renders a compact
status strip.

## Install

```sh
mkdir -p "$HOME/Library/Application Support/Übersicht/widgets"
cp -R extensions/ubersicht/olly.widget "$HOME/Library/Application Support/Übersicht/widgets/"
```

The widget refreshes once per second. If `ollyctl` is not on the default shell `PATH`, set
`OLLYCTL=/path/to/ollyctl` in the environment used to launch Übersicht.

## Config

Edit `extensions/ubersicht/olly.widget/index.jsx` after copying if you need to change:

- `refreshFrequency`
- top/left positioning in `className`
- the `command` PATH prefix

Sources checked 2026-06-26: <https://tracesof.net/uebersicht/> and
<https://github.com/Jean-Tinland/simple-bar>.
