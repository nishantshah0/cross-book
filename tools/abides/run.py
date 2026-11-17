"""Every detector, in a market someone else built: J.P. Morgan's ABIDES (rmsc04).

For each seed it runs ABIDES' standard market twice, with two of our layering spoofers
(tools/abides/spoofer_agent.py) and with their honest twins, turns the exchange's order
history into the log detectors read (detectors/README.md), and scores the built-in
detectors (through the app's POST /api/surveillance/scan, in a lab worker) and any
outside ones (run directly) against who really spoofed. ABIDES' own agents are the
honest traders, so a flag on one of them is a false alarm we didn't design around.

    target/external/abides/venv/bin/python tools/abides/run.py --seeds 1-5
    ... --external mar-layering='python3 detectors/mar_layering.py'

Needs ABIDES installed in target/external/abides/venv (see tools/abides/README.md),
Docker, and the packaged app (./mvnw -DskipTests package).
"""
import argparse
import collections
import datetime
import json
import os
import subprocess
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "tools", "lab"))
sys.path.insert(0, HERE)
import lab  # noqa: E402

from abides_core import abides  # noqa: E402
from abides_markets.configs import rmsc04  # noqa: E402
from abides_markets.utils import generate_latency_model  # noqa: E402
from spoofer_agent import LayeringSpoofer  # noqa: E402

SYMBOL = "ABM"
SPOOFERS = 2


def market(seed, minutes, layers):
    """One ABIDES market, with SPOOFERS spoofers (honest twins at layers=0) added."""
    end = (datetime.datetime(2021, 2, 5, 9, 30) + datetime.timedelta(minutes=minutes)).strftime("%H:%M:%S")
    config = rmsc04.build_config(seed=seed, end_time=end, stdout_log_level="WARNING")
    agents = config["agents"]
    for i in range(SPOOFERS):
        agents.append(LayeringSpoofer(len(agents), SYMBOL, 10_000_000,
                                      np.random.RandomState(seed=(seed * 1000 + i) % 2**32), layers=layers))
    config["agent_latency_model"] = generate_latency_model(len(agents))
    end_state = abides.run(config)
    return end_state["agents"]


