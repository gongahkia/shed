# Hotkey Delegation

Status: target contract for `olly` v0.1 hotkey-daemon interop. Last checked: 2026-06-26.
Scope: `NORTHSTAR.md` section 7.

## Ownership Rule

One chord has one runtime owner.

| Owner | Config location | What runs |
|---|---|---|
| `olly` | `Keybinds` in `~/.config/olly/Config.swift` | Carbon `RegisterEventHotKey` dispatches an `olly` action. |
| Karabiner-Elements | complex modification JSON | `shell_command` runs `ollyctl ...`. |
| skhd | `skhdrc` | skhd command runs `ollyctl ...`. |
| BetterTouchTool | trigger action | Run Shell Script action runs `ollyctl ...`. |
| Hammerspoon | `init.lua` | `hs.hotkey.bind` callback runs `ollyctl ...`. |

Delegation means omission: if a chord belongs to Karabiner-Elements, skhd, BetterTouchTool,
or Hammerspoon, do not declare the same chord in `Keybinds`. `olly` does not generate or edit
external daemon configs.

## Startup Conflict Policy

At startup, `olly` treats a collision as a diagnostic, not as a second binding source.

- Compare DSL-declared chords against observable macOS symbolic hotkeys and readable known-daemon config files.
- Log each collision with chord, `olly` action, and suspected external owner.
- Show one startup toast summarizing collision count.
- Keep running after logging; the user resolves ownership by removing one binding.
- If Carbon registration fails for a DSL chord, fail that binding fast and keep the failure visible in logs.

The rule for users is still one owner per chord. Collision logs are there to catch mistakes.

## Karabiner-Elements

Use Karabiner when the desired input is a remap, modifier layer, simultaneous key gesture, or
device-specific rule. Run `ollyctl` through `to.shell_command`, and use absolute paths because
Karabiner passes a limited environment to shell commands.

```json
{
  "description": "olly: focus next window",
  "manipulators": [
    {
      "type": "basic",
      "from": {
        "key_code": "j",
        "modifiers": { "mandatory": ["left_option"] }
      },
      "to": [
        { "shell_command": "/opt/homebrew/bin/ollyctl focus next" }
      ]
    }
  ]
}
```

Keep the matching `Keybind(KeyChord([.option], .j), ...)` out of the olly DSL.

## skhd

Use skhd when a text hotkey file and live reload are preferred. skhd executes commands through
`$SHELL -c`, so normal shell quoting applies.

```sh
alt - j : /opt/homebrew/bin/ollyctl focus next
alt + shift - 1 : /opt/homebrew/bin/ollyctl move-to-tag 1
```

Reload with `skhd -r` after editing.

## BetterTouchTool

Use BetterTouchTool when the trigger is a keyboard shortcut, gesture, mouse button, Stream Deck
button, menu bar item, or other BTT surface.

Create a trigger, add a Run Shell Script action, and use an absolute command path:

```sh
/opt/homebrew/bin/ollyctl set-engine niri-scroll
```

Do not also bind that same keyboard shortcut in `Keybinds`.

## Hammerspoon

Use Hammerspoon when Lua automation owns the workflow. Prefer absolute `ollyctl` paths and avoid
loading the full shell environment in hot callbacks unless needed.

```lua
local ollyctl = "/opt/homebrew/bin/ollyctl"

hs.hotkey.bind({"alt"}, "j", function()
  hs.execute(ollyctl .. " focus next")
end)
```

Hammerspoon can shadow an older active hotkey with a newer one, so keep ownership explicit in
`init.lua` and the olly DSL.

## Source Notes

- Karabiner-Elements `to.shell_command`: <https://karabiner-elements.pqrs.org/docs/json/complex-modifications-manipulator-definition/to/shell-command/>
- skhd README and examples: <https://github.com/asmvik/skhd>, <https://github.com/asmvik/skhd/blob/master/examples/skhdrc>
- BetterTouchTool Run Shell Script examples: <https://docs.folivora.ai/docs/other-triggers/text-selection/>
- Hammerspoon hotkeys and shell execution: <https://www.hammerspoon.org/docs/hs.hotkey.html>, <https://www.hammerspoon.org/docs/hs.html#execute>
