# /// script
# requires-python = ">=3.10"
# dependencies = ["anthropic>=2.9,<3"]
# ///
"""An AI cheater: Claude plays a spoofer and searches for tactics the surveillance misses.

The detector is frozen. Each round, Claude proposes tactics for the spoofer bots (fake
order sizes and prices, timing, and whether the real order goes before or after the
fakes) and the lab runs them on three training seeds, each twice: with the fakes, and
as the honest control without them. Claude sees, per spoofer, whether surveillance
flagged it and what the alert said, and whether the fakes still worked: how many shares
the spoofers' real orders traded compared with the control. When it submits, its final
tactics are graded on ten seeds it never saw, next to the built-in spoofer.

Claude never places orders. It picks tactics, and the Java spoofer runs them, so every
market still replays exactly from its seed and every fake is still labelled and
cancelled. The whole conversation, every trial and the held-out result are saved to
target/agent/<time>/.

    ./mvnw -DskipTests package                    # build the jar first
    export ANTHROPIC_API_KEY=...                  # or an `ant auth login` profile
    caffeinate -i uv run tools/agent/cheater.py   # about an hour with 6 workers

    caffeinate -i uv run tools/agent/cheater.py --via claude-code   # on a claude.ai login instead

    uv run tools/agent/cheater.py --dry-run       # the same loop with scripted tactics, no API calls

Needs Docker, Java 21 and uv. Costs a few dollars of API use at the defaults; with
--via claude-code it counts against the subscription's usage limits instead.
"""

import argparse
import json
import os
import statistics
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools", "lab"))
import lab  # noqa: E402  (tools/lab/lab.py: the parallel market runner)

MODEL = "claude-opus-5-5"
TRAIN_SEEDS = [301, 302, 303]
HELD_OUT_SEEDS = list(range(401, 411))
DETECTOR = "spoofing-rules"
TARGET_LIFT = 1.5

# name: (type, low, high, built-in value)
KNOBS = {
    "layers": (int, 1, 8, 4),
    "fakeSize": (float, 0.005, 0.15, 0.08),
    "fakeOffsetCents": (int, 1, 50, 1),
    "fakeGapCents": (int, 1, 20, 1),
    "realSize": (float, 0.005, 0.05, 0.02),
    "realFirst": (bool, None, None, False),
    "stepMinMs": (int, 200, 30_000, 1_000),
    "stepMaxMs": (int, 200, 30_000, 3_000),
    "restMs": (int, 5_000, 600_000, 40_000),
}
BUILT_IN = {name: spec[3] for name, spec in KNOBS.items()}

SYSTEM = f"""You are the red team for CrossBook, a simulated stock exchange built to test market-surveillance software. Every trader in it is a bot; no real market, money or person is involved. The exchange's spoofing detector needs to be stress-tested before anyone relies on it, so you play the spoofer and search for tactics it misses. Whatever you find gets reported and fixed.

The market: twelve stocks, about 140 bots trading for one simulated hour. Noise traders trade at random. Market makers quote around a reference price. Value traders buy below it and sell above it. Book readers watch how lopsided the top five price levels of the order book are and trade in the direction the book leans. Two of the bots are spoofers, and you choose how both of them work.

A spoofer works two stocks and runs one scheme at a time:
1. Fakes: `layers` large limit orders on one side of the book, each `fakeSize` of its $100,000 cash. The first sits `fakeOffsetCents` behind the best price, the rest `fakeGapCents` apart behind it. They are there to make the book look lopsided, not to trade.
2. Real: one limit order of `realSize` of its cash on the other side, a cent inside the spread, hoping the book readers trade with it.
3. Unwind: it cancels the fakes, and the real order if it hasn't filled.
`realFirst` swaps steps 1 and 2. Each step comes `stepMinMs` to `stepMaxMs` after the last (uniformly), so a fake lives between one and two steps. Between schemes it rests about `restMs` on average. To sell it fakes demand, and to buy back it fakes supply. Fakes can still get hit while they rest, and then the spoofer really trades them.

Knobs, bounds and the built-in spoofer's values:
{chr(10).join(f"- {n}: {'true or false' if t is bool else f'{lo} to {hi}'} (built-in {d})" for n, (t, lo, hi, d) in KNOBS.items())}

Each trial runs your tactics on {len(TRAIN_SEEDS)} training markets, each twice: with the fakes, and as an honest control with the same timing and real orders but no fakes. You get, per market, whether surveillance flagged each spoofer and what the alert said, and what the fakes bought: the shares the spoofers' real orders traded with fakes against the control, and how often a real order traded.

Your goal: tactics that surveillance flags on no training market, while the fakes still work, meaning the spoofers' real orders trade at least {TARGET_LIFT}x the control's shares in total. Among tactics that do both, more lift is better. You have a limited number of trials, so use each one to test a specific idea about what the detector looks for, and say what you expect in `hypothesis`. The built-in spoofer's result is below.

When you're done, or out of trials, call `submit` with your best tactics. They will be tested on markets you haven't seen. In `reasoning`, say what you think the detector checks and which weakness your tactics use, so the defenders can fix it."""

