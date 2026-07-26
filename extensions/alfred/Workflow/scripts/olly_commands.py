#!/usr/bin/env python3
import json
import os
import subprocess
import sys

COMMANDS = [
    ("state", "State", "Print current olly state", ["state"]),
    ("reload", "Reload Config", "Reload Config.swift", ["reload"]),
    ("focus-next", "Focus Next", "Focus the next window", ["focus", "next"]),
    ("focus-previous", "Focus Previous", "Focus the previous window", ["focus", "previous"]),
    ("focus-left", "Focus Left", "Focus the window to the left", ["focus", "left"]),
    ("focus-right", "Focus Right", "Focus the window to the right", ["focus", "right"]),
    ("move-window-left", "Move Window Left", "Move focused window left", ["move-window", "left"]),
    ("move-window-right", "Move Window Right", "Move focused window right", ["move-window", "right"]),
    ("switch-tag-1", "Switch to Tag 1", "Switch active display to tag 1", ["switch-tag", "0"]),
    ("switch-tag-2", "Switch to Tag 2", "Switch active display to tag 2", ["switch-tag", "1"]),
    ("move-to-tag-1", "Move Window to Tag 1", "Move focused window to tag 1", ["move-to-tag", "0"]),
    ("move-to-tag-2", "Move Window to Tag 2", "Move focused window to tag 2", ["move-to-tag", "1"]),
    ("set-engine-niri", "Set Engine: NiriScroll", "Bind active tag to niri-scroll", ["set-engine", "niri-scroll"]),
    ("set-engine-bsp", "Set Engine: BSP", "Bind active tag to bsp", ["set-engine", "bsp"]),
    ("cycle-engine", "Cycle Engine", "Cycle active tag layout engine", ["cycle-engine"]),
]


def command_by_id():
    return {command_id: argv for command_id, _, _, argv in COMMANDS}


def score(command, query):
    if not query:
        return 100
    haystack = " ".join([command[0], command[1], command[2]] + command[3]).lower()
    query = query.lower()
    if query in haystack:
        return 100 - haystack.index(query)
    cursor = 0
    for character in query:
        found = haystack.find(character, cursor)
        if found < 0:
            return None
        cursor = found + 1
    return len(query)


def filter_commands(query):
    items = []
    for command in COMMANDS:
        current_score = score(command, query)
        if current_score is None:
            continue
        command_id, title, subtitle, argv = command
        items.append((current_score, {
            "uid": command_id,
            "title": title,
            "subtitle": f"ollyctl {' '.join(argv)} - {subtitle}",
            "arg": command_id,
            "autocomplete": title,
            "valid": True,
        }))
    items.sort(key=lambda item: (-item[0], item[1]["title"]))
    return {"items": [item for _, item in items]}


def run_command(command_id):
    commands = command_by_id()
    if command_id not in commands:
        print(f"unknown olly command id: {command_id}", file=sys.stderr)
        return 2
    executable = os.environ.get("OLLYCTL", "ollyctl")
    completed = subprocess.run([executable] + commands[command_id], check=False)
    return completed.returncode


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "filter"
    value = sys.argv[2] if len(sys.argv) > 2 else ""
    if mode == "filter":
        print(json.dumps(filter_commands(value)))
        return 0
    if mode == "run":
        return run_command(value)
    print(f"unknown mode: {mode}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
