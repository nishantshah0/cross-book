#!/usr/bin/env python3
"""Publishes finished AI-cheater runs for the public surveillance desk on GitHub Pages.

    python3 tools/agent/publish.py target/agent/20261116-170055        # add or update runs
    python3 tools/agent/publish.py --remove 20261116-161356-dry        # take one down

Each run is copied to docs/red-team/runs/<name>.json, and docs/red-team/runs.json lists
them, in the same shape the app's /api/red-team endpoints serve. The Pages workflow builds
the desk (npm run build:pages) and serves these files beside it, so the public page shows
exactly what the app would, with a Replay button. Standard library only.
"""

import argparse
import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "docs", "red-team")
NAME = re.compile(r"^\d{8}-\d{6}(-[a-z0-9]+)*$")


def load(run_dir):
    """A run as the app serves it: result.json (it must have finished), with its name and status."""
    name = os.path.basename(os.path.normpath(run_dir))
    if not NAME.match(name):
        sys.exit("%s isn't a run folder" % run_dir)
    path = os.path.join(run_dir, "result.json")
    if not os.path.exists(path):
        sys.exit("%s hasn't finished: no result.json" % run_dir)
    with open(path) as f:
        run = json.load(f)
    run["name"] = name
    run["status"] = {"phase": "done", "updatedAt": os.path.getmtime(path)}
    run.setdefault("budget", max(0, len(run["trials"]) - 1))
    run.setdefault("searcher", "claude" if run.get("model") else "scripted")
    return run


def summary(run, updated_ms):
    """Matches RedTeamRuns.Summary in the app."""
    cheater = (run.get("heldOut") or {}).get("cheater")
    built_in = (run.get("heldOut") or {}).get("built-in")
    return {"name": run["name"], "phase": run["status"]["phase"], "model": run.get("model"), "via": run.get("via"),
            "searcher": run.get("searcher"), "blind": bool(run.get("blind")),
            "detector": run.get("detector", "spoofing-rules"), "trials": max(0, len(run["trials"]) - 1),
            "budget": run.get("budget", 0), "firstSuccess": run.get("firstSuccess"),
            "flaggedHeldOut": cheater["flaggedSpoofers"] if cheater else None,
            "spoofersHeldOut": 2 * len(run["heldOutSeeds"]) if cheater else None,
            "lift": cheater.get("lift") if cheater else None, "builtInLift": built_in.get("lift") if built_in else None,
            "updatedAt": updated_ms}


def index():
    runs = []
    for path in sorted(glob.glob(os.path.join(OUT, "runs", "*.json")), reverse=True):
        with open(path) as f:
            run = json.load(f)
        runs.append(summary(run, int(run["status"].get("updatedAt", 0) * 1000)))
    with open(os.path.join(OUT, "runs.json"), "w") as f:
        json.dump(runs, f, indent=1)
    print("docs/red-team/runs.json lists %d runs" % len(runs))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("runs", nargs="*", help="finished run folders under target/agent/")
    parser.add_argument("--remove", nargs="*", default=[], metavar="NAME", help="published runs to take down")
    args = parser.parse_args()
    os.makedirs(os.path.join(OUT, "runs"), exist_ok=True)
    for run_dir in args.runs:
        run = load(run_dir)
        with open(os.path.join(OUT, "runs", run["name"] + ".json"), "w") as f:
            json.dump(run, f, indent=1)
        print("published %s" % run["name"])
    for name in args.remove:
        path = os.path.join(OUT, "runs", name + ".json")
        if NAME.match(name) and os.path.exists(path):
            os.remove(path)
            print("removed %s" % name)
    index()


if __name__ == "__main__":
    main()
