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
`snap-window` places a window in a safe-layout display zone and makes it floating by default so the next
tiling arrange does not immediately overwrite the user placement. `dispatch-gesture` resolves a configured
DSL gesture binding for external tools such as BetterTouchTool or Hammerspoon and executes the resulting
runtime action.
`manual-preselect` and `bsp-tree` expose engine tree controls for manual/BSP layouts; both return structured
`unsupported_engine_command` errors when the active engine does not match the requested tree operation.

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
            "restored-windows",
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

AX permission event line:

```json
{"version":1,"event":{"axPermission":{"status":"missing"}}}
```