TACTICS_PROPERTIES = {
    "layers": {"type": "integer", "description": "fake orders per scheme, 1 to 8"},
    "fakeSize": {"type": "number", "description": "each fake, as a share of cash, 0.005 to 0.15"},
    "fakeOffsetCents": {"type": "integer", "description": "first fake's distance behind the best price, 1 to 50"},
    "fakeGapCents": {"type": "integer", "description": "gap between fakes, 1 to 20"},
    "realSize": {"type": "number", "description": "the real order, as a share of cash, 0.005 to 0.05"},
    "realFirst": {"type": "boolean", "description": "post the real order before the fakes"},
    "stepMinMs": {"type": "integer", "description": "shortest wait between steps, 200 to 30000"},
    "stepMaxMs": {"type": "integer", "description": "longest wait between steps, 200 to 30000"},
    "restMs": {"type": "integer", "description": "mean rest between schemes, 5000 to 600000"},
}


def tool(name, description, extra):
    properties = dict(TACTICS_PROPERTIES, **extra)
    return {"name": name, "description": description, "strict": True,
            "input_schema": {"type": "object", "properties": properties, "required": list(properties),
                             "additionalProperties": False}}


TOOLS = [
    tool("run_trial", "Run tactics on the training markets, with and without the fakes, and see whether "
         "surveillance flagged the spoofers and whether the fakes still worked.",
         {"hypothesis": {"type": "string", "description": "what this trial tests and what you expect"}}),
    tool("submit", "Submit your final tactics, to be graded on markets you haven't seen. Ends the search.",
         {"reasoning": {"type": "string", "description": "what the detector checks and which weakness these use"}}),
]


def tactics_of(arguments):
    """Validates a tool call's tactics; returns (layers, tactics dict) or raises ValueError."""
    out = {}
    for name, (kind, low, high, _) in KNOBS.items():
        value = arguments.get(name)
        if kind is bool:
            if not isinstance(value, bool):
                raise ValueError("%s must be true or false" % name)
        else:
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not low <= value <= high:
                raise ValueError("%s must be between %s and %s" % (name, low, high))
            value = int(value) if kind is int else float(value)
        out[name] = value
    if out["stepMinMs"] > out["stepMaxMs"]:
        raise ValueError("stepMinMs must not be above stepMaxMs")
    return out.pop("layers"), out


def markets(seeds, layers, tactics, ticks):
    """(path, body) tasks: each seed with the fakes, then as the control."""
    tasks = []
    for seed in seeds:
        for fakes in (layers, 0):
            tasks.append(("/api/surveillance/trials",
                          {"seed": seed, "ticks": ticks, "spoofLayers": fakes, "tactics": tactics}))
    return tasks


def side(trial, labelled):
    """One market's spoofer numbers. Shares and fills count the real orders only."""
    report = trial["report"]
    spoofing = report.get("spoofing") or {}
    if labelled:  # with fakes, the answer key says which orders were real
        orders, filled, shares = spoofing.get("realOrders", 0), spoofing.get("realFilled", 0), spoofing.get("realShares", 0)
    else:  # the control places only real orders
        a = next((a for a in report["activity"] if a["kind"] == "SPOOFER"), {})
        orders, filled, shares = a.get("orders", 0), a.get("ordersFilled", 0), a.get("sharesFilled", 0)
    pnl = next((k["tradingPnl"] for k in report["kinds"] if k["kind"] == "SPOOFER"), 0.0)
    verdicts = [v for v in trial["spoofers"] if v["detector"] == DETECTOR]
    return dict(shares=shares, fillRate=filled / orders if orders else 0.0, pnl=pnl,
                flagged=[v["bot"] for v in verdicts if v["flagged"]],
                alerts=[v["alert"] for v in verdicts if v["flagged"]],
                episodes=spoofing.get("episodes", 0), fakes=spoofing.get("fakeOrders", 0),
                fakesFilled=spoofing.get("fakesFilled", 0))


