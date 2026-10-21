#!/usr/bin/env python3
"""Run the checked-in experiment through the agent interface; standard library only."""

import argparse
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", default="target/release/chiyoda")
    parser.add_argument("--output", default="out/agent-station")
    parser.add_argument("--replay", action="store_true", help="also reconstruct during final verification")
    args = parser.parse_args()
    workspace = Path(__file__).resolve().parents[2]
    binary = Path(args.binary)
    if not binary.is_absolute():
        binary = workspace / binary

    def call(tool, arguments):
        process = subprocess.run(
            [str(binary), "agent", "call", tool, "--input", "-", "--workspace", str(workspace)],
            input=json.dumps(arguments), text=True, capture_output=True, check=False,
        )
        try:
            envelope = json.loads(process.stdout)
        except json.JSONDecodeError as error:
            raise RuntimeError(process.stderr or process.stdout) from error
        if not envelope["ok"] or process.returncode:
            raise RuntimeError(envelope)
        return envelope["result"]

    experiment = json.loads((workspace / "examples/experiments/station.plan.json").read_text())
    plan = call("chiyoda_plan", experiment)
    if not plan["runnable"]:
        raise RuntimeError(plan)
    result = call("chiyoda_run", {
        "experiment": experiment,
        "expected_input_hash": plan["input_hash"],
        "output": args.output,
    })
    pairs = call("chiyoda_query", {
        "experiment": args.output, "view": "comparisons", "arm": "announcement", "limit": 5,
    })
    verified = call("chiyoda_verify", {"experiment": args.output, "replay": args.replay})
    print(json.dumps({
        "output": args.output,
        "report_hash": result["report_hash"],
        "arms": [{
            "id": arm["id"], "checks_passed": arm["evaluation"]["passed"],
            "metrics": arm["evaluation"]["metrics"], "findings": arm["findings"],
        } for arm in result["arms"]],
        "announcement_sample": pairs,
        "verification": verified,
    }, indent=2))


if __name__ == "__main__":
    main()
