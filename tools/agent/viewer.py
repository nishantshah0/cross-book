#!/usr/bin/env python3
"""Turns saved AI-cheater runs into static web pages, one per run, plus an index.

    python3 tools/agent/viewer.py target/agent/20261116-170000   # one run
    python3 tools/agent/viewer.py --all                          # every run in target/agent/

Each page goes to docs/runs/<run>/index.html with the run's result.json beside it, and
docs/runs/index.html lists them all. The pages are self-contained: no build step, no
server, no scripts from anywhere else, so they work opened from disk or on GitHub Pages.
Standard library only.
"""

import argparse
import glob
import html
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SOURCE = os.path.join(ROOT, "target", "agent")
OUT = os.path.join(ROOT, "docs", "runs")
KNOBS = ["layers", "fakeSize", "fakeOffsetCents", "fakeGapCents", "realSize", "realFirst",
         "stepMinMs", "stepMaxMs", "restMs"]


def load(run_dir):
    """The run's result, with each trial's hypothesis filled in from the transcript if missing."""
    with open(os.path.join(run_dir, "result.json")) as f:
        result = json.load(f)
    calls = []
    path = os.path.join(run_dir, "transcript.jsonl")
    if os.path.exists(path):
        with open(path) as f:
            for line in f:
                entry = json.loads(line)
                if entry["kind"] == "call" and entry["data"]["name"] == "run_trial":
                    calls.append(entry["data"]["input"])
    pending = list(calls)
    for i, trial in enumerate(result["trials"]):
        if trial.get("hypothesis") or i == 0:
            trial.setdefault("hypothesis", "the built-in spoofer, run by the harness")
            continue
        wanted = dict(trial["tactics"], layers=trial["layers"])
        while pending:  # calls that failed validation never became trials, so skip them
            call = pending.pop(0)
            if all(call.get(k) == wanted.get(k) for k in KNOBS):
                trial["hypothesis"] = call.get("hypothesis", "")
                break
    result["name"] = os.path.basename(os.path.normpath(run_dir))
    return result


PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>__TITLE__</title>
<meta name="description" content="__DESCRIPTION__">
<style>
:root {
  color-scheme: light;
  --page: #f9f9f7; --surface: #fcfcfb; --ink: #0b0b0b; --ink-2: #52514e; --muted: #898781;
  --grid: #e1e0d9; --axis: #c3c2b7; --border: rgba(11,11,11,0.10);
  --series-1: #2a78d6; --series-2: #eb6834; --critical: #d03b3b; --good: #0ca30c; --good-text: #006300;
  --wash: rgba(42,120,214,0.08);
}
@media (prefers-color-scheme: dark) {
  :root:where(:not([data-theme="light"])) {
    color-scheme: dark;
    --page: #0d0d0d; --surface: #1a1a19; --ink: #f0efec; --ink-2: #c3c2b7; --muted: #898781;
    --grid: #2c2c2a; --axis: #383835; --border: rgba(255,255,255,0.10);
    --series-1: #3987e5; --series-2: #d95926; --good-text: #0ca30c; --wash: rgba(57,135,229,0.12);
  }
}
:root[data-theme="dark"] {
  color-scheme: dark;
  --page: #0d0d0d; --surface: #1a1a19; --ink: #f0efec; --ink-2: #c3c2b7; --muted: #898781;
  --grid: #2c2c2a; --axis: #383835; --border: rgba(255,255,255,0.10);
  --series-1: #3987e5; --series-2: #d95926; --good-text: #0ca30c; --wash: rgba(57,135,229,0.12);
}
* { box-sizing: border-box; }
html { -webkit-text-size-adjust: 100%; }
body { margin: 0; background: var(--page); color: var(--ink); font: 15px/1.55 system-ui, -apple-system, "Segoe UI", sans-serif; }
main { max-width: 960px; margin: 0 auto; padding: 32px 16px 64px; }
a { color: var(--series-1); }
h1 { font-size: 28px; line-height: 1.2; margin: 4px 0 8px; letter-spacing: -0.01em; }
h2 { font-size: 18px; margin: 40px 0 12px; }
.eyebrow { color: var(--muted); font-size: 13px; text-transform: uppercase; letter-spacing: 0.06em; }
.lede { color: var(--ink-2); max-width: 70ch; margin: 0; }
.card { background: var(--surface); border: 1px solid var(--border); border-radius: 12px; padding: 20px; }
.tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; margin-top: 24px; }
.tile .label { color: var(--ink-2); font-size: 13px; }
.tile .value { font-size: 30px; font-weight: 600; line-height: 1.2; margin-top: 4px; }
.tile .sub { color: var(--muted); font-size: 13px; margin-top: 2px; }
.hero .value { font-size: 44px; white-space: nowrap; }
@media (max-width: 480px) { .tile .value { font-size: 24px; } .hero .value { font-size: 44px; } .hero { grid-column: 1 / -1; } h1 { font-size: 24px; } }
.meta { color: var(--muted); font-size: 13px; margin-top: 12px; }
.chart-scroll { overflow-x: auto; }
.chart-wrap { position: relative; min-width: 620px; }
svg { display: block; width: 100%; height: auto; overflow: visible; }
svg text { font: 12px system-ui, -apple-system, "Segoe UI", sans-serif; fill: var(--muted); }
.bar { fill: var(--series-1); }
.bar.final { fill: var(--series-2); }
.hit:hover + .bar, .hit:focus + .bar { opacity: 0.8; }
.hit { fill: transparent; cursor: pointer; outline: none; }
.legend { display: flex; flex-wrap: wrap; gap: 16px; color: var(--ink-2); font-size: 13px; margin-bottom: 8px; }
.key { display: inline-flex; align-items: center; gap: 6px; }
.swatch { width: 12px; height: 12px; border-radius: 3px; display: inline-block; }
.cell-flag { width: 12px; height: 12px; border-radius: 3px; display: inline-flex; align-items: center; justify-content: center; font-size: 9px; font-weight: 700; line-height: 1; }
.flagged { background: var(--critical); color: #fff; }
.clear { border: 1.5px solid var(--axis); color: var(--muted); }
.tooltip { position: absolute; pointer-events: none; background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; font-size: 13px; box-shadow: 0 4px 16px rgba(0,0,0,0.12); max-width: 300px; display: none; z-index: 2; }
.tooltip strong { font-size: 15px; display: block; }
.tooltip .muted { color: var(--ink-2); }
.trials { display: grid; gap: 12px; }
.trial-head { display: flex; flex-wrap: wrap; align-items: baseline; gap: 8px 16px; }
.trial-head .n { font-weight: 600; }
.trial-head .lift { font-weight: 600; font-variant-numeric: tabular-nums; }
.pill { font-size: 12px; border-radius: 999px; padding: 2px 8px; border: 1px solid var(--border); color: var(--ink-2); }
.pill.bad { color: #fff; background: var(--critical); border-color: transparent; }
.pill.ok { color: var(--good-text); border-color: currentColor; }
.hypothesis { margin: 10px 0 0; color: var(--ink); white-space: pre-wrap; }
.changes { margin-top: 10px; display: flex; flex-wrap: wrap; gap: 6px; }
.change { font-size: 12px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; background: var(--wash); border-radius: 6px; padding: 2px 6px; }
details { margin-top: 10px; }
summary { cursor: pointer; color: var(--ink-2); font-size: 13px; }
.table-scroll { overflow-x: auto; }
table { border-collapse: collapse; width: 100%; font-size: 13px; }
th, td { text-align: right; padding: 6px 10px; border-bottom: 1px solid var(--grid); white-space: nowrap; font-variant-numeric: tabular-nums; }
th:first-child, td:first-child { text-align: left; }
th { color: var(--ink-2); font-weight: 600; }
.alert { color: var(--ink-2); font-size: 13px; margin: 6px 0 0; white-space: normal; }
.reasoning { white-space: pre-wrap; margin: 0; }
.note { color: var(--ink-2); font-size: 13px; }
.runs a { display: block; text-decoration: none; color: inherit; }
.runs a:hover .card { border-color: var(--series-1); }
.live-bar { display: inline-flex; align-items: center; gap: 8px; margin: 10px 0 2px; padding: 4px 12px; border-radius: 999px; background: var(--surface); border: 1px solid var(--border); font-size: 13px; color: var(--ink-2); }
.live-bar .ago { color: var(--muted); }
.dot { width: 8px; height: 8px; border-radius: 50%; background: var(--muted); flex: none; }
.dot.on { background: var(--good); animation: pulse 1.6s ease-in-out infinite; }
.dot.done { background: var(--series-1); }
.dot.failed { background: var(--critical); }
@keyframes pulse { 50% { opacity: 0.35; } }
@media (prefers-reduced-motion: reduce) { .dot.on { animation: none; } }
.progress { height: 6px; border-radius: 3px; background: var(--grid); margin-top: 14px; overflow: hidden; }
.progress-fill { height: 100%; background: var(--series-1); border-radius: 3px; transition: width 0.4s ease; width: 0; }
.pending { fill: var(--series-1); opacity: 0.3; }
.now { border-color: var(--series-1); }
section[hidden], [hidden] { display: none !important; }
footer { margin-top: 48px; color: var(--muted); font-size: 13px; }
code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.92em; }
</style>
</head>
<body>
<main>
__BODY__
</main>
__SCRIPT__
</body>
</html>
"""

RUN_BODY = """
<div class="eyebrow"><a href="../">CrossBook</a> · AI cheater run</div>
<div class="live-bar" id="live" hidden><span class="dot" id="live-dot"></span><span id="live-text"></span><span class="ago" id="live-ago"></span></div>
<h1 id="headline"></h1>
<p class="lede" id="lede"></p>
<div class="tiles" id="tiles"></div>
<p class="meta" id="meta"></p>

<section id="now-section" hidden>
<h2 id="now-title"></h2>
<div class="card now"><p class="hypothesis" id="now-hypothesis"></p><div class="changes" id="now-changes"></div>
<div class="progress" id="now-progress"><div class="progress-fill" id="now-fill"></div></div><p class="note" id="now-note"></p></div>
</section>

<h2>Trial by trial</h2>
<div class="card">
  <div class="legend">
    <span class="key"><span class="swatch" style="background:var(--series-1)"></span>Trial lift: real-order shares with fakes ÷ without</span>
    <span class="key"><span class="swatch" style="background:var(--series-2)"></span>Submitted tactics, held out</span>
    <span class="key"><span class="cell-flag flagged">!</span>Market flagged</span>
    <span class="key"><span class="cell-flag clear">✓</span>Not flagged</span>
  </div>
  <div class="chart-scroll"><div class="chart-wrap"><svg id="chart" role="img"></svg><div class="tooltip" id="tip"></div></div></div>
  <details><summary>Table view</summary><div class="table-scroll"><table id="trial-table"></table></div></details>
</div>

<h2>What Claude tried</h2>
<div class="trials" id="trials"></div>

<section id="final-section" hidden>
<h2>Claude's write-up, on submitting</h2>
<div class="card"><p class="reasoning" id="reasoning"></p><div class="changes" id="final-changes"></div></div>
</section>

<section id="held-section" hidden>
<h2>Held out</h2>
<div class="card">
  <p class="note" id="held-note"></p>
  <div class="table-scroll"><table id="held-table"></table></div>
</div>
</section>

<h2>How to read this</h2>
<div class="card note" id="method"></div>

<footer id="footer">Generated by <code>tools/agent/viewer.py</code> from <a href="result.json">result.json</a>. Every market here replays exactly from its seed.</footer>
"""

RUN_SCRIPT = r"""
<script type="application/json" id="data">__DATA__</script>
<script>
(() => {
  const $ = (id) => document.getElementById(id);
  const el = (tag, attrs = {}, text) => {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
    if (text !== undefined) node.textContent = text;
    return node;
  };
  const svgEl = (tag, attrs = {}) => {
    const node = document.createElementNS("http://www.w3.org/2000/svg", tag);
    for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
    return node;
  };
  const fmt = (n, d = 0) => n == null ? "n/a" : Number(n).toLocaleString("en-US", { maximumFractionDigits: d, minimumFractionDigits: d });
  const x = (lift) => lift == null ? "n/a" : fmt(lift, 2) + "×";
  const signed = (n) => n == null ? "n/a" : (n > 0 ? "+" : n < 0 ? "−" : "") + fmt(Math.abs(n));
  const plural = (n, word) => n + " " + word + (n === 1 ? "" : "s");
  const BUILT = { layers: 4, fakeSize: 0.08, fakeOffsetCents: 1, fakeGapCents: 1, realSize: 0.02, realFirst: false,
                  stepMinMs: 1000, stepMaxMs: 3000, restMs: 40000 };
  const unit = { layers: (v) => String(v), fakeSize: (v) => fmt(v * 100, 1) + "% of cash", realSize: (v) => fmt(v * 100, 1) + "% of cash",
                 fakeOffsetCents: (v) => v + "¢", fakeGapCents: (v) => v + "¢", realFirst: (v) => v ? "yes" : "no",
                 stepMinMs: (v) => fmt(v / 1000, 1) + " s", stepMaxMs: (v) => fmt(v / 1000, 1) + " s", restMs: (v) => fmt(v / 1000) + " s" };
  const changes = (layers, tactics) => {
    const all = Object.assign({ layers }, tactics || {}), out = [];
    for (const k of Object.keys(unit)) if (all[k] !== undefined && all[k] !== BUILT[k]) out.push(k + " " + unit[k](BUILT[k]) + " → " + unit[k](all[k]));
    return out;
  };
  const flaggedCells = (summary) => summary.rows.map((r) => r.spoofed.flagged.length > 0);

  function render(run) {
    const status = run.status || { phase: "done" };
    const live = document.body.dataset.live;
    const trials = run.trials || [];
    const cheater = run.heldOut && run.heldOut.cheater, builtIn = run.heldOut && run.heldOut["built-in"];
    const spoofers = 2 * run.heldOutSeeds.length;
    ["tiles", "chart", "trial-table", "trials", "final-changes", "held-table", "method", "now-changes"].forEach((id) => $(id).replaceChildren());

    // headline: claims only what the evidence so far supports
    const tried = Math.max(0, trials.length - 1);
    if (cheater) {
      const ci = cheater.paired.shares, evaded = cheater.flaggedSpoofers === 0, working = cheater.lift != null && cheater.lift >= 1.5;
      const proven = ci[1] != null && ci[1] > 0;
      $("headline").textContent = !evaded ? "The detector held: Claude's spoofer was still caught"
        : working && proven ? "Claude got a spoofer past the detector, and the fakes still worked"
        : working ? "Claude got a spoofer past the detector; whether the fakes still work isn't settled"
        : "Claude got a spoofer past the detector, but the fakes stopped working";
      $("lede").textContent = "The detector was frozen. Claude played the spoofer, tried " + plural(tried, "set")
        + " of tactics on " + plural(run.trainSeeds.length, "training market") + ", saw what got flagged and why, then submitted one. "
        + "Below are its submitted tactics on " + plural(run.heldOutSeeds.length, "market") + " it never saw, next to the built-in spoofer on the same markets.";
    } else {
      $("headline").textContent = status.phase === "failed" ? "This run stopped before it finished"
        : run.final ? "Claude has submitted; grading on unseen markets" : "Claude is searching for tactics the detector misses";
      $("lede").textContent = "The detector is frozen. Claude plays the spoofer and gets " + plural(run.budget || 10, "trial")
        + ", each on " + plural(run.trainSeeds.length, "training market") + " with and without the fakes. It sees what got flagged and why, "
        + "then submits one set of tactics, which is graded on " + plural(run.heldOutSeeds.length, "market") + " it hasn't seen.";
    }

    const tile = (label, value, sub, hero) => {
      const t = el("div", { class: "card tile" + (hero ? " hero" : "") });
      t.append(el("div", { class: "label" }, label), el("div", { class: "value" }, value), el("div", { class: "sub" }, sub));
      $("tiles").append(t);
    };
    if (cheater) {
      const ci = cheater.paired.shares;
      tile("Claude's spoofers flagged, held out", cheater.flaggedSpoofers + " of " + spoofers, "built-in spoofer: " + builtIn.flaggedSpoofers + " of " + spoofers, true);
      tile("What the fakes bought", x(cheater.lift), "real-order shares vs the honest control; built-in " + x(builtIn.lift));
      tile("Extra shares per market", signed(ci[0]), ci[1] == null ? "one seed, no interval" : "95% CI " + signed(ci[1]) + " to " + signed(ci[2]));
      tile("Seeds where the fakes helped", cheater.paired.better + " of " + cheater.paired.seeds, "built-in: " + builtIn.paired.better + " of " + builtIn.paired.seeds);
    } else {
      const clean = trials.slice(1).filter((t) => t.summary.flaggedMarkets === 0);
      const best = clean.reduce((b, t) => (b == null || (t.summary.lift || 0) > (b.summary.lift || 0)) ? t : b, null);
      tile("Trials used", tried + " of " + (run.budget || 10), status.phase === "trial" && status.trial > 0 ? "trial " + status.trial + " running" : status.phase === "thinking" ? "Claude is deciding" : "", true);
      tile("Built-in spoofer", trials[0] ? x(trials[0].summary.lift) : "…", trials[0] ? "flagged in " + trials[0].summary.flaggedMarkets + " of " + trials[0].summary.rows.length + " markets" : "running first");
      tile("Best unflagged lift so far", best ? x(best.summary.lift) : "none yet", best ? "trial " + trials.indexOf(best) : "no trial has slipped past yet");
      tile("Goal", "1.5×", "unflagged on every training market, with at least this lift");
    }
    $("meta").textContent = [run.model ? run.model + (run.via === "claude-code" ? " via Claude Code" : " via the API") : "scripted dry run",
      "detector " + run.detector, "training seeds " + run.trainSeeds.join(", "),
      "held-out seeds " + run.heldOutSeeds[0] + "–" + run.heldOutSeeds[run.heldOutSeeds.length - 1],
      run.ticks / 4 + " minutes of market time each", "run " + run.name].join(" · ");

    // what's happening right now
    $("now-section").hidden = !(live && (status.phase === "trial" || status.phase === "grading" || status.phase === "thinking"));
    if (!$("now-section").hidden) {
      const pct = status.total ? 100 * status.done / status.total : 0;
      $("now-progress").hidden = status.phase === "thinking";
      $("now-fill").style.width = pct + "%";
      if (status.phase === "thinking") {
        $("now-title").textContent = "Now: Claude is deciding what to try next";
        $("now-hypothesis").textContent = "It has the results of " + plural(trials.length, "trial") + " so far. Its next move shows up here when it picks one.";
        $("now-note").textContent = "";
      } else if (status.phase === "grading") {
        $("now-title").textContent = "Now: grading " + status.who + " on unseen markets";
        $("now-hypothesis").textContent = "Each of " + plural(run.heldOutSeeds.length, "unseen seed") + " runs with the fakes and as the honest control.";
        $("now-note").textContent = status.done + " of " + status.total + " markets done";
      } else {
        $("now-title").textContent = status.trial === 0 ? "Now: running the built-in spoofer" : "Now: trial " + status.trial;
        $("now-hypothesis").textContent = status.trial === 0 ? "The harness runs the built-in spoofer first, so Claude starts from what it gets." : (status.hypothesis || "");
        changes(status.layers, status.tactics).forEach((c) => $("now-changes").append(el("span", { class: "change" }, c)));
        $("now-note").textContent = status.done + " of " + status.total + " markets done (each training seed with and without the fakes)";
      }
    }

    // the chart: one column per trial, a slot for a trial in flight, and the held-out result
    const points = trials.map((t, i) => ({ label: i === 0 ? "built-in" : "trial " + i, lift: t.summary.lift, cells: flaggedCells(t.summary), trial: t }));
    if (status.phase === "trial" && status.trial >= trials.length) points.push({ label: status.trial === 0 ? "built-in" : "trial " + status.trial, lift: null, cells: run.trainSeeds.map(() => null), pending: true, status });
    if (cheater) points.push({ label: "held out", lift: cheater.lift, cells: flaggedCells(cheater), final: true });
    // while live, leave room for the trials still to come so the chart doesn't reflow every time
    const slots = live && !cheater ? Math.max(points.length, (run.budget || 10) + 2) : points.length;
    const W = 900, H = 300, left = 64, right = 8, top = 12, cellRows = Math.max(run.trainSeeds.length, run.heldOutSeeds.length > 3 && cheater ? run.heldOutSeeds.length : 0);
    const cellH = 14, cellGap = 3, bottom = 28 + cellRows * (cellH + cellGap);
    const svgH = H + Math.max(0, bottom - 80), plotH = svgH - top - bottom, band = (W - left - right) / slots;
    const niceMax = Math.ceil(Math.max(2, ...points.map((p) => p.lift || 0)));
    const y = (v) => top + plotH - (v / niceMax) * plotH;
    const svg = $("chart");
    svg.setAttribute("viewBox", "0 0 " + W + " " + svgH);
    svg.setAttribute("aria-label", "Lift per trial and which training markets flagged the spoofers");
    const step = niceMax <= 4 ? 1 : niceMax <= 10 ? 2 : 5;
    for (let v = 0; v <= niceMax; v += step) {
      svg.append(svgEl("line", { x1: left, x2: W - right, y1: y(v), y2: y(v), stroke: v === 0 ? "var(--axis)" : "var(--grid)", "stroke-width": 1 }));
      const t = svgEl("text", { x: left - 8, y: y(v) + 4, "text-anchor": "end" }); t.textContent = v + "×"; svg.append(t);
    }
    svg.append(svgEl("line", { x1: left, x2: W - right, y1: y(1.5), y2: y(1.5), stroke: "var(--ink-2)", "stroke-width": 1 }));
    const target = svgEl("text", { x: W - right, y: y(1.5) - 6, "text-anchor": "end", style: "fill:var(--ink-2)" });
    target.textContent = "goal: 1.5×"; svg.append(target);
    const tip = $("tip"), wrap = tip.parentElement;
    const show = (p, cx) => {
      tip.replaceChildren();
      if (p.pending) {
        tip.append(el("strong", {}, "running"), el("div", { class: "muted" }, p.label + ": " + p.status.done + " of " + p.status.total + " markets done"));
      } else {
        tip.append(el("strong", {}, x(p.lift)), el("div", { class: "muted" }, p.label + (p.final ? ", submitted tactics on unseen markets" : "")));
        tip.append(el("div", {}, p.cells.filter(Boolean).length + " of " + p.cells.length + " markets flagged"));
      }
      const t = p.final ? run.final : p.pending ? p.status : p.trial;
      const c = changes(t.layers, t.tactics);
      if (c.length) tip.append(el("div", { class: "muted" }, c.join("; ")));
      tip.style.display = "block";
      const box = wrap.getBoundingClientRect(), scale = box.width / W;
      tip.style.left = Math.min(Math.max(0, cx * scale + 16), box.width - 300) + "px";
      tip.style.top = "8px";
    };
    points.forEach((p, i) => {
      const cx = left + band * i + band / 2, bw = Math.min(24, band * 0.5);
      const g = svgEl("g");
      const hit = svgEl("rect", { class: "hit", x: left + band * i, y: top, width: band, height: svgH - top, tabindex: 0 });
      hit.addEventListener("pointerenter", () => show(p, cx));
      hit.addEventListener("focus", () => show(p, cx));
      hit.addEventListener("pointerleave", () => { tip.style.display = "none"; });
      hit.addEventListener("blur", () => { tip.style.display = "none"; });
      g.append(hit);
      if (p.pending) {
        const h = Math.max(6, plotH * (p.status.total ? p.status.done / p.status.total : 0) * 0.5);
        g.append(svgEl("rect", { class: "pending", x: cx - bw / 2, y: y(0) - h, width: bw, height: h, rx: 4, "pointer-events": "none" }));
      } else if (p.lift != null && p.lift > 0) {
        const h = y(0) - y(p.lift), r = Math.min(4, h), x0 = cx - bw / 2, y0 = y(p.lift);
        g.append(svgEl("path", { class: "bar" + (p.final ? " final" : ""), "pointer-events": "none",
          d: `M${x0},${y(0)} V${y0 + r} Q${x0},${y0} ${x0 + r},${y0} H${x0 + bw - r} Q${x0 + bw},${y0} ${x0 + bw},${y0 + r} V${y(0)} Z` }));
      }
      const lab = svgEl("text", { x: cx, y: y(0) + 16, "text-anchor": "middle", style: p.final ? "fill:var(--ink)" : "" });
      lab.textContent = p.label; g.append(lab);
      p.cells.forEach((flag, j) => {
        const cy = y(0) + 26 + j * (cellH + cellGap);
        g.append(svgEl("rect", { x: cx - cellH / 2, y: cy, width: cellH, height: cellH, rx: 3, "pointer-events": "none",
          fill: flag ? "var(--critical)" : "none", stroke: flag ? "none" : "var(--axis)", "stroke-width": 1.5,
          "stroke-dasharray": flag == null ? "2 2" : "none" }));
        if (flag != null) {
          const mark = svgEl("text", { x: cx, y: cy + 10.5, "text-anchor": "middle", "pointer-events": "none",
            style: "font-size:9px;font-weight:700;fill:" + (flag ? "#fff" : "var(--muted)") });
          mark.textContent = flag ? "!" : "✓"; g.append(mark);
        }
      });
      svg.append(g);
    });
    const cellLabel = svgEl("text", { x: left - 8, y: y(0) + 37, "text-anchor": "end" });
    cellLabel.textContent = "markets"; svg.append(cellLabel);

    // table view of the chart
    const table = $("trial-table"), head = el("tr");
    ["", "Lift", "Markets flagged", "Real shares, fakes", "Real shares, control"].forEach((h) => head.append(el("th", {}, h)));
    table.append(head);
    points.filter((p) => !p.pending).forEach((p) => {
      const s = p.final ? cheater : p.trial.summary, tr = el("tr");
      [p.label, x(p.lift), p.cells.filter(Boolean).length + " of " + p.cells.length, fmt(s.shares), fmt(s.controlShares)].forEach((v) => tr.append(el("td", {}, v)));
      table.append(tr);
    });

    // the trials, in Claude's words, newest first while live
    const order = trials.map((t, i) => [t, i]);
    if (live && !cheater) order.reverse();
    order.forEach(([t, i]) => {
      const s = t.summary, card = el("div", { class: "card" }), headRow = el("div", { class: "trial-head" });
      headRow.append(el("span", { class: "n" }, i === 0 ? "The built-in spoofer" : "Trial " + i), el("span", { class: "lift" }, x(s.lift)),
        el("span", { class: "pill " + (s.flaggedMarkets ? "bad" : "ok") }, s.flaggedMarkets ? "flagged in " + s.flaggedMarkets + " of " + s.rows.length + " markets" : "not flagged"));
      if (s.lift != null && s.lift < 1.5) headRow.append(el("span", { class: "pill" }, "fakes below the 1.5× goal"));
      card.append(headRow);
      card.append(el("p", { class: "hypothesis" }, i === 0 ? "Run by the harness first, so Claude starts from what the built-in spoofer gets." : (t.hypothesis || "")));
      const ch = el("div", { class: "changes" });
      changes(t.layers, t.tactics).forEach((c) => ch.append(el("span", { class: "change" }, c)));
      if (ch.childElementCount) card.append(ch);
      const det = el("details"), tab = el("table"), hr = el("tr");
      ["Seed", "Verdict", "Real shares, fakes", "Control", "Filled, fakes", "Control", "Schemes", "Fakes hit", "Spoofer P&L"].forEach((h) => hr.append(el("th", {}, h)));
      tab.append(hr);
      s.rows.forEach((r) => {
        const tr = el("tr");
        [r.seed, r.spoofed.flagged.length ? "flagged" : "clear", fmt(r.spoofed.shares), fmt(r.control.shares), fmt(100 * r.spoofed.fillRate) + "%",
         fmt(100 * r.control.fillRate) + "%", fmt(r.spoofed.episodes), fmt(r.spoofed.fakesFilled) + " of " + fmt(r.spoofed.fakes), "$" + signed(r.spoofed.pnl)]
          .forEach((v) => tr.append(el("td", {}, String(v))));
        tab.append(tr);
      });
      const scroll = el("div", { class: "table-scroll" }); scroll.append(tab);
      det.append(el("summary", {}, "Markets"), scroll);
      const alerts = s.rows.flatMap((r) => r.spoofed.alerts);
      if (alerts.length) det.append(el("p", { class: "alert" }, "What the alert said: " + alerts[0]));
      card.append(det);
      $("trials").append(card);
    });
    if (!trials.length) $("trials").append(el("p", { class: "note" }, "Nothing yet: the built-in spoofer runs first."));

    $("final-section").hidden = !run.final;
    if (run.final) {
      $("reasoning").textContent = run.final.reasoning || "(none given)";
      changes(run.final.layers, run.final.tactics).forEach((c) => $("final-changes").append(el("span", { class: "change" }, c)));
    }

    $("held-section").hidden = !cheater;
    if (cheater) {
      $("held-note").textContent = "Both spoofers on the same " + plural(run.heldOutSeeds.length, "unseen market") + ", each run with its fakes and as the honest control. Intervals are paired t intervals over the seeds.";
      const ht = $("held-table"), hh = el("tr");
      ["Spoofer", "Flagged", "Real shares, fakes vs control", "Lift", "Extra shares per market, 95% CI", "Extra fill rate", "Extra P&L per market", "Seeds where fakes helped"].forEach((h) => hh.append(el("th", {}, h)));
      ht.append(hh);
      const band2 = (m) => m[1] == null ? signed(m[0]) : signed(m[0]) + " (" + signed(m[1]) + " to " + signed(m[2]) + ")";
      [["Claude's tactics", cheater], ["Built-in", builtIn]].forEach(([name, h]) => {
        const p = h.paired, tr = el("tr");
        [name, h.flaggedSpoofers + " of " + spoofers, fmt(h.shares) + " vs " + fmt(h.controlShares), x(h.lift), band2(p.shares),
         (p.fillPoints[0] >= 0 ? "+" : "−") + fmt(Math.abs(p.fillPoints[0]), 1) + " pts", "$" + band2(p.pnl), p.better + " of " + p.seeds]
          .forEach((v) => tr.append(el("td", {}, v)));
        ht.append(tr);
      });
    }

    [["Lift", "how many shares the spoofers' real orders traded with fakes, divided by the same spoofers on the same seed with no fakes. The control keeps the timing and real orders, so the fakes are the only difference."],
     ["Flagged", "the " + run.detector + " detector raised an alert on the spoofer. It reads only the order event log; a separate answer key says who cheated."],
     ["What Claude sees", "after each trial, whether each spoofer was flagged, the alert's text, and the lift. That's a strong attacker: it's like a trader who reads every enforcement notice. A blind mode would show only caught or not."],
     ["What Claude controls", "the spoofer's tactics, never individual orders. The Java bot runs them, so every fake is still labelled and cancelled, and every market replays exactly from its seed."]]
      .forEach(([k, v]) => { const p = el("p"); p.append(el("strong", {}, k + ": "), document.createTextNode(v)); $("method").append(p); });
  }

  // live mode: poll the run's progress file and redraw when it changes
  const source = document.body.dataset.live;
  if (!source) { render(JSON.parse($("data").textContent)); return; }
  $("live").hidden = false;
  $("footer").textContent = "Live from this run's progress.json. When the run ends, tools/agent/viewer.py turns it into a static page.";
  let last = null, updatedAt = null, phase = null;
  const tick = () => {
    if (updatedAt == null) return;
    const s = Math.max(0, Math.round(Date.now() / 1000 - updatedAt));
    $("live-ago").textContent = phase === "done" || phase === "failed" ? "" : "updated " + (s < 60 ? s + "s" : Math.floor(s / 60) + "m") + " ago";
  };
  const describe = (run) => {
    const st = run.status || {};
    if (st.phase === "done") return "Finished";
    if (st.phase === "failed") return "Stopped: " + (st.error || "unknown error");
    if (st.phase === "starting") return "Live · starting the markets";
    if (st.phase === "thinking") return "Live · Claude is deciding what to try next";
    if (st.phase === "grading") return "Live · grading " + st.who + " on unseen markets, " + st.done + " of " + st.total + " done";
    if (st.phase === "trial") return "Live · " + (st.trial === 0 ? "the built-in spoofer" : "trial " + st.trial) + ", " + st.done + " of " + st.total + " markets done";
    return "Live";
  };
  const poll = async () => {
    try {
      const response = await fetch(source, { cache: "no-store" });
      const text = await response.text();
      if (text !== last) {
        last = text;
        const run = JSON.parse(text);
        phase = (run.status || {}).phase;
        updatedAt = (run.status || {}).updatedAt;
        $("live-text").textContent = describe(run);
        $("live-dot").className = "dot " + (phase === "done" ? "done" : phase === "failed" ? "failed" : "on");
        render(run);
      }
      tick();
    } catch (e) {
      $("live-text").textContent = "Lost the run: it may have finished or stopped. The last state is shown below.";
      $("live-dot").className = "dot failed";
      return;
    }
    if (phase !== "done" && phase !== "failed") setTimeout(poll, 2000);
  };
  setInterval(tick, 1000);
  poll();
})();
</script>
"""

INDEX_BODY = """
<div class="eyebrow"><a href="https://github.com/nishantshah0/cross-book">CrossBook</a></div>
<h1>AI cheater runs</h1>
<p class="lede">Each run gives Claude a spoofer bot, a frozen spoofing detector and ten tries to get past it, then grades its tactics on markets it never saw.</p>
<div class="runs trials" id="runs" style="margin-top:24px">__RUNS__</div>
<footer>Generated by <code>tools/agent/viewer.py</code>.</footer>
"""


def safe_json(data):
    """JSON that can't close the script tag it sits in."""
    return json.dumps(data).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")


def live_page():
    """The run page in live mode: it polls progress.json beside it and redraws as the run goes."""
    return (PAGE.replace("__TITLE__", "Cheater run, live")
            .replace("__DESCRIPTION__", "Claude vs a frozen spoofing detector, live.")
            .replace("<body>", '<body data-live="progress.json">')
            .replace("__BODY__", RUN_BODY)
            .replace("__SCRIPT__", RUN_SCRIPT.replace("__DATA__", "null")))


def build_run(run_dir):
    result = load(run_dir)
    target = os.path.join(OUT, result["name"])
    os.makedirs(target, exist_ok=True)
    cheater = result["heldOut"]["cheater"]
    description = "Claude vs a frozen spoofing detector: %d of %d spoofers flagged on unseen markets, lift %s." % (
        cheater["flaggedSpoofers"], 2 * len(result["heldOutSeeds"]),
        "%.2fx" % cheater["lift"] if cheater["lift"] else "n/a")
    page = (PAGE.replace("__TITLE__", "Cheater run " + html.escape(result["name"]))
            .replace("__DESCRIPTION__", html.escape(description))
            .replace("__BODY__", RUN_BODY)
            .replace("__SCRIPT__", RUN_SCRIPT.replace("__DATA__", safe_json(result))))
    with open(os.path.join(target, "index.html"), "w") as f:
        f.write(page)
    with open(os.path.join(target, "result.json"), "w") as f:
        json.dump(result, f, indent=1)
    print("wrote %s" % os.path.relpath(os.path.join(target, "index.html"), ROOT))


def build_index():
    cards = []
    for path in sorted(glob.glob(os.path.join(OUT, "*", "result.json")), reverse=True):
        with open(path) as f:
            r = json.load(f)
        c, b = r["heldOut"]["cheater"], r["heldOut"]["built-in"]
        n = 2 * len(r["heldOutSeeds"])
        who = (r.get("model") or "scripted") + (" via Claude Code" if r.get("via") == "claude-code" else "")
        cards.append('<a href="%s/"><div class="card"><div class="trial-head"><span class="n">%s</span>'
                     '<span class="pill %s">%d of %d flagged held out</span><span class="lift">lift %s</span></div>'
                     '<p class="note">%s · %d trials · built-in spoofer: %d of %d flagged</p></div></a>' % (
                         html.escape(r["name"]), html.escape(r["name"]), "bad" if c["flaggedSpoofers"] else "ok",
                         c["flaggedSpoofers"], n, "%.2f×" % c["lift"] if c["lift"] else "n/a", html.escape(who),
                         len(r["trials"]) - 1, b["flaggedSpoofers"], n))
    page = (PAGE.replace("__TITLE__", "AI cheater runs")
            .replace("__DESCRIPTION__", "Claude tries to get a spoofer past CrossBook's detector.")
            .replace("__BODY__", INDEX_BODY.replace("__RUNS__", "\n".join(cards) or "<p>No runs yet.</p>"))
            .replace("__SCRIPT__", ""))
    with open(os.path.join(OUT, "index.html"), "w") as f:
        f.write(page)
    print("wrote %s" % os.path.relpath(os.path.join(OUT, "index.html"), ROOT))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("runs", nargs="*", help="run directories under target/agent/")
    parser.add_argument("--all", action="store_true", help="every run in target/agent/ that finished")
    parser.add_argument("--out", help="where to write the pages (default docs/runs/)")
    args = parser.parse_args()
    global OUT
    if args.out:
        OUT = os.path.abspath(args.out)
    runs = args.runs or ([d for d in sorted(glob.glob(os.path.join(SOURCE, "*")))
                          if os.path.exists(os.path.join(d, "result.json"))] if args.all else [])
    if not runs:
        sys.exit("name a run directory, or pass --all")
    for run in runs:
        build_run(run)
    build_index()


if __name__ == "__main__":
    main()
