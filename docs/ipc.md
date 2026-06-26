# IPC Protocol

Status: v1. Schema version: `1`.

Olly IPC uses a Unix-domain socket and newline-delimited JSON. The default socket is
`$XDG_RUNTIME_DIR/olly.sock`; on macOS without `XDG_RUNTIME_DIR`, olly falls back to
`~/.config/olly/olly.sock`.

Each client writes one JSON request per line. Normal commands receive one JSON response line.
`subscribe-events` upgrades the connection to an event stream after an `ok` response; `ollyctl events`
consumes that response and then prints event lines.

## Commands

- `state`
- `focus`
- `move-window`
- `swap`
- `switch-tag`
- `move-to-tag`
- `toggle-tag`
- `set-engine`
- `cycle-engine`
- `tag-add`
- `tag-remove`
- `reload`
- `subscribe-events`
- `version`

## Schema

The schema block below is tested against the Swift IPC constants. Breaking wire changes must bump
`protocolVersion`, `$id`, and this document.

<!-- ipc-schema:start -->
```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://olly.dev/schemas/ipc.v1.json",
  "title": "olly IPC JSON line protocol v1",
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
      "const": 1
    },
    "commandName": {
      "type": "string",
      "enum": [
        "state",
        "focus",
        "move-window",
        "swap",
        "switch-tag",
        "move-to-tag",
        "toggle-tag",
        "set-engine",
        "cycle-engine",
        "tag-add",
        "tag-remove",
        "reload",
        "subscribe-events",
        "version"
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
        "display",
        "engine",
        "focus",
        "tag",
        "window"
      ]
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
            "state",
            "subscribed",
            "version"
          ]
        },
        "payload": {
          "type": "object"
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
      "required": [
        "engine"
      ],
      "properties": {
        "engine": {
          "$ref": "#/$defs/engineEvent"
        }
      },
      "additionalProperties": false
    },
    "engineEvent": {
      "type": "object",
      "additionalProperties": true
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

Event line:

```json
{"version":1,"event":{"engine":{"arranged":{"displayID":1,"engineID":{"rawValue":"floating"},"placementCount":3,"appliedPlacementCount":2}}}}
```
