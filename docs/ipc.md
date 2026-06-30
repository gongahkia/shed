# IPC Protocol

Status: v2. Schema version: `2`.

Olly IPC uses a Unix-domain socket and newline-delimited JSON. The default socket is
`$XDG_RUNTIME_DIR/olly.sock`; on macOS without `XDG_RUNTIME_DIR`, olly falls back to
`~/.config/olly/olly.sock`.

Each client writes one JSON request per line. Normal commands receive one JSON response line.
`subscribe-events` upgrades the connection to an event stream after an `ok` response; `ollyctl events`
consumes that response and then prints event lines.

## Commands

- `state`
- `focus`
- `list-windows`
- `list-displays`
- `move-window`
- `move-to-display`
- `swap`
- `toggle-floating`
- `snap-window`
- `dispatch-gesture`
- `manual-preselect`
- `bsp-tree`
- `switch-tag`
- `move-to-tag`
- `toggle-tag`
- `set-engine`
- `cycle-engine`
- `tag-add`
- `tag-remove`
- `reload`
- `restore-windows`
- `subscribe-events`
- `version`
- `scratchpad-add`
- `scratchpad-toggle`
- `scratchpad-list`
- `scratchpad-remove`
- `toggle-sticky`
- `toggle-pinned`
- `explain-window`
- `explain-rule`
- `macro-start`
- `macro-stop`
- `macro-run`
- `macro-list`
- `macro-delete`
- `run-raw-action`
- `set-space-policy`
- `set-focus-policy`
- `telemetry-status`
- `telemetry-flush`
- `show-overlay`
- `list-cooperative-apps`

## Directional window operations

`focus` accepts `next`/`previous` and spatial `left`/`right`/`up`/`down`. Spatial directions use the
latest visible layout placement when available and the captured window frame otherwise. Selection prefers
windows in the requested direction with perpendicular overlap, then nearest primary-axis distance, then
layout order/window ID.

`move-window` and `swap` operate on the focused tiled window in the active display/tag set. `move-window`
re-inserts the focused window before or after the directional target; `swap` exchanges the focused window
with that target. If no focused window or no directional target exists, the response is a structured error.
`state` responses include `layoutOrder` for windows that have been explicitly reordered. Olly persists
layout order by stable window identity and restores it on later window discovery when the identity matches.

`list-windows` and `list-displays` return scoped `state` payloads for scripts that need stable query
commands instead of parsing the full runtime state. `toggle-floating` changes whether a window participates
in tiling, and `move-to-display` updates Olly's display assignment before re-arranging the affected displays.
`engineOverride` reports a per-window layout opt-out; currently `.floating` means the tag engine leaves that
window at its current frame. Other per-window engine overrides are rejected as `unsupported_engine_command`.
`isFullscreen` marks windows temporarily excluded from tag engines while native fullscreen owns their Space.
`isOffSpace` marks managed windows that are absent from the active native Space but still tracked.
`set-space-policy` accepts `follow-window`, `rehome`, or `unmanage`; `follow-window` is the default.
`set-focus-policy` updates focus-stealing controls. Omitted fields keep their current values; a provided
`allowedBundleIDs` array replaces the allowlist.
`snap-window` places a window in a safe-layout display zone and makes it floating by default so the next
tiling arrange does not immediately overwrite the user placement. `dispatch-gesture` resolves a configured
DSL gesture binding for external tools such as BetterTouchTool or Hammerspoon and executes the resulting
runtime action.
`show-overlay` opens an interactive app overlay; `grid` displays snap zones and commits with Enter.
`manual-preselect` and `bsp-tree` expose engine tree controls for manual/BSP layouts; both return structured
`unsupported_engine_command` errors when the active engine does not match the requested tree operation.
`explain-window` returns every rule trace for a window; `explain-rule` returns one rule trace for the focused
window.
`macro-start`, `macro-stop`, `macro-run`, `macro-list`, and `macro-delete` record/replay IPC commands.
Macros are persisted as `~/.config/olly/macros/<name>.json`; macro commands themselves are not recorded.
`run-raw-action` executes a configured shell action by label when the loaded DSL permissions allow it, then
emits a `rawAction` event with status, exit code, elapsed time, and stdout/stderr heads.
`list-cooperative-apps` reports resolved cooperative app bundle IDs, behaviors, and currently detected
window counts.

## Schema

The schema block below is tested against the Swift IPC constants. v2 changes are additive only;
breaking wire changes must bump `protocolVersion`, `$id`, and this document.

