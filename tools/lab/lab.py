#!/usr/bin/env python3
"""Runs many bot markets at once, one per worker, and merges what they found.

A bot market has to run one action at a time to replay exactly, so a single app does
about one hour-long market a minute. Markets with different seeds share nothing, so
this starts N copies of the packaged app, each with its own database in one
throwaway Postgres container, hands each the next seed through the job API, and
merges the reports. Each copy still runs its markets one at a time, so every market
is exactly the one a single app would have run from that seed.

    ./mvnw -DskipTests package                       # build the jar first
    python3 tools/lab/lab.py evaluate --seeds 101-105
    python3 tools/lab/lab.py spoof-pairs --seeds 201-230 --workers 6

evaluate scores every detector on each seed's spoofed and control markets.
spoof-pairs runs each seed twice, with fake orders and without (the honest control),
and reports the paired differences with 95% confidence intervals.

Results go to target/lab/<command>-<time>.json, and worker logs to target/lab/. Each finished
market is cached under target/lab/cache/ by jar and request, so a rerun after a crash or a
sleeping laptop only runs the markets that are missing. On a Mac, run it under caffeinate -i.
Needs Docker, Java 21 and Python 3.9 or newer; nothing outside the standard library.
"""

import argparse
import glob
import hashlib
import json
import math
import os
import queue
import shutil
import socket
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "target", "lab")
DB_USER = DB_PASSWORD = "lab"

# two-sided 95% critical values of Student's t, by degrees of freedom
T95 = {1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571, 6: 2.447, 7: 2.365, 8: 2.306, 9: 2.262,
       10: 2.228, 11: 2.201, 12: 2.179, 13: 2.160, 14: 2.145, 15: 2.131, 16: 2.120, 17: 2.110,
       18: 2.101, 19: 2.093, 20: 2.086, 21: 2.080, 22: 2.074, 23: 2.069, 24: 2.064, 25: 2.060,
       26: 2.056, 27: 2.052, 28: 2.048, 29: 2.045, 30: 2.042, 40: 2.021, 60: 2.000, 120: 1.980}


def t95(df):
    for limit in sorted(T95):
        if df <= limit:
            return T95[limit]
    return 1.960


def seeds(text):
    """'101-105' or '1,4,9' or a mix"""
    out = []
    for part in text.split(","):
        if "-" in part:
            low, high = part.split("-")
            out.extend(range(int(low), int(high) + 1))
        else:
            out.append(int(part))
    return out


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def call(method, url, body=None, timeout=30):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method,
                                     headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, json.loads(response.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"null")