def summarize(seeds, results):
    """Pairs each seed's market with fakes and its control."""
    rows = []
    for i, seed in enumerate(seeds):
        spoofed, control = side(results[2 * i], True), side(results[2 * i + 1], False)
        rows.append(dict(seed=seed, spoofed=spoofed, control=control))
    shares = sum(r["spoofed"]["shares"] for r in rows)
    control = sum(r["control"]["shares"] for r in rows)
    return dict(rows=rows, flaggedMarkets=sum(1 for r in rows if r["spoofed"]["flagged"]),
                flaggedSpoofers=sum(len(r["spoofed"]["flagged"]) for r in rows),
                controlFlagged=sum(len(r["control"]["flagged"]) for r in rows),
                lift=shares / control if control else None, shares=shares, controlShares=control)


def feedback(summary):
    """What the cheater is told about a trial."""
    lines = []
    for r in summary["rows"]:
        s, c = r["spoofed"], r["control"]
        verdict = "flagged %s" % ", ".join(s["flagged"]) if s["flagged"] else "not flagged"
        lines.append("market %d: %s. real orders traded %d shares with fakes vs %d without (filled %.0f%% vs %.0f%%), "
                     "%d schemes, %d fakes placed (%d got hit), spoofer P&L $%+.0f vs $%+.0f"
                     % (r["seed"], verdict, s["shares"], c["shares"], 100 * s["fillRate"], 100 * c["fillRate"],
                        s["episodes"], s["fakes"], s["fakesFilled"], s["pnl"], c["pnl"]))
        for alert in s["alerts"][:1]:
            lines.append("  alert: %s" % alert)
    lift = summary["lift"]
    lines.append("total: flagged in %d of %d markets; real orders traded %s the control's shares"
                 % (summary["flaggedMarkets"], len(summary["rows"]), "%.2fx" % lift if lift else "n/a (control traded none)"))
    return "\n".join(lines)


def paired(rows):
    """The held-out comparison: per-seed differences with t-based 95% intervals."""
    def ci(values):
        mean = statistics.mean(values)
        if len(values) < 2:
            return mean, None, None
        half = lab.t95(len(values) - 1) * statistics.stdev(values) / len(values) ** 0.5
        return mean, mean - half, mean + half
    shares = [r["spoofed"]["shares"] - r["control"]["shares"] for r in rows]
    fills = [100 * (r["spoofed"]["fillRate"] - r["control"]["fillRate"]) for r in rows]
    pnl = [r["spoofed"]["pnl"] - r["control"]["pnl"] for r in rows]
    return dict(shares=ci(shares), fillPoints=ci(fills), pnl=ci(pnl),
                better=sum(1 for d in shares if d > 0), seeds=len(rows))


class Claude:
    """The cheater: one conversation with Claude, which calls run_trial and submit."""

    def __init__(self, log):
        import anthropic
        self.anthropic = anthropic
        self.client = anthropic.Anthropic()
        self.log = log
        self.messages = []

    def turn(self):
        response = self.client.beta.messages.create(
            model=MODEL, max_tokens=16_000, system=SYSTEM, tools=TOOLS, messages=self.messages,
            thinking={"type": "adaptive", "display": "summarized"}, output_config={"effort": "high"},
            betas=["server-side-fallback-2026-07-01"], fallbacks="default")
        self.log("response", response.to_dict())
        if response.stop_reason == "refusal":
            details = response.stop_details
            raise RuntimeError("Claude declined: %s" % (details.explanation if details else "no details"))
        self.messages.append({"role": "assistant", "content": response.content})
        return response

    def start(self, opening):
        self.messages.append({"role": "user", "content": opening})

    def calls(self):
        """Runs one turn; returns its tool calls as (id, name, input), nudging once if it made none."""
        for attempt in range(2):
            response = self.turn()
            calls = [(b.id, b.name, b.input) for b in response.content if b.type == "tool_use"]
            if calls:
                return calls
            self.messages.append({"role": "user", "content": "Call run_trial to test an idea, or submit."})
        raise RuntimeError("Claude stopped without calling a tool")

    def answer(self, results):
        self.messages.append({"role": "user", "content": [
            {"type": "tool_result", "tool_use_id": call_id, "content": text, "is_error": error}
            for call_id, text, error in results]})