<!-- ipc-schema:start -->
```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://olly.dev/schemas/ipc.v2.json",
  "title": "olly IPC JSON line protocol v2",
  "oneOf": [
    {
      "$ref": "#/$defs/requestEnvelope"
    },
    {
      "$ref": "#/$defs/responseEnvelope"
    },
    {
      "$ref": "#/$defs/eventEnvelope"
    }
  ],
  "$defs": {
    "protocolVersion": {
      "type": "integer",
      "const": 2
    },
    "commandName": {
      "type": "string",
      "enum": [
        "state",
        "focus",
        "list-windows",
        "list-displays",
        "move-window",
        "move-to-display",
        "swap",
        "toggle-floating",
        "snap-window",
        "dispatch-gesture",
        "manual-preselect",
        "bsp-tree",
        "switch-tag",
        "move-to-tag",
        "toggle-tag",
        "set-engine",
        "cycle-engine",
        "tag-add",
        "tag-remove",
        "reload",
        "restore-windows",
        "subscribe-events",
        "version",
        "scratchpad-add",
        "scratchpad-toggle",
        "scratchpad-list",
        "scratchpad-remove",
        "toggle-sticky",
        "toggle-pinned",
        "explain-window",
        "explain-rule",
        "macro-start",
        "macro-stop",
        "macro-run",
        "macro-list",
        "macro-delete",
        "run-raw-action",
        "set-space-policy",
        "set-focus-policy",
        "telemetry-status",
        "telemetry-flush",
        "show-overlay",
        "list-cooperative-apps"
      ]
    },
    "direction": {
      "type": "string",
      "enum": [
        "up",
        "down",
        "left",
        "right",
        "next",
        "previous"
      ]
    },
    "eventKind": {
      "type": "string",
      "enum": [
        "axPermission",
        "config",
        "display",
        "engine",
        "engineChange",
        "focus",
        "focusBlocked",
        "fullscreen",
        "macro",
        "rawAction",
        "runtimeError",
        "space",
        "tag",
        "window"
      ]
    },
    "snapPosition": {
      "type": "string",
      "enum": [
        "left-half",
        "right-half",
        "top-half",
        "bottom-half",
        "top-left",
        "top-right",
        "bottom-left",
        "bottom-right",
        "center",
        "maximize"
      ]
    },
    "overlayKind": {
      "type": "string",
      "enum": [
        "grid",
        "cheatsheet",
        "alt-tab"
      ]
    },
    "gestureTrigger": {
      "type": "string",
      "enum": [
        "fourFingerHorizontal",
        "fourFingerVertical"
      ]
    },
    "gestureMotion": {
      "type": "string",
      "enum": [
        "left",
        "right",
        "upward",
        "downward"
      ]
    },
    "manualPreselectDirection": {
      "type": "string",
      "enum": [
        "left",
        "right",
        "up",
        "down"
      ]
    },
    "bspTreeAction": {
      "type": "string",
      "enum": [
        "rotateChildren",
        "flipAxis",
        "balanceTree"
      ]
    },
    "nativeSpacePolicy": {
      "type": "string",
      "enum": [
        "follow-window",
        "rehome",
        "unmanage"
      ]
    },
    "spaceDriftAction": {
      "type": "string",
      "enum": [
        "marked-off-space",
        "returned",
        "rehomed",
        "unmanaged"
      ]
    },
    "rawActionStatus": {
      "type": "string",
      "enum": [
        "completed",
        "denied",
        "failed",
        "timed-out"
      ]
    },
    "bspContainerPath": {
      "type": "object",
      "required": [
        "indexes"
      ],
      "properties": {
        "indexes": {
          "type": "array",
          "items": {
            "type": "integer",
            "minimum": 0,
            "maximum": 1
          }
        }
      },
      "additionalProperties": false
    },
    "tagIndex": {
      "type": "integer",
      "minimum": 0,
      "maximum": 63
    },
    "windowID": {
      "type": "integer",
      "minimum": 0
    },
    "displayID": {
      "type": "integer",
      "minimum": 0
    },
    "uuid": {
      "type": "string",
      "format": "uuid"
    },
    "layoutEngineID": {
      "type": "object",
      "required": [
        "rawValue"
      ],
      "properties": {
        "rawValue": {
          "type": "string",
          "minLength": 1
        }
      },
      "additionalProperties": false
    },
    "requestEnvelope": {
      "type": "object",
      "required": [
        "version",
        "command"
      ],
      "properties": {
        "version": {
          "$ref": "#/$defs/protocolVersion"
        },
        "id": {
          "type": [
            "string",
            "null"
          ]
        },
        "command": {
          "$ref": "#/$defs/command"
        }
      },
      "additionalProperties": false
    },
    "command": {
      "type": "object",
      "required": [
        "name",
        "arguments"
      ],
      "properties": {
        "name": {
          "$ref": "#/$defs/commandName"
        },
        "arguments": {
          "type": "object"
        }
      },
      "oneOf": [
        {
          "properties": {
            "name": {
              "const": "state"
            },
            "arguments": {
              "$ref": "#/$defs/stateArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "focus"
            },
            "arguments": {
              "$ref": "#/$defs/directionalArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "list-windows"
            },
            "arguments": {
              "$ref": "#/$defs/windowQueryArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "list-displays"
            },
            "arguments": {
              "$ref": "#/$defs/displayQueryArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "move-window"
            },
            "arguments": {
              "$ref": "#/$defs/directionalArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "move-to-display"
            },
            "arguments": {
              "$ref": "#/$defs/moveToDisplayArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "swap"
            },
            "arguments": {
              "$ref": "#/$defs/directionalArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "toggle-floating"
            },
            "arguments": {
              "$ref": "#/$defs/floatingArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "toggle-sticky"
            },
            "arguments": {
              "$ref": "#/$defs/stickyArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "toggle-pinned"
            },
            "arguments": {
              "$ref": "#/$defs/pinnedArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "snap-window"
            },
            "arguments": {
              "$ref": "#/$defs/snapWindowArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "show-overlay"
            },
            "arguments": {
              "$ref": "#/$defs/showOverlayArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "dispatch-gesture"
            },
            "arguments": {
              "$ref": "#/$defs/dispatchGestureArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "manual-preselect"
            },
            "arguments": {
              "$ref": "#/$defs/manualPreselectArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "bsp-tree"
            },
            "arguments": {
              "$ref": "#/$defs/bspTreeArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "switch-tag"
            },
            "arguments": {
              "$ref": "#/$defs/tagArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "move-to-tag"
            },
            "arguments": {
              "$ref": "#/$defs/moveToTagArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "toggle-tag"
            },
            "arguments": {
              "$ref": "#/$defs/tagArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "set-engine"
            },
            "arguments": {
              "$ref": "#/$defs/setEngineArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "cycle-engine"
            },
            "arguments": {
              "$ref": "#/$defs/cycleEngineArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "tag-add"
            },
            "arguments": {
              "$ref": "#/$defs/tagArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "tag-remove"
            },
            "arguments": {
              "$ref": "#/$defs/tagArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "reload"
            },
            "arguments": {
              "$ref": "#/$defs/emptyArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "restore-windows"
            },
            "arguments": {
              "$ref": "#/$defs/emptyArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "list-cooperative-apps"
            },
            "arguments": {
              "$ref": "#/$defs/emptyArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "explain-window"
            },
            "arguments": {
              "$ref": "#/$defs/explainWindowArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "explain-rule"
            },
            "arguments": {
              "$ref": "#/$defs/explainRuleArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "macro-start"
            },
            "arguments": {
              "$ref": "#/$defs/macroNameArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "macro-stop"
            },
            "arguments": {
              "$ref": "#/$defs/emptyArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "macro-run"
            },
            "arguments": {
              "$ref": "#/$defs/macroNameArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "macro-list"
            },
            "arguments": {
              "$ref": "#/$defs/emptyArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "macro-delete"
            },
            "arguments": {
              "$ref": "#/$defs/macroNameArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "run-raw-action"
            },
            "arguments": {
              "$ref": "#/$defs/runRawActionArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "set-space-policy"
            },
            "arguments": {
              "$ref": "#/$defs/setSpacePolicyArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "set-focus-policy"
            },
            "arguments": {
              "$ref": "#/$defs/setFocusPolicyArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "subscribe-events"
            },
            "arguments": {
              "$ref": "#/$defs/subscribeEventsArguments"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "version"
            },
            "arguments": {
              "$ref": "#/$defs/emptyArguments"
            }
          }
        }
      ],
      "additionalProperties": false
    },
    "emptyArguments": {
      "type": "object",
      "additionalProperties": false
    },
    "stateArguments": {
      "type": "object",
      "properties": {
        "displayID": {
          "$ref": "#/$defs/displayID"
        }
      },
      "additionalProperties": false
    },
    "directionalArguments": {
      "type": "object",
      "required": [
        "direction"
      ],
      "properties": {
        "direction": {
          "$ref": "#/$defs/direction"
        },
        "displayID": {
          "$ref": "#/$defs/displayID"
        }
      },
      "additionalProperties": false
    },
    "windowQueryArguments": {
      "type": "object",
      "properties": {
        "windowID": {
          "$ref": "#/$defs/windowID"
        },
        "displayID": {
          "$ref": "#/$defs/displayID"
        }
      },
      "additionalProperties": false
    },
    "displayQueryArguments": {
      "type": "object",
      "properties": {
        "displayID": {
          "$ref": "#/$defs/displayID"
        }
      },
      "additionalProperties": false
    },
    "moveToDisplayArguments": {
      "type": "object",
      "required": [
        "displayID"
      ],
      "properties": {
        "displayID": {
          "$ref": "#/$defs/displayID"
        },
        "windowID": {
          "$ref": "#/$defs/windowID"
        }
      },
      "additionalProperties": false
    },
    "floatingArguments": {
      "type": "object",
      "properties": {
        "windowID": {
          "$ref": "#/$defs/windowID"
        },
        "floating": {
          "type": "boolean"
        },
        "displayID": {
          "$ref": "#/$defs/displayID"
        }
      },
      "additionalProperties": false
    },
    "stickyArguments": {
      "type": "object",
      "properties": {
        "windowID": {
          "$ref": "#/$defs/windowID"
        },
        "sticky": {
          "type": "boolean"
        },
        "displayID": {
          "$ref": "#/$defs/displayID"
        }
      },
      "additionalProperties": false
    },
    "pinnedArguments": {
      "type": "object",
      "properties": {
        "windowID": {
          "$ref": "#/$defs/windowID"
        },
        "pinned": {
          "type": "boolean"
        },
        "displayID": {
          "$ref": "#/$defs/displayID"
        }
      },
      "additionalProperties": false
    },
    "snapWindowArguments": {
      "type": "object",
      "required": [
        "position",
        "makeFloating"
      ],
      "properties": {
        "position": {
          "$ref": "#/$defs/snapPosition"
        },
        "windowID": {
          "$ref": "#/$defs/windowID"
        },
        "displayID": {
          "$ref": "#/$defs/displayID"
        },
        "makeFloating": {
          "type": "boolean"
        }
      },
      "additionalProperties": false
    },
    "showOverlayArguments": {
      "type": "object",
      "required": [
        "kind"
      ],
      "properties": {
        "kind": {
          "$ref": "#/$defs/overlayKind"
        }
      },
      "additionalProperties": false
    },
    "dispatchGestureArguments": {
      "type": "object",
      "required": [
        "trigger",
        "motion"
      ],
      "properties": {
        "trigger": {
          "$ref": "#/$defs/gestureTrigger"
        },
        "motion": {
          "$ref": "#/$defs/gestureMotion"
        },
        "displayID": {
          "$ref": "#/$defs/displayID"
        }
      },
      "additionalProperties": false
    },
    "manualPreselectArguments": {
      "type": "object",
      "required": [
        "direction"
      ],
      "properties": {
        "direction": {
          "$ref": "#/$defs/manualPreselectDirection"
        },
        "windowID": {
          "$ref": "#/$defs/windowID"
        },
        "displayID": {
          "$ref": "#/$defs/displayID"
        }
      },
      "additionalProperties": false
    },
    "bspTreeArguments": {
      "type": "object",
      "required": [
        "action",
        "path"
      ],
      "properties": {
        "action": {
          "$ref": "#/$defs/bspTreeAction"
        },
        "path": {
          "$ref": "#/$defs/bspContainerPath"
        },
        "displayID": {
          "$ref": "#/$defs/displayID"
        }
      },
      "additionalProperties": false
    },
    "moveToTagArguments": {
      "type": "object",
      "required": [
        "tag"
      ],
      "properties": {
        "tag": {
          "$ref": "#/$defs/tagIndex"
        },
        "windowID": {
          "$ref": "#/$defs/windowID"
        },
        "displayID": {
          "$ref": "#/$defs/displayID"
        }
      },
      "additionalProperties": false
    },
    "setEngineArguments": {
      "type": "object",
      "required": [
        "engineID"
      ],
      "properties": {
        "engineID": {
          "$ref": "#/$defs/layoutEngineID"
        },
        "tag": {
          "$ref": "#/$defs/tagIndex"
        },
        "displayID": {
          "$ref": "#/$defs/displayID"
        }
      },
      "additionalProperties": false
    },
    "cycleEngineArguments": {
      "type": "object",
      "properties": {
        "reverse": {
          "type": "boolean"
        },
        "tag": {
          "$ref": "#/$defs/tagIndex"
        },
        "displayID": {
          "$ref": "#/$defs/displayID"
        }
      },
      "additionalProperties": false
    },
    "tagArguments": {
      "type": "object",
      "required": [
        "tag"
      ],
      "properties": {
        "tag": {
          "$ref": "#/$defs/tagIndex"
        },
        "displayID": {
          "$ref": "#/$defs/displayID"
        }
      },
      "additionalProperties": false
    },
    "macroName": {
      "type": "string",
      "pattern": "^[A-Za-z0-9._-]+$",
      "minLength": 1
    },
    "macroNameArguments": {
      "type": "object",
      "required": [
        "name"
      ],
      "properties": {
        "name": {
          "$ref": "#/$defs/macroName"
        }
      },
      "additionalProperties": false
    },
    "runRawActionArguments": {
      "type": "object",
      "required": [
        "label"
      ],
      "properties": {
        "label": {
          "type": "string",
          "minLength": 1
        }
      },
      "additionalProperties": false
    },
    "explainWindowArguments": {
      "type": "object",
      "properties": {
        "windowID": {
          "$ref": "#/$defs/windowID"
        }
      },
      "additionalProperties": false
    },
    "explainRuleArguments": {
      "type": "object",
      "required": [
        "ruleID"
      ],
      "properties": {
        "ruleID": {
          "$ref": "#/$defs/uuid"
        }
      },
      "additionalProperties": false
    },
    "setSpacePolicyArguments": {
      "type": "object",
      "required": [
        "policy"
      ],
      "properties": {
        "policy": {
          "$ref": "#/$defs/nativeSpacePolicy"
        }
      },
      "additionalProperties": false
    },
    "setFocusPolicyArguments": {
      "type": "object",
      "properties": {
        "allowedBundleIDs": {
          "type": "array",
          "items": {
            "type": "string",
            "minLength": 1
          },
          "uniqueItems": true
        },
        "maxEventsPerSecond": {
          "type": "integer",
          "minimum": 1
        },
        "minHumanIntervalMilliseconds": {
          "type": "integer",
          "minimum": 0
        }
      },
      "additionalProperties": false
    },
    "subscribeEventsArguments": {
      "type": "object",
      "required": [
        "eventKinds",
        "replayCurrentState"
      ],
      "properties": {
        "eventKinds": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/eventKind"
          },
          "uniqueItems": true
        },
        "supportedEventKinds": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/eventKind"
          },
          "uniqueItems": true
        },
        "replayCurrentState": {
          "type": "boolean"
        }
      },
      "additionalProperties": false
    },
    "responseEnvelope": {
      "type": "object",
      "required": [
        "version",
        "status"
      ],
      "properties": {
        "version": {
          "$ref": "#/$defs/protocolVersion"
        },
        "id": {
          "type": [
            "string",
            "null"
          ]
        },
        "status": {
          "type": "string",
          "enum": [
            "ok",
            "error"
          ]
        },
        "result": {
          "$ref": "#/$defs/commandResult"
        },
        "error": {
          "$ref": "#/$defs/errorPayload"
        }
      },
      "additionalProperties": false
    },
    "commandResult": {
      "type": "object",
      "required": [
        "name",
        "payload"
      ],
      "properties": {
        "name": {
          "type": "string",
          "enum": [
            "acknowledged",
            "cooperative-apps",
            "macro",
            "macros",
            "restored-windows",
            "rule-explanation",
            "scratchpads",
            "state",
            "subscribed",
            "version"
          ]
        },
        "payload": {
          "type": "object"
        }
      },
      "oneOf": [
        {
          "properties": {
            "name": {
              "const": "acknowledged"
            },
            "payload": {
              "$ref": "#/$defs/acknowledgementPayload"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "cooperative-apps"
            },
            "payload": {
              "$ref": "#/$defs/cooperativeAppsPayload"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "macro"
            },
            "payload": {
              "$ref": "#/$defs/macroPayload"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "macros"
            },
            "payload": {
              "$ref": "#/$defs/macrosPayload"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "restored-windows"
            },
            "payload": {
              "$ref": "#/$defs/restoredWindowsPayload"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "rule-explanation"
            },
            "payload": {
              "$ref": "#/$defs/ruleExplanationPayload"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "scratchpads"
            },
            "payload": {
              "$ref": "#/$defs/scratchpadsPayload"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "state"
            },
            "payload": {
              "$ref": "#/$defs/statePayload"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "subscribed"
            },
            "payload": {
              "$ref": "#/$defs/subscriptionPayload"
            }
          }
        },
        {
          "properties": {
            "name": {
              "const": "version"
            },
            "payload": {
              "$ref": "#/$defs/versionPayload"
            }
          }
        }
      ],
      "additionalProperties": false
    },
    "acknowledgementPayload": {
      "type": "object",
      "properties": {
        "message": {
          "type": [
            "string",
            "null"
          ]
        }
      },
      "additionalProperties": false
    },
    "ruleApply": {
      "type": "object",
      "properties": {
        "tagMask": {
          "type": [
            "integer",
            "null"
          ],
          "minimum": 0
        },
        "engineOverride": {
          "anyOf": [
            {
              "$ref": "#/$defs/layoutEngineID"
            },
            {
              "type": "null"
            }
          ]
        },
        "floating": {
          "type": [
            "boolean",
            "null"
          ]
        },
        "sticky": {
          "type": [
            "boolean",
            "null"
          ]
        },
        "pinned": {
          "type": [
            "boolean",
            "null"
          ]
        }
      },
      "additionalProperties": false
    },
    "ruleMatchTrace": {
      "type": "object",
      "required": [
        "ruleID",
        "matched"
      ],
      "properties": {
        "ruleID": {
          "$ref": "#/$defs/uuid"
        },
        "matched": {
          "type": "boolean"
        },
        "bundleIDMatched": {
          "type": [
            "boolean",
            "null"
          ]
        },
        "titleMatched": {
          "type": [
            "boolean",
            "null"
          ]
        },
        "roleMatched": {
          "type": [
            "boolean",
            "null"
          ]
        },
        "subroleMatched": {
          "type": [
            "boolean",
            "null"
          ]
        },
        "predicateMatched": {
          "type": [
            "boolean",
            "null"
          ]
        }
      },
      "additionalProperties": false
    },
    "ruleExplanationPayload": {
      "type": "object",
      "required": [
        "traces",
        "finalApply"
      ],
      "properties": {
        "windowID": {
          "anyOf": [
            {
              "$ref": "#/$defs/windowID"
            },
            {
              "type": "null"
            }
          ]
        },
        "ruleID": {
          "anyOf": [
            {
              "$ref": "#/$defs/uuid"
            },
            {
              "type": "null"
            }
          ]
        },
        "traces": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/ruleMatchTrace"
          }
        },
        "finalApply": {
          "$ref": "#/$defs/ruleApply"
        }
      },
      "additionalProperties": false
    },
    "cooperativeBehavior": {
      "type": "string",
      "enum": [
        "floatOnly",
        "floatAndHideOnSwitch",
        "floatAndReserveSpace",
        "dockAware"
      ]
    },
    "cooperativeAppsPayload": {
      "type": "object",
      "required": [
        "apps"
      ],
      "properties": {
        "apps": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/cooperativeAppInfo"
          }
        }
      },
      "additionalProperties": false
    },
    "cooperativeAppInfo": {
      "type": "object",
      "required": [
        "bundleID",
        "behavior",
        "detectedWindowCount"
      ],
      "properties": {
        "bundleID": {
          "type": "string",
          "minLength": 1
        },
        "behavior": {
          "$ref": "#/$defs/cooperativeBehavior"
        },
        "detectedWindowCount": {
          "type": "integer",
          "minimum": 0
        }
      },
      "additionalProperties": false
    },
    "macroPayload": {
      "$ref": "#/$defs/macroInfo"
    },
    "macrosPayload": {
      "type": "object",
      "required": [
        "macros"
      ],
      "properties": {
        "macros": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/macroInfo"
          }
        }
      },
      "additionalProperties": false
    },
    "scratchpadsPayload": {
      "type": "object",
      "required": [
        "scratchpads"
      ],
      "properties": {
        "scratchpads": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/scratchpadInfo"
          }
        }
      },
      "additionalProperties": false
    },
    "scratchpadInfo": {
      "type": "object",
      "required": [
        "name",
        "isVisible"
      ],
      "properties": {
        "name": {
          "type": "string",
          "minLength": 1
        },
        "bundleID": {
          "type": [
            "string",
            "null"
          ]
        },
        "titleRegex": {
          "type": [
            "string",
            "null"
          ]
        },
        "role": {
          "type": [
            "string",
            "null"
          ]
        },
        "lastVisibleFrame": {
          "anyOf": [
            {
              "$ref": "#/$defs/frame"
            },
            {
              "type": "null"
            }
          ]
        },
        "isVisible": {
          "type": "boolean"
        }
      },
      "additionalProperties": false
    },
    "macroInfo": {
      "type": "object",
      "required": [
        "name",
        "createdAt",
        "recordedDurationMs",
        "commandCount"
      ],
      "properties": {
        "name": {
          "$ref": "#/$defs/macroName"
        },
        "createdAt": {
          "type": [
            "string",
            "number"
          ]
        },
        "recordedDurationMs": {
          "type": "integer",
          "minimum": 0
        },
        "commandCount": {
          "type": "integer",
          "minimum": 0
        }
      },
      "additionalProperties": false
    },
    "restoredWindowsPayload": {
      "type": "object",
      "required": [
        "restoredCount",
        "skippedCount",
        "failedCount"
      ],
      "properties": {
        "restoredCount": {
          "type": "integer",
          "minimum": 0
        },
        "skippedCount": {
          "type": "integer",
          "minimum": 0
        },
        "failedCount": {
          "type": "integer",
          "minimum": 0
        }
      },
      "additionalProperties": false
    },
    "subscriptionPayload": {
      "type": "object",
      "required": [
        "eventKinds"
      ],
      "properties": {
        "eventKinds": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/eventKind"
          }
        }
      },
      "additionalProperties": false
    },
    "versionPayload": {
      "type": "object",
      "required": [
        "protocolVersion",
        "supportedCommands",
        "supportedEventKinds"
      ],
      "properties": {
        "protocolVersion": {
          "$ref": "#/$defs/protocolVersion"
        },
        "supportedCommands": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/commandName"
          }
        },
        "supportedEventKinds": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/eventKind"
          }
        }
      },
      "additionalProperties": false
    },
    "statePayload": {
      "type": "object",
      "required": [
        "displays",
        "windows"
      ],
      "properties": {
        "displays": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/displayState"
          }
        },
        "windows": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/windowState"
          }
        },
        "focusedWindowID": {
          "anyOf": [
            {
              "$ref": "#/$defs/windowID"
            },
            {
              "type": "null"
            }
          ]
        }
      },
      "additionalProperties": false
    },
    "windowState": {
      "type": "object",
      "required": [
        "windowID",
        "processID",
        "tags",
        "isFloating",
        "isSticky",
        "isPinned",
        "isFullscreen",
        "isOffSpace",
        "frame"
      ],
      "properties": {
        "windowID": {
          "$ref": "#/$defs/windowID"
        },
        "processID": {
          "type": "integer"
        },
        "bundleID": {
          "type": [
            "string",
            "null"
          ]
        },
        "displayID": {
          "anyOf": [
            {
              "$ref": "#/$defs/displayID"
            },
            {
              "type": "null"
            }
          ]
        },
        "tags": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/tagIndex"
          }
        },
        "isFloating": {
          "type": "boolean"
        },
        "isSticky": {
          "type": "boolean"
        },
        "isPinned": {
          "type": "boolean"
        },
        "isFullscreen": {
          "type": "boolean"
        },
        "isOffSpace": {
          "type": "boolean"
        },
        "engineOverride": {
          "type": [
            "string",
            "null"
          ]
        },
        "layoutOrder": {
          "type": [
            "integer",
            "null"
          ]
        },
        "frame": {
          "$ref": "#/$defs/frame"
        },
        "title": {
          "type": [
            "string",
            "null"
          ]
        },
        "role": {
          "type": [
            "string",
            "null"
          ]
        },
        "subrole": {
          "type": [
            "string",
            "null"
          ]
        }
      },
      "additionalProperties": false
    },
    "displayState": {
      "type": "object",
      "required": [
        "displayID",
        "activeTags",
        "tagEngines",
        "mruHistory"
      ],
      "properties": {
        "displayID": {
          "$ref": "#/$defs/displayID"
        },
        "activeTags": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/tagIndex"
          }
        },
        "tagEngines": {
          "type": "array",
          "items": {
            "$ref": "#/$defs/tagEngineBinding"
          }
        },
        "mruHistory": {
          "type": "array",
          "items": {
            "type": "array",
            "items": {
              "$ref": "#/$defs/tagIndex"
            }
          }
        }
      },
      "additionalProperties": false
    },
    "tagEngineBinding": {
      "type": "object",
      "required": [
        "tag",
        "engineID"
      ],
      "properties": {
        "tag": {
          "$ref": "#/$defs/tagIndex"
        },
        "engineID": {
          "$ref": "#/$defs/layoutEngineID"
        }
      },
      "additionalProperties": false
    },
    "frame": {
      "type": "object",
      "required": [
        "x",
        "y",
        "width",
        "height"
      ],
      "properties": {
        "x": {
          "type": "number"
        },
        "y": {
          "type": "number"
        },
        "width": {
          "type": "number"
        },
        "height": {
          "type": "number"
        }
      },
      "additionalProperties": false
    },
    "errorPayload": {
      "type": "object",
      "required": [
        "code",
        "message"
      ],
      "properties": {
        "code": {
          "type": "string"
        },
        "message": {
          "type": "string"
        }
      },
      "additionalProperties": false
    },
    "eventEnvelope": {
      "type": "object",
      "required": [
        "version",
        "event"
      ],
      "properties": {
        "version": {
          "$ref": "#/$defs/protocolVersion"
        },
        "event": {
          "$ref": "#/$defs/event"
        }
      },
      "additionalProperties": false
    },
    "event": {
      "type": "object",
      "properties": {
        "axPermission": {
          "$ref": "#/$defs/axPermissionEvent"
        },
        "engine": {
          "$ref": "#/$defs/engineEvent"
        },
        "focus": {
          "$ref": "#/$defs/focusEvent"
        },
        "focusBlocked": {
          "$ref": "#/$defs/focusBlockedEvent"
        },
        "fullscreen": {
          "$ref": "#/$defs/fullscreenEvent"
        },
        "rawAction": {
          "$ref": "#/$defs/rawActionEvent"
        },
        "runtimeError": {
          "$ref": "#/$defs/runtimeErrorEvent"
        },
        "space": {
          "$ref": "#/$defs/spaceEvent"
        }
      },
      "oneOf": [
        {
          "required": [
            "axPermission"
          ]
        },
        {
          "required": [
            "engine"
          ]
        },
        {
          "required": [
            "focus"
          ]
        },
        {
          "required": [
            "focusBlocked"
          ]
        },
        {
          "required": [
            "fullscreen"
          ]
        },
        {
          "required": [
            "rawAction"
          ]
        },
        {
          "required": [
            "runtimeError"
          ]
        },
        {
          "required": [
            "space"
          ]
        }
      ],
      "additionalProperties": false
    },
    "engineEvent": {
      "type": "object",
      "additionalProperties": true
    },
    "axPermissionEvent": {
      "type": "object",
      "required": [
        "status"
      ],
      "properties": {
        "status": {
          "type": "string",
          "enum": [
            "trusted",
            "missing"
          ]
        }
      },
      "additionalProperties": false
    },
    "focusEvent": {
      "type": "object",
      "properties": {
        "focusedWindowID": {
          "anyOf": [
            {
              "$ref": "#/$defs/windowID"
            },
            {
              "type": "null"
            }
          ]
        },
        "displayID": {
          "$ref": "#/$defs/displayID"
        },
        "tagMask": {
          "type": "integer",
          "minimum": 0
        }
      },
      "additionalProperties": false
    },
    "focusBlockedEvent": {
      "type": "object",
      "required": [
        "processID",
        "reason"
      ],
      "properties": {
        "processID": {
          "type": "integer"
        },
        "bundleID": {
          "type": [
            "string",
            "null"
          ]
        },
        "reason": {
          "type": "string"
        }
      },
      "additionalProperties": false
    },
    "fullscreenEvent": {
      "type": "object",
      "required": [
        "windowID",
        "didEnter"
      ],
      "properties": {
        "windowID": {
          "$ref": "#/$defs/windowID"
        },
        "didEnter": {
          "type": "boolean"
        }
      },
      "additionalProperties": false
    },
    "rawActionEvent": {
      "type": "object",
      "required": [
        "label",
        "status",
        "exit",
        "stdoutHead",
        "stderrHead",
        "elapsedMs"
      ],
      "properties": {
        "label": {
          "type": "string"
        },
        "status": {
          "$ref": "#/$defs/rawActionStatus"
        },
        "exit": {
          "type": [
            "integer",
            "null"
          ]
        },
        "stdoutHead": {
          "type": "string",
          "maxLength": 4096
        },
        "stderrHead": {
          "type": "string",
          "maxLength": 4096
        },
        "elapsedMs": {
          "type": "integer",
          "minimum": 0
        }
      },
      "additionalProperties": false
    },
    "runtimeErrorEvent": {
      "type": "object",
      "required": [
        "timestamp",
        "message"
      ],
      "properties": {
        "timestamp": {
          "type": "string",
          "format": "date-time"
        },
        "message": {
          "type": "string"
        }
      },
      "additionalProperties": false
    },
    "spaceEvent": {
      "type": "object",
      "required": [
        "windowID",
        "fromDisplayID",
        "action"
      ],
      "properties": {
        "windowID": {
          "$ref": "#/$defs/windowID"
        },
        "fromDisplayID": {
          "anyOf": [
            {
              "$ref": "#/$defs/displayID"
            },
            {
              "type": "null"
            }
          ]
        },
        "action": {
          "$ref": "#/$defs/spaceDriftAction"
        }
      },
      "additionalProperties": false
    }
  }
}
```
<!-- ipc-schema:end -->

