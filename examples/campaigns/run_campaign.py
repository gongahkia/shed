#!/usr/bin/env python3
"""Run a bounded campaign through the same tools available to MCP agents."""
import argparse
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default="examples/campaigns/station.json")
    parser.add_argument("--output", default="out/campaign-station")
    parser.add_argument("--binary", default="target/release/chiyoda")
    parser.add_argument("--plan-only", action="store_true")
    parser.add_argument("--replay", action="store_true", help="reconstruct trials and image pixels during additional final verification")
    args = parser.parse_args()
    workspace = Path(__file__).resolve().parents[2]

    def relative(path):
        path = Path(path)
        return str((path if path.is_absolute() else workspace / path).resolve().relative_to(workspace))

    binary = Path(args.binary)
    if not binary.is_absolute():
        binary = workspace / binary

    def call(tool, arguments):
        result = subprocess.run(
            [str(binary), "agent", "call", tool, "--input", "-", "--workspace", str(workspace)],
            input=json.dumps(arguments), capture_output=True, text=True, check=False,
        )
        try:
            envelope = json.loads(result.stdout)
        except json.JSONDecodeError as error:
            raise RuntimeError(result.stderr or result.stdout) from error
        if not envelope["ok"] or result.returncode:
            raise RuntimeError(envelope)
        return envelope["result"]

    config = relative(args.config)
    plan = call("chiyoda_campaign_plan", {"config": config})
    if args.plan_only or not plan["runnable"]:
        print(json.dumps(plan, indent=2))
        if not plan["runnable"]:
            raise SystemExit(1)
        return
    output = relative(args.output)
    result = call("chiyoda_campaign_run", {"config": config, "expected_plan_hash": plan["plan_hash"], "output": output})
    verification = call("chiyoda_campaign_verify", {"campaign": output, "replay": args.replay})
    print(json.dumps({"output": output, "summary": result, "verification": verification}, indent=2))


if __name__ == "__main__":
    main()