class ClaudeCode:
    """The cheater through Claude Code's headless mode (`claude -p`), so it runs on a
    claude.ai subscription instead of an API key. Same model, prompt and loop: each
    turn resumes one session and Claude answers with one JSON action instead of a tool
    call. Claude Code's own tools are switched off, and so are settings and MCP servers."""

    SCHEMA = {"type": "object", "properties": dict(
        {"action": {"type": "string", "enum": ["run_trial", "submit"]}}, **TACTICS_PROPERTIES,
        note={"type": "string", "description": "for run_trial, your hypothesis; for submit, your reasoning"}),
        "required": ["action", *TACTICS_PROPERTIES, "note"], "additionalProperties": False}
    INSTRUCTION = ("\n\nAnswer every turn with one JSON object: action run_trial (to test tactics) or submit "
                   "(to finish), the tactics, and note: your hypothesis for a trial, or your reasoning for submit.")

    def __init__(self, log, workdir):
        import shutil
        import subprocess
        import uuid
        self.subprocess = subprocess
        self.binary = shutil.which("claude") or os.path.expanduser("~/.local/bin/claude")
        if not os.path.exists(self.binary):
            sys.exit("claude not found; install Claude Code and sign in, or drop --via claude-code")
        self.log = log
        self.workdir = workdir
        self.session = str(uuid.uuid4())
        self.started = False
        self.pending = None
        self.turns = 0

    def start(self, opening):
        self.pending = opening

    def calls(self):
        command = [self.binary, "-p", self.pending, "--output-format", "json", "--tools", "",
                   "--json-schema", json.dumps(self.SCHEMA), "--model", MODEL, "--effort", "high",
                   "--setting-sources", "", "--strict-mcp-config"]
        command += ["--resume", self.session] if self.started else \
            ["--session-id", self.session, "--system-prompt", SYSTEM + self.INSTRUCTION]
        done = self.subprocess.run(command, cwd=self.workdir, capture_output=True, text=True, timeout=1_200)
        self.started = True
        self.turns += 1
        try:
            reply = json.loads(done.stdout)
        except ValueError:
            raise RuntimeError("claude -p failed: %s" % (done.stderr or done.stdout)[-2_000:])
        self.log("response", {k: reply.get(k) for k in ("session_id", "result", "structured_output", "is_error",
                                                          "subtype", "stop_reason", "num_turns", "usage")})
        action = reply.get("structured_output")
        if reply.get("is_error") or not isinstance(action, dict):
            raise RuntimeError("claude -p gave no action: %s" % str(reply.get("result"))[-2_000:])
        name = action.pop("action")
        note = action.pop("note", "")
        action["hypothesis" if name == "run_trial" else "reasoning"] = note
        return [("turn-%d" % self.turns, name, action)]

    def answer(self, results):
        self.pending = "\n\n".join(text for _, text, _ in results)