## Examples

Request:

```json
{"version":1,"command":{"name":"focus","arguments":{"direction":"next"}}}
```

Response:

```json
{"version":1,"status":"ok","result":{"name":"acknowledged","payload":{"message":"focused"}}}
```

Restore windows response:

```json
{"version":1,"status":"ok","result":{"name":"restored-windows","payload":{"restoredCount":1,"skippedCount":0,"failedCount":0}}}
```

Event line:

```json
{"version":1,"event":{"engine":{"arranged":{"displayID":1,"engineID":{"rawValue":"floating"},"placementCount":3,"appliedPlacementCount":2}}}}
```

Focus event line:

```json
{"version":1,"event":{"focus":{"focusedWindowID":42,"displayID":1,"tagMask":1}}}
```

Set focus policy command:

```json
{"version":2,"command":{"name":"set-focus-policy","arguments":{"allowedBundleIDs":["com.apple.Terminal"],"maxEventsPerSecond":20,"minHumanIntervalMilliseconds":80}}}
```

Focus blocked event line:

```json
{"version":2,"event":{"focusBlocked":{"processID":1234,"bundleID":"com.example.Noisy","reason":"rate-limited"}}}
```

AX permission event line:

```json
{"version":1,"event":{"axPermission":{"status":"missing"}}}
```

