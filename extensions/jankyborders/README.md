# Olly JankyBorders Extension

This bridge consumes olly focus events and re-applies a JankyBorders config only when the
focused window key changes. Duplicate focus/tag replay is ignored to avoid border flicker.

## Install

```sh
mkdir -p ~/.config/olly/jankyborders
cp -R extensions/jankyborders/* ~/.config/olly/jankyborders/
chmod +x ~/.config/olly/jankyborders/*.sh
```

Start the bridge after `ollyApp` and JankyBorders are running:

```sh
~/.config/olly/jankyborders/olly_focus_borders.sh &
```

`jq` is required. Runtime border-flicker verification requires the `borders` binary; the
included verification script checks that duplicate olly focus events produce one config call.

```sh
extensions/jankyborders/verify-no-flicker.sh
```