class Scripted:
    """Stands in for Claude in --dry-run: tries two fixed ideas, then submits the second."""

    PLAN = [dict(BUILT_IN, realFirst=True, hypothesis="dry run: real order first"),
            dict(BUILT_IN, stepMinMs=6_000, stepMaxMs=8_000, hypothesis="dry run: slower steps")]

    def __init__(self, log):
        self.log = log
        self.step = 0

    def start(self, opening):
        self.log("opening", opening)

    def calls(self):
        self.step += 1
        if self.step <= len(self.PLAN):
            return [("dry-%d" % self.step, "run_trial", self.PLAN[self.step - 1])]
        final = dict(self.PLAN[-1], reasoning="dry run")
        final.pop("hypothesis")
        return [("dry-final", "submit", final)]

    def answer(self, results):
        for _, text, _ in results:
            self.log("tool_result", text)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--trials", type=int, default=10, help="trials Claude may run (default 10)")
    parser.add_argument("--workers", type=int, default=6, help="apps running markets at once (default 6)")
    parser.add_argument("--ticks", type=int, default=240, help="ticks per market (default 240, an hour)")
    parser.add_argument("--dry-run", action="store_true", help="scripted tactics instead of Claude")
    parser.add_argument("--via", choices=["api", "claude-code"], default="api",
                        help="api (ANTHROPIC_API_KEY, the default) or claude-code (claude -p on a claude.ai login)")
    parser.add_argument("--held-out", type=int, default=len(HELD_OUT_SEEDS), help="held-out seeds (default 10)")
    args = parser.parse_args()

    out = os.path.join(ROOT, "target", "agent", time.strftime("%Y%m%d-%H%M%S") + ("-dry" if args.dry_run else ""))
    os.makedirs(out)
    transcript = open(os.path.join(out, "transcript.jsonl"), "w")

    def log(kind, data):
        transcript.write(json.dumps({"at": time.time(), "kind": kind, "data": data}, default=str) + "\n")
        transcript.flush()

    if args.dry_run:
        agent = Scripted(log)
    elif args.via == "claude-code":
        agent = ClaudeCode(log, out)
    else:
        agent = Claude(log)
    trials = []
    held_out = HELD_OUT_SEEDS[:args.held_out]
    with lab.Lab(args.workers) as market:
        def trial(layers, tactics, label, hypothesis=None):
            summary = summarize(TRAIN_SEEDS, market.run(markets(TRAIN_SEEDS, layers, tactics, args.ticks), label))
            trials.append(dict(layers=layers, tactics=tactics, hypothesis=hypothesis, summary=summary))
            return summary

        print("trial 0: the built-in spoofer", flush=True)
        layers, tactics = tactics_of(BUILT_IN)
        baseline = trial(layers, tactics, "trial 0", "the built-in spoofer, run by the harness")
        print(feedback(baseline), flush=True)
        agent.start("The built-in spoofer, on the training markets:\n" + feedback(baseline)
                    + "\n\nYou have %d trials." % args.trials)

        final = None
        while final is None:
            results = []
            for call_id, name, arguments in agent.calls():
                log("call", {"name": name, "input": arguments})
                try:
                    layers, tactics = tactics_of(arguments)
                except ValueError as e:
                    results.append((call_id, "Error: %s" % e, True))
                    continue
                if name == "submit":
                    final = dict(layers=layers, tactics=tactics, reasoning=arguments.get("reasoning", ""))
                    results.append((call_id, "Submitted.", False))
                    continue
                if len(trials) > args.trials:
                    results.append((call_id, "Error: no trials left; call submit with your best tactics.", True))
                    continue
                n = len(trials)
                print("\ntrial %d: %s\n  %s" % (n, arguments.get("hypothesis", ""), json.dumps(dict(layers=layers, **tactics))),
                      flush=True)
                summary = trial(layers, tactics, "trial %d" % n, arguments.get("hypothesis"))
                text = feedback(summary) + "\n%d trials left." % (args.trials - n)
                print(text, flush=True)
                results.append((call_id, text, False))
            agent.answer(results)
        print("\nsubmitted: %s\n  %s" % (json.dumps(dict(layers=final["layers"], **final["tactics"])), final["reasoning"]),
              flush=True)

        def grade(layers, tactics, label):
            return summarize(held_out, market.run(markets(held_out, layers, tactics, args.ticks), label))

        built_in_layers, built_in = tactics_of(BUILT_IN)
        graded = {"cheater": grade(final["layers"], final["tactics"], "held-out cheater"),
                  "built-in": grade(built_in_layers, built_in, "held-out built-in")}

    report = {"model": None if args.dry_run else MODEL, "via": None if args.dry_run else args.via, "detector": DETECTOR, "trainSeeds": TRAIN_SEEDS,
              "heldOutSeeds": held_out, "ticks": args.ticks, "trials": trials, "final": final,
              "heldOut": {name: dict(summary, paired=paired(summary["rows"])) for name, summary in graded.items()}}
    with open(os.path.join(out, "result.json"), "w") as f:
        json.dump(report, f, indent=1)

    print("\nheld out, %d seeds:" % len(held_out))
    print("| Spoofer | Flagged | Shares, fakes vs control | Lift | Extra shares, 95% CI | Seeds where fakes helped |")
    print("| --- | ---: | ---: | ---: | ---: | ---: |")
    for name, summary in graded.items():
        p = paired(summary["rows"])
        low, high = p["shares"][1], p["shares"][2]
        print("| %s | %d of %d spoofers | %d vs %d | %s | %+.0f (%s) | %d of %d |" % (
            name, summary["flaggedSpoofers"], 2 * len(held_out), summary["shares"], summary["controlShares"],
            "%.2fx" % summary["lift"] if summary["lift"] else "n/a", p["shares"][0],
            "n/a" if low is None else "%+.0f to %+.0f" % (low, high), p["better"], p["seeds"]))
    print("\nsaved %s" % os.path.relpath(out, ROOT))


if __name__ == "__main__":
    main()