class Lab:
    """A Postgres container and N workers, each an app with a database of its own."""

    def __init__(self, workers):
        self.size = workers
        self.container = "cross-book-lab-%d" % os.getpid()
        self.processes = []
        self.urls = []
        self.build = None

    def __enter__(self):
        jars = [j for j in glob.glob(os.path.join(ROOT, "target", "cross-book-*.jar")) if "plain" not in j]
        if not jars:
            sys.exit("no jar in target/; build it first: ./mvnw -DskipTests package")
        with open(jars[0], "rb") as f:
            self.build = hashlib.sha256(f.read()).hexdigest()
        if shutil.which("docker") is None:
            sys.exit("docker is not on the PATH")
        os.makedirs(OUT, exist_ok=True)
        db_port = free_port()
        print("starting Postgres and %d workers ..." % self.size, flush=True)
        subprocess.run(["docker", "run", "-d", "--rm", "--name", self.container,
                        "-e", "POSTGRES_USER=" + DB_USER, "-e", "POSTGRES_PASSWORD=" + DB_PASSWORD,
                        "-p", "127.0.0.1:%d:5432" % db_port, "postgres:17",
                        "-c", "max_connections=%d" % (20 * self.size + 20)],
                       check=True, stdout=subprocess.DEVNULL)
        self._wait(lambda: subprocess.run(["docker", "exec", self.container, "pg_isready", "-U", DB_USER],
                                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0,
                   60, "Postgres did not start")
        time.sleep(1)  # pg_isready can answer before the init scripts finish and the server restarts
        self._wait(lambda: subprocess.run(["docker", "exec", self.container, "psql", "-U", DB_USER, "-c", "select 1"],
                                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0,
                   60, "Postgres did not accept connections")
        java = os.path.join(os.environ["JAVA_HOME"], "bin", "java") if os.environ.get("JAVA_HOME") else "java"
        for i in range(1, self.size + 1):
            subprocess.run(["docker", "exec", self.container, "psql", "-U", DB_USER, "-c",
                            "create database worker%d" % i], check=True, stdout=subprocess.DEVNULL)
            port = free_port()
            env = dict(os.environ, MARKET_MODE="book", CLOCK_MODE="simulated", FEED_MODE="demo",
                       DB_URL="jdbc:postgresql://localhost:%d/worker%d" % (db_port, i),
                       DB_USERNAME=DB_USER, DB_PASSWORD=DB_PASSWORD)
            log = open(os.path.join(OUT, "worker%d.log" % i), "w")
            self.processes.append(subprocess.Popen(
                [java, "-Xmx512m", "-jar", jars[0], "--server.port=%d" % port,
                 "--spring.docker.compose.enabled=false"], env=env, stdout=log, stderr=subprocess.STDOUT))
            self.urls.append("http://127.0.0.1:%d" % port)
        for url, process in zip(self.urls, self.processes):
            self._wait(lambda: process.poll() is None and self._healthy(url), 120,
                       "a worker did not start; see target/lab/worker*.log")
        print("%d workers up" % self.size, flush=True)
        return self

    def __exit__(self, *exc):
        for process in self.processes:
            process.terminate()
        for process in self.processes:
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
        subprocess.run(["docker", "stop", self.container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    @staticmethod
    def _healthy(url):
        try:
            return call("GET", url + "/actuator/health", timeout=2)[1].get("status") == "UP"
        except (OSError, ValueError, AttributeError):
            return False

    @staticmethod
    def _wait(ready, seconds, failure):
        deadline = time.time() + seconds
        while not ready():
            if time.time() > deadline:
                sys.exit(failure)
            time.sleep(0.5)

    def _job(self, url, path, body):
        """Submits one job and waits for it. A missed poll (the laptop slept, say) is retried."""
        status, job = call("POST", url + path, body)
        if status != 202:
            raise RuntimeError("%s %s" % (status, job))
        misses = 0
        while job["status"] in ("QUEUED", "RUNNING"):
            time.sleep(0.5)
            try:
                job = call("GET", "%s/api/jobs/%d" % (url, job["id"]))[1]
                misses = 0
            except (OSError, ValueError):
                misses += 1
                if misses > 20:
                    raise
        if job["status"] != "DONE":
            raise RuntimeError(job.get("error"))
        return job["result"]

    def _cache_path(self, path, body):
        """Markets replay exactly, so a result is reusable until the jar changes."""
        key = hashlib.sha256(json.dumps([self.build, path, body], sort_keys=True).encode()).hexdigest()
        return os.path.join(OUT, "cache", key[:32] + ".json")

    def _cached(self, path, body):
        try:
            with open(self._cache_path(path, body)) as f:
                return json.load(f)
        except (OSError, ValueError):
            return None

    def _remember(self, path, body, result):
        target = self._cache_path(path, body)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target + ".tmp", "w") as f:
            json.dump(result, f)
        os.replace(target + ".tmp", target)

    def run(self, tasks, label, on_done=None):
        """Runs (path, body) tasks across the workers, as jobs; returns results in task order.
        on_done(done, total), if given, is called after each one finishes."""
        work = queue.Queue()
        for index, task in enumerate(tasks):
            work.put((index, task))
        results = [None] * len(tasks)
        failures = []
        done = [0]
        lock = threading.Lock()
        started = time.time()

        def worker(url):
            while True:
                try:
                    index, (path, body) = work.get_nowait()
                except queue.Empty:
                    return
                cached = self._cached(path, body)
                if cached is None:
                    try:
                        cached = self._job(url, path, body)
                    except Exception as e:  # noqa: BLE001 - one bad market must not lose the rest
                        failures.append("%s %s: %s" % (path, body, e))
                        continue
                    self._remember(path, body, cached)
                results[index] = cached
                with lock:
                    done[0] += 1
                    print("  %s %d/%d done (%.0fs)" % (label, done[0], len(tasks), time.time() - started), flush=True)
                    if on_done:
                        on_done(done[0], len(tasks))

        threads = [threading.Thread(target=worker, args=(url,)) for url in self.urls]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        if failures:
            sys.exit("failed:\n  " + "\n  ".join(failures))
        return results


def ratio(part, whole):
    return None if whole == 0 else part / whole


def pct(value):
    return "-" if value is None else "%.0f%%" % (100 * value)


def evaluate(args):
    with Lab(args.workers) as lab:
        reports = lab.run([("/api/surveillance/evaluations", {"seeds": [s], "ticks": args.ticks})
                           for s in args.seeds], "seeds")
    runs = [run for report in reports for run in report["runs"]]
    totals = {}
    for run in runs:
        for score in run["scores"]:
            t = totals.setdefault((score["detector"], run["market"]), dict(
                flagged=0, cheaters=0, caught=0, falseAlarms=0, byKind={}, spoof=0, judged=0, hit=0, orders=False))
            for key in ("flagged", "cheaters", "caught", "falseAlarms"):
                t[key] += score[key]
            for kind, n in score["falseAlarmsByKind"].items():
                t["byKind"][kind] = t["byKind"].get(kind, 0) + n
            if score["orders"] is not None:
                t["orders"] = True
                t["spoof"] += score["orders"]["spoofOrders"]
                t["judged"] += score["orders"]["pointedAt"]
                t["hit"] += score["orders"]["pointedAtSpoof"]
    print("\n%d seeds, %d ticks each\n" % (len(args.seeds), args.ticks))
    print("| Detector | Market | Caught | False alarms | Precision | Fake orders found |")
    print("| --- | --- | ---: | ---: | ---: | ---: |")
    for (detector, market), t in totals.items():
        kinds = ", ".join("%d %s" % (n, k.lower()) for k, n in sorted(t["byKind"].items()))
        print("| %s | %s | %d / %d | %d%s | %s | %s |" % (
            detector, market, t["caught"], t["cheaters"], t["falseAlarms"], " (%s)" % kinds if kinds else "",
            pct(ratio(t["caught"], t["flagged"])),
            "%s (%d of %d)" % (pct(ratio(t["hit"], t["spoof"])), t["hit"], t["spoof"]) if t["orders"] and t["spoof"]
            else "-"))
    save("evaluate", {"seeds": args.seeds, "ticks": args.ticks, "totals": [
        dict(detector=d, market=m, **t) for (d, m), t in totals.items()], "reports": reports})


def spoof_pairs(args):
    tasks = []
    for seed in args.seeds:
        for layers in (4, 0):
            tasks.append(("/api/bots/runs", {"seed": seed, "ticks": args.ticks, "spoofLayers": layers}))
    with Lab(args.workers) as lab:
        reports = lab.run(tasks, "markets")
    rows = []
    for i in range(0, len(reports), 2):
        spoofed, control = reports[i], reports[i + 1]
        rows.append(dict(seed=spoofed["seed"], spoofed=pair_side(spoofed, True), control=pair_side(control, False)))
    print("\n%d seeds, %d ticks each, every seed run with fake orders and without\n" % (len(args.seeds), args.ticks))
    print("| Measure | With fakes | Control | Difference (95% CI) | Seeds where fakes did better |")
    print("| --- | ---: | ---: | ---: | ---: |")
    summary = {}
    for name, key, fmt in (("Spoofer P&L ($, both spoofers)", "pnl", "%+.0f"),
                           ("Real orders that traded", "fillRate", "%.0f%%"),
                           ("Shares the real orders traded", "shares", "%.0f"),
                           ("Book readers' P&L ($, all 20)", "readers", "%+.0f"),
                           ("Worst stock vs feed (bps)", "worstGap", "%.0f")):
        scale = 100 if key == "fillRate" else 1
        a = [r["spoofed"][key] * scale for r in rows]
        b = [r["control"][key] * scale for r in rows]
        d = [x - y for x, y in zip(a, b)]
        mean = statistics.mean(d)
        half = t95(len(d) - 1) * statistics.stdev(d) / math.sqrt(len(d)) if len(d) > 1 else float("nan")
        better = sum(1 for x in d if (x < 0 if key in ("readers", "worstGap") else x > 0))
        unit = "%" if key == "fillRate" else ""
        print("| %s | %s | %s | %s%s (%s%s to %s%s) | %d of %d |" % (
            name, fmt % statistics.mean(a), fmt % statistics.mean(b),
            ("%+.1f" % mean), unit, "%+.1f" % (mean - half), unit, "%+.1f" % (mean + half), unit,
            better, len(d)))
        summary[key] = dict(withFakes=statistics.mean(a), control=statistics.mean(b), difference=mean,
                            low=mean - half, high=mean + half, fakesBetter=better)
    print("\nfor book readers and the worst gap, 'better' means lower: readers lost more, prices strayed less")
    save("spoof-pairs", {"seeds": args.seeds, "ticks": args.ticks, "summary": summary, "rows": rows,
                         "reports": reports})


def pair_side(report, labelled):
    pnl = {k["kind"]: float(k["tradingPnl"]) for k in report["kinds"]}
    activity = {a["kind"]: a for a in report["activity"]}
    if labelled:
        s = report["spoofing"] or {"realOrders": 0, "realFilled": 0, "realShares": 0}
        orders, filled, shares = s["realOrders"], s["realFilled"], s["realShares"]
    else:  # the control places only real orders
        a = activity.get("SPOOFER", {"orders": 0, "ordersFilled": 0, "sharesFilled": 0})
        orders, filled, shares = a["orders"], a["ordersFilled"], a["sharesFilled"]
    return dict(pnl=pnl.get("SPOOFER", 0.0), readers=pnl.get("BOOK_READER", 0.0),
                fillRate=filled / orders if orders else 0.0, shares=shares,
                worstGap=max(abs(p["gapBps"] or 0) for p in report["prices"]), seconds=report["seconds"])


def save(command, data):
    path = os.path.join(OUT, "%s-%s.json" % (command, time.strftime("%Y%m%d-%H%M%S")))
    with open(path, "w") as f:
        json.dump(data, f, indent=1)
    print("\nsaved %s" % os.path.relpath(path, ROOT))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--workers", type=int, default=4, help="apps running markets at once (default 4)")
    commands = parser.add_subparsers(dest="command", required=True)
    for name, help_text in (("evaluate", "score the detectors on spoofed and control markets"),
                            ("spoof-pairs", "spoofing vs the honest control, paired by seed")):
        command = commands.add_parser(name, help=help_text)
        command.add_argument("--seeds", type=seeds, required=True, help="e.g. 101-105 or 1,4,9")
        command.add_argument("--ticks", type=int, default=240, help="ticks per market (default 240, an hour)")
    args = parser.parse_args()
    started = time.time()
    {"evaluate": evaluate, "spoof-pairs": spoof_pairs}[args.command](args)
    print("took %.0f s" % (time.time() - started))


if __name__ == "__main__":
    main()