def iso(ns):
    return datetime.datetime.fromtimestamp(ns / 1e9, tz=datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def market_log(agents):
    """The exchange's order history as detectors/README.md describes the input."""
    history = agents[0].order_books[SYMBOL].history
    orders = {}

    def order(order_id, agent_id, side, at, seq):
        return orders.setdefault(order_id, dict(id=order_id, accountId=agent_id, symbol=SYMBOL, side=side,
                                                type="MARKET", quantity=0, limitPrice=None, firstSeq=seq,
                                                placedAt=iso(at), endedAt=None, end=None, filled=0))

    for seq, r in enumerate(history, start=1):
        if r["type"] == "EXEC":
            passive = orders[r["order_id"]]
            passive["filled"] += r["quantity"]
            if passive["filled"] >= passive["quantity"]:
                passive["end"], passive["endedAt"] = "FILLED", iso(r["time"])
            aggressor = order(r["oppos_order_id"], r["oppos_agent_id"], "BUY" if r["side"] == "SELL" else "SELL",
                              r["time"], seq)
            aggressor["filled"] += r["quantity"]
            aggressor["quantity"] = max(aggressor["quantity"], aggressor["filled"])
            aggressor["end"], aggressor["endedAt"] = "FILLED", iso(r["time"])
        elif r["type"] == "LIMIT":  # the order now rests; if it traded on the way in, those shares count too
            o = order(r["order_id"], r["agent_id"], "BUY" if r["side"] == "BID" else "SELL", r["time"], seq)
            o.update(type="LIMIT", quantity=o["filled"] + r["quantity"], limitPrice=r["price"] / 100,
                     end=None, endedAt=None)
        elif r["type"] == "CANCEL" and r["order_id"] in orders:
            o = orders[r["order_id"]]
            o["end"], o["endedAt"] = "CANCELLED", iso(r["time"])
    usernames = {str(a.id): "agent_%04d" % a.id for a in agents[1:]}  # nothing about what kind of agent
    return dict(orders=sorted(orders.values(), key=lambda o: o["firstSeq"]), usernames=usernames)


def grade(alerts, kinds, spoofers, fakes):
    flagged = {a["accountId"] for a in alerts}
    pointed = {o for a in alerts for o in a.get("orders") or []}
    false = collections.Counter(kinds[a] for a in flagged - spoofers)
    return dict(caught=len(flagged & spoofers), cheaters=len(spoofers), falseAlarms=sum(false.values()),
                falseByKind=dict(false), fakes=len(fakes), fakesFound=len(pointed & fakes),
                ordersJudged=bool(pointed) or not alerts)


def run_external(command, log):
    done = subprocess.run(command, input=json.dumps(log), capture_output=True, text=True, timeout=600)
    if done.returncode != 0:
        raise RuntimeError("%s failed: %s" % (command, done.stderr[-2000:]))
    if done.stderr.strip():
        print("  %s wrote to stderr: %s" % (command[-1], done.stderr.strip()[-500:]), flush=True)
    return json.loads(done.stdout)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--seeds", type=lab.seeds, required=True, help="e.g. 1-5")
    parser.add_argument("--minutes", type=int, default=60, help="market minutes from the 9:30 open (default 60)")
    parser.add_argument("--external", type=lab.external_detector, action="append", default=[], metavar="NAME=COMMAND")
    args = parser.parse_args()
    started = time.time()
    rows = []
    with lab.Lab(1) as app:
        url = app.urls[0]
        for seed in args.seeds:
            for name, layers in (("spoofers", 4), ("control", 0)):
                agents = market(seed, args.minutes, layers)
                log = market_log(agents)
                kinds = {a.id: type(a).__name__ for a in agents}
                spoofer_ids = {a.id for a in agents if isinstance(a, LayeringSpoofer) and layers}
                labels = {oid: role for a in agents if isinstance(a, LayeringSpoofer) for oid, (_, role) in a.labels.items()}
                fakes = {oid for oid, role in labels.items() if role == "SPOOF"}
                status, alerts = lab.call("POST", url + "/api/surveillance/scan", log, timeout=600)
                if status != 200:
                    sys.exit("the app couldn't scan the log (%s): %s; is the jar older than the scan endpoint?"
                             % (status, str(alerts)[:500]))
                for ext, command in args.external:
                    alerts[ext] = run_external([lab.absolute(c) for c in command], log)
                real = [o for o in log["orders"] if labels.get(o["id"]) == "REAL"]
                rows.append(dict(seed=seed, market=name, orders=len(log["orders"]),
                                 realShares=sum(o["filled"] for o in real), realOrders=len(real),
                                 scores={d: grade(a, kinds, spoofer_ids, fakes) for d, a in alerts.items()}))
                print("seed %d %s: %d orders, %d by the spoofers' accounts" % (
                    seed, name, len(log["orders"]), sum(1 for o in log["orders"] if o["accountId"] in
                                                          {a.id for a in agents if isinstance(a, LayeringSpoofer)})),
                      flush=True)
    detectors = sorted({d for r in rows for d in r["scores"]})
    print("\nABIDES rmsc04, %d seeds, %d minutes each, %d spoofers per market\n" % (len(args.seeds), args.minutes, SPOOFERS))
    print("| Detector | Market | Caught | False alarms | Fake orders found |")
    print("| --- | --- | ---: | ---: | ---: |")
    for d in detectors:
        for name in ("spoofers", "control"):
            s = [r["scores"][d] for r in rows if r["market"] == name]
            false = collections.Counter()
            for x in s:
                false.update(x["falseByKind"])
            found, total = sum(x["fakesFound"] for x in s), sum(x["fakes"] for x in s)
            print("| %s | %s | %d / %d | %d%s | %s |" % (
                d, name, sum(x["caught"] for x in s), sum(x["cheaters"] for x in s), sum(false.values()),
                " (%s)" % ", ".join("%d %s" % (n, k) for k, n in false.most_common()) if false else "",
                "%d%% (%d of %d)" % (100 * found / total, found, total) if total and any(x["ordersJudged"] for x in s)
                else "-"))
    pairs = [(r, c) for r in rows if r["market"] == "spoofers" for c in rows
             if c["market"] == "control" and c["seed"] == r["seed"]]
    spoofed, control = sum(r["realShares"] for r, _ in pairs), sum(c["realShares"] for _, c in pairs)
    print("\nreal orders' shares traded, with fakes vs without: %d vs %d (%s)" % (
        spoofed, control, "%.2fx" % (spoofed / control) if control else "n/a"))
    out = os.path.join(ROOT, "target", "lab", "abides-%s.json" % time.strftime("%Y%m%d-%H%M%S"))
    with open(out, "w") as f:
        json.dump(dict(seeds=args.seeds, minutes=args.minutes, rows=rows), f, indent=1)
    print("saved %s\ntook %.0f s" % (os.path.relpath(out, ROOT), time.time() - started))


if __name__ == "__main__":
    main()