Fullscreen event line:

```json
{"version":2,"event":{"fullscreen":{"windowID":42,"didEnter":true}}}
```

Space drift event line:

```json
{"version":2,"event":{"space":{"windowID":42,"fromDisplayID":1,"action":"marked-off-space"}}}
```

Raw action event line:

```json
{"version":2,"event":{"rawAction":{"label":"safari","status":"completed","exit":0,"stdoutHead":"","stderrHead":"","elapsedMs":18}}}
```

List cooperative apps response:

```json
{"version":2,"status":"ok","result":{"name":"cooperative-apps","payload":{"apps":[{"bundleID":"com.apple.finder","behavior":"floatOnly","detectedWindowCount":1}]}}}
```

Explain window response:

```json
{"version":2,"status":"ok","result":{"name":"rule-explanation","payload":{"windowID":42,"ruleID":null,"traces":[{"ruleID":"11111111-2222-3333-8444-555555555555","matched":true,"bundleIDMatched":true,"titleMatched":null,"roleMatched":null,"subroleMatched":null,"predicateMatched":null}],"finalApply":{"tagMask":null,"engineOverride":{"rawValue":"floating"},"floating":true,"sticky":null,"pinned":null}}}}
```

Macro list response:

```json
{"version":2,"status":"ok","result":{"name":"macros","payload":{"macros":[{"name":"workflow1","createdAt":"2026-06-30T05:00:00Z","recordedDurationMs":250,"commandCount":3}]}}}
```
