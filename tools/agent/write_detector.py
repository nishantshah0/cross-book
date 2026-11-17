"""Has Claude write a spoofing detector blind, as an outside detector (detectors/README.md).

Claude gets the input/output contract and the task, nothing else: no access to this
repository, the bots, the spoofer or the built-in detectors (Claude Code's tools,
settings and MCP servers are all off, and it runs in an empty directory). Its program is
saved as written, with the prompt and model in a header, so the detector is someone
else's in a way mine can't be. If the program fails on a small test log, the error goes
back once and the retry is recorded too.

    python3 tools/agent/write_detector.py            # writes detectors/claude_written.py
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MODEL = "claude-opus-5-5"
OUT = os.path.join(ROOT, "detectors", "claude_written.py")

CONTRACT = open(os.path.join(ROOT, "detectors", "README.md")).read()
CONTRACT = CONTRACT[CONTRACT.index("## Input (stdin)"):CONTRACT.index("## Running one")].strip()

PROMPT = """Write a market-surveillance detector for spoofing and layering on a simulated equity exchange.

The exchange is a continuous limit order book with price-time priority across a dozen stocks, and about 140 accounts trade for an hour. Honest participants include market makers that quote both sides and re-quote often, and other traders who cancel orders for ordinary reasons. Some accounts may be spoofing. You will not see the market before your detector runs on it, and nobody will tune it for you afterwards. False alarms are costly: an analyst has to review every alert.

Your detector is a program. It reads one market's order log as JSON on stdin and prints its alerts as JSON on stdout. The contract:

""" + CONTRACT + """

Requirements: a single Python 3 program using only the standard library; deterministic; it must handle about 50,000 orders in under a minute. Use your own judgment about what spoofing and layering look like in order data and how to tell them from market making. In `notes`, say in a few sentences what your detector looks for and why."""

SCHEMA = {"type": "object", "properties": {
    "program": {"type": "string", "description": "the complete Python program"},
    "notes": {"type": "string", "description": "what the detector looks for and why"}},
    "required": ["program", "notes"], "additionalProperties": False}


def ask(prompt, session, resume, workdir):
    binary = shutil.which("claude") or os.path.expanduser("~/.local/bin/claude")
    command = [binary, "-p", prompt, "--output-format", "json", "--tools", "", "--json-schema", json.dumps(SCHEMA),
               "--model", MODEL, "--effort", "high", "--setting-sources", "", "--strict-mcp-config"]
    command += ["--resume", session] if resume else ["--session-id", session]
    done = subprocess.run(command, cwd=workdir, capture_output=True, text=True, timeout=1_800)
    reply = json.loads(done.stdout)
    out = reply.get("structured_output")
    if reply.get("is_error") or not isinstance(out, dict):
        sys.exit("claude -p gave no program: %s" % str(reply.get("result"))[-2_000:])
    return out


def smoke_test(path):
    """A tiny log, only to see the program runs and prints a list; its verdict isn't checked."""
    def order(i, account, side, quantity, placed, ended, end, filled):
        return dict(id=i, accountId=account, symbol="T", side=side, type="LIMIT", quantity=quantity,
                    limitPrice=100.0, firstSeq=i, placedAt="2026-01-05T14:30:%02dZ" % placed,
                    endedAt=None if ended is None else "2026-01-05T14:30:%02dZ" % ended, end=end, filled=filled)
    log = {"orders": [order(1, 1, "BUY", 40, 0, 4, "CANCELLED", 0), order(2, 1, "SELL", 10, 1, 3, "FILLED", 10),
                      order(3, 2, "BUY", 10, 0, 5, "FILLED", 10), order(4, 2, "SELL", 10, 0, None, None, 0)],
           "usernames": {"1": "trader_0001", "2": "trader_0002"}}
    done = subprocess.run([sys.executable, path], input=json.dumps(log), capture_output=True, text=True, timeout=120)
    if done.returncode != 0:
        return done.stderr[-3_000:] or "exited with %d" % done.returncode
    try:
        alerts = json.loads(done.stdout)
        assert isinstance(alerts, list)
    except (ValueError, AssertionError):
        return "stdout was not a JSON list: %r" % done.stdout[:500]
    return None


def main():
    import uuid
    session = str(uuid.uuid4())
    workdir = tempfile.mkdtemp(prefix="blind-detector-")
    rounds = []
    out = ask(PROMPT, session, False, workdir)
    rounds.append(("prompt", PROMPT))
    for attempt in range(2):
        with open(OUT, "w") as f:
            f.write(out["program"])
        error = smoke_test(OUT)
        if error is None:
            break
        if attempt == 1:
            sys.exit("the program still fails after one retry:\n" + error)
        retry = "Your program failed on a small test log:\n\n" + error + "\n\nFix it and send the whole program again."
        rounds.append(("retry", retry))
        out = ask(retry, session, True, workdir)
    header = ['"""Written by %s, blind, on %s, by tools/agent/write_detector.py. Saved as' % (
        MODEL, time.strftime("%Y-%m-%d")), "written; nothing below the line was edited by hand.", "",
        "What Claude said it looks for:", "", out["notes"].strip(), "", "The prompt it got:", ""]
    for kind, text in rounds:
        header += ["--- %s ---" % kind, text.replace('"""', "'''"), ""]
    header += ['"""', "# " + "-" * 76, ""]
    with open(OUT, "w") as f:
        f.write("\n".join(header) + out["program"])
    print("wrote %s (%d rounds)\n\n%s" % (os.path.relpath(OUT, ROOT), len(rounds), out["notes"]))


if __name__ == "__main__":
    main()
