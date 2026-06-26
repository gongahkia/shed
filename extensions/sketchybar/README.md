# Olly SketchyBar Extension

This example forwards `ollyctl events --json` into a SketchyBar custom event named
`olly_update`.

## Install

```sh
mkdir -p ~/.config/sketchybar/olly
cp -R extensions/sketchybar/* ~/.config/sketchybar/olly/
chmod +x ~/.config/sketchybar/olly/plugins/*.sh
```

Source the example from your main `~/.config/sketchybar/sketchybarrc`:

```sh
source "$HOME/.config/sketchybar/olly/sketchybarrc"
```

`jq` is optional. Without it, the item still updates on every olly event but shows a generic label.
