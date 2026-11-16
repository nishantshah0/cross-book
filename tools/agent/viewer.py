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
.hero .value { font-size: 52px; }
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
<h1 id="headline"></h1>
<p class="lede" id="lede"></p>
<div class="tiles" id="tiles"></div>
<p class="meta" id="meta"></p>

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

<h2>Claude's write-up, on submitting</h2>
<div class="card"><p class="reasoning" id="reasoning"></p><div class="changes" id="final-changes"></div></div>

<h2>Held out</h2>
<div class="card">
  <p class="note" id="held-note"></p>
  <div class="table-scroll"><table id="held-table"></table></div>
</div>

<h2>How to read this</h2>
<div class="card note" id="method"></div>

<footer>Generated by <code>tools/agent/viewer.py</code> from <a href="result.json">result.json</a>. Every market here replays exactly from its seed.</footer>
"""

RUN_SCRIPT = r"""
<script type="application/json" id="data">__DATA__</script>
<script>
(() => {
  const run = JSON.parse(document.getElementById("data").textContent);
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
  const BUILT_IN = run.trials[0].tactics;
  const BUILT_IN_LAYERS = run.trials[0].layers;
  const unit = { fakeSize: (v) => fmt(v * 100, 1) + "% of cash", realSize: (v) => fmt(v * 100, 1) + "% of cash",
                 fakeOffsetCents: (v) => v + "¢", fakeGapCents: (v) => v + "¢", stepMinMs: (v) => fmt(v / 1000, 1) + " s",
                 stepMaxMs: (v) => fmt(v / 1000, 1) + " s", restMs: (v) => fmt(v / 1000) + " s", realFirst: (v) => v ? "yes" : "no",
                 layers: (v) => String(v) };
  const changes = (layers, tactics) => {
    const out = [];
    const all = Object.assign({ layers }, tactics), base = Object.assign({ layers: BUILT_IN_LAYERS }, BUILT_IN);
    for (const k of Object.keys(unit)) if (all[k] !== base[k]) out.push(k + " " + unit[k](base[k]) + " → " + unit[k](all[k]));
    return out;
  };
  const cheater = run.heldOut.cheater, builtIn = run.heldOut["built-in"];
  const spoofers = 2 * run.heldOutSeeds.length;
  const evaded = cheater.flaggedSpoofers === 0;
  const working = cheater.lift != null && cheater.lift >= 1.5;
  const ci = cheater.paired.shares;

  document.title = document.title;
  // the headline claims only what the held-out interval supports
  const proven = ci[1] != null && ci[1] > 0;
  const plural = (n, word) => n + " " + word + (n === 1 ? "" : "s");
  $("headline").textContent = !evaded ? "The detector held: Claude's spoofer was still caught"
    : working && proven ? "Claude got a spoofer past the detector, and the fakes still worked"
    : working ? "Claude got a spoofer past the detector; whether the fakes still work isn't settled"
    : "Claude got a spoofer past the detector, but the fakes stopped working";
  $("lede").textContent = "The detector was frozen. Claude played the spoofer, tried " + plural(run.trials.length - 1, "set")
    + " of tactics on " + plural(run.trainSeeds.length, "training market") + ", saw what got flagged and why, then submitted one. "
    + "Below are its submitted tactics on " + plural(run.heldOutSeeds.length, "market") + " it never saw, next to the built-in spoofer on the same markets.";

  const tile = (label, value, sub, hero) => {
    const t = el("div", { class: "card tile" + (hero ? " hero" : "") });
    t.append(el("div", { class: "label" }, label), el("div", { class: "value" }, value), el("div", { class: "sub" }, sub));
    return t;
  };
  $("tiles").append(
    tile("Claude's spoofers flagged, held out", cheater.flaggedSpoofers + " of " + spoofers, "built-in spoofer: " + builtIn.flaggedSpoofers + " of " + spoofers, true),
    tile("What the fakes bought", x(cheater.lift), "real-order shares vs the honest control; built-in " + x(builtIn.lift)),
    tile("Extra shares per market", signed(ci[0]), ci[1] == null ? "one seed, no interval" : "95% CI " + signed(ci[1]) + " to " + signed(ci[2])),
    tile("Seeds where the fakes helped", cheater.paired.better + " of " + cheater.paired.seeds, "built-in: " + builtIn.paired.better + " of " + builtIn.paired.seeds));
  $("meta").textContent = [run.model ? run.model + (run.via === "claude-code" ? " via Claude Code" : " via the API") : "scripted dry run",
    "detector " + run.detector, "training seeds " + run.trainSeeds.join(", "),
    "held-out seeds " + run.heldOutSeeds[0] + "–" + run.heldOutSeeds[run.heldOutSeeds.length - 1],
    run.ticks / 4 + " minutes of market time each", "run " + run.name].join(" · ");

  // the chart: one column per trial, plus the submitted tactics held out
  const points = run.trials.map((t, i) => ({ label: i === 0 ? "built-in" : "trial " + i, lift: t.summary.lift,
    cells: t.summary.rows.map((r) => r.spoofed.flagged.length > 0), trial: t, index: i }));
  points.push({ label: "held out", lift: cheater.lift, cells: cheater.rows.map((r) => r.spoofed.flagged.length > 0), final: true });
  const W = 900, H = 300, left = 64, right = 8, top = 12, cellRows = Math.max(...points.map((p) => p.cells.length));
  const cellH = 14, cellGap = 3, bottom = 28 + cellRows * (cellH + cellGap);
  const plotH = H - top - bottom, band = (W - left - right) / points.length;
  const maxLift = Math.max(2, ...points.map((p) => p.lift || 0));
  const niceMax = Math.ceil(maxLift);
  const y = (v) => top + plotH - (v / niceMax) * plotH;
  const svg = $("chart");
  svg.setAttribute("viewBox", "0 0 " + W + " " + H);
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
    tip.append(el("strong", {}, x(p.lift)), el("div", { class: "muted" }, p.label + (p.final ? ", submitted tactics on unseen markets" : "")));
    const flagged = p.cells.filter(Boolean).length;
    tip.append(el("div", {}, flagged + " of " + p.cells.length + " markets flagged"));
    const t = p.final ? { layers: run.final.layers, tactics: run.final.tactics } : p.trial;
    const c = changes(t.layers, t.tactics);
    if (c.length) tip.append(el("div", { class: "muted" }, c.join("; ")));
    tip.style.display = "block";
    const box = wrap.getBoundingClientRect(), scale = box.width / W;
    tip.style.left = Math.min(Math.max(0, cx * scale - 150), box.width - 300) + "px";
    tip.style.top = "8px";
  };
  points.forEach((p, i) => {
    const cx = left + band * i + band / 2, bw = Math.min(24, band * 0.5);
    const g = svgEl("g");
    const hit = svgEl("rect", { class: "hit", x: left + band * i, y: top, width: band, height: H - top, tabindex: 0 });
    hit.addEventListener("pointerenter", () => show(p, cx));
    hit.addEventListener("focus", () => show(p, cx));
    hit.addEventListener("pointerleave", () => { tip.style.display = "none"; });
    hit.addEventListener("blur", () => { tip.style.display = "none"; });
    g.append(hit);
    if (p.lift != null && p.lift > 0) {
      const h = y(0) - y(p.lift), r = Math.min(4, h);
      const x0 = cx - bw / 2, y0 = y(p.lift);
      g.append(svgEl("path", { class: "bar" + (p.final ? " final" : ""), "pointer-events": "none",
        d: `M${x0},${y(0)} V${y0 + r} Q${x0},${y0} ${x0 + r},${y0} H${x0 + bw - r} Q${x0 + bw},${y0} ${x0 + bw},${y0 + r} V${y(0)} Z` }));
    }
    const lab = svgEl("text", { x: cx, y: y(0) + 16, "text-anchor": "middle", style: p.final ? "fill:var(--ink)" : "" });
    lab.textContent = p.label; g.append(lab);
    p.cells.forEach((flag, j) => {
      const cy = y(0) + 26 + j * (cellH + cellGap);
      g.append(svgEl("rect", { x: cx - cellH / 2, y: cy, width: cellH, height: cellH, rx: 3, "pointer-events": "none",
        fill: flag ? "var(--critical)" : "none", stroke: flag ? "none" : "var(--axis)", "stroke-width": 1.5 }));
      const mark = svgEl("text", { x: cx, y: cy + 10.5, "text-anchor": "middle", "pointer-events": "none",
        style: "font-size:9px;font-weight:700;fill:" + (flag ? "#fff" : "var(--muted)") });
      mark.textContent = flag ? "!" : "✓"; g.append(mark);
    });
    svg.append(g);
  });
  const cellLabel = svgEl("text", { x: left - 8, y: y(0) + 37, "text-anchor": "end" });
  cellLabel.textContent = "markets"; svg.append(cellLabel);

  // table view of the chart
  const table = $("trial-table");
  const head = el("tr");
  ["", "Lift", "Markets flagged", "Real shares, fakes", "Real shares, control"].forEach((h) => head.append(el("th", {}, h)));
  table.append(head);
  points.forEach((p) => {
    const rows = p.final ? cheater : p.trial.summary;
    const tr = el("tr");
    [p.label, x(p.lift), p.cells.filter(Boolean).length + " of " + p.cells.length, fmt(rows.shares), fmt(rows.controlShares)]
      .forEach((v) => tr.append(el("td", {}, v)));
    table.append(tr);
  });

  // the trials, in Claude's words
  run.trials.forEach((t, i) => {
    const s = t.summary, card = el("div", { class: "card" });
    const headRow = el("div", { class: "trial-head" });
    headRow.append(el("span", { class: "n" }, i === 0 ? "The built-in spoofer" : "Trial " + i),
      el("span", { class: "lift" }, x(s.lift)),
      el("span", { class: "pill " + (s.flaggedMarkets ? "bad" : "ok") }, s.flaggedMarkets ? "flagged in " + s.flaggedMarkets + " of " + s.rows.length + " markets" : "not flagged"));
    if (s.lift != null && s.lift < 1.5) headRow.append(el("span", { class: "pill" }, "fakes below the 1.5× goal"));
    card.append(headRow);
    if (t.hypothesis) card.append(el("p", { class: "hypothesis" }, i === 0 ? "Run by the harness first, so Claude starts from what the built-in spoofer gets." : t.hypothesis));
    const ch = el("div", { class: "changes" });
    changes(t.layers, t.tactics).forEach((c) => ch.append(el("span", { class: "change" }, c)));
    if (ch.childElementCount) card.append(ch);
    const det = el("details"), sum = el("summary", {}, "Markets");
    const tab = el("table"), hr = el("tr");
    ["Seed", "Verdict", "Real shares, fakes", "Control", "Filled, fakes", "Control", "Schemes", "Fakes hit", "Spoofer P&L"].forEach((h) => hr.append(el("th", {}, h)));
    tab.append(hr);
    s.rows.forEach((r) => {
      const tr = el("tr");
      [r.seed, r.spoofed.flagged.length ? "flagged" : "clear", fmt(r.spoofed.shares), fmt(r.control.shares),
       fmt(100 * r.spoofed.fillRate) + "%", fmt(100 * r.control.fillRate) + "%", fmt(r.spoofed.episodes),
       fmt(r.spoofed.fakesFilled) + " of " + fmt(r.spoofed.fakes), "$" + signed(r.spoofed.pnl)].forEach((v) => tr.append(el("td", {}, String(v))));
      tab.append(tr);
    });
    const scroll = el("div", { class: "table-scroll" }); scroll.append(tab);
    det.append(sum, scroll);
    const alerts = s.rows.flatMap((r) => r.spoofed.alerts);
    if (alerts.length) det.append(el("p", { class: "alert" }, "What the alert said: " + alerts[0]));
    card.append(det);
    $("trials").append(card);
  });

  $("reasoning").textContent = run.final.reasoning || "(none given)";
  changes(run.final.layers, run.final.tactics).forEach((c) => $("final-changes").append(el("span", { class: "change" }, c)));

  // held out
  $("held-note").textContent = "Both spoofers on the same " + run.heldOutSeeds.length + " unseen markets, each run with its fakes and as the honest control. "
    + "Intervals are paired t intervals over the seeds.";
  const ht = $("held-table"), hh = el("tr");
  ["Spoofer", "Flagged", "Real shares, fakes vs control", "Lift", "Extra shares per market, 95% CI", "Extra fill rate", "Extra P&L per market", "Seeds where fakes helped"].forEach((h) => hh.append(el("th", {}, h)));
  ht.append(hh);
  [["Claude's tactics", cheater], ["Built-in", builtIn]].forEach(([name, h]) => {
    const p = h.paired, tr = el("tr");
    const band = (m) => m[1] == null ? signed(m[0]) : signed(m[0]) + " (" + signed(m[1]) + " to " + signed(m[2]) + ")";
    [name, h.flaggedSpoofers + " of " + spoofers, fmt(h.shares) + " vs " + fmt(h.controlShares), x(h.lift), band(p.shares),
     (p.fillPoints[0] >= 0 ? "+" : "−") + fmt(Math.abs(p.fillPoints[0]), 1) + " pts", "$" + band(p.pnl), p.better + " of " + p.seeds]
      .forEach((v) => tr.append(el("td", {}, v)));
    ht.append(tr);
  });

  const m = $("method");
  [["Lift", "how many shares the spoofers' real orders traded with fakes, divided by the same spoofers on the same seed with no fakes. The control keeps the timing and real orders, so the fakes are the only difference."],
   ["Flagged", "the " + run.detector + " detector raised an alert on the spoofer. It reads only the order event log; a separate answer key says who cheated."],
   ["What Claude saw", "after each trial, whether each spoofer was flagged, the alert's text, and the lift. That's a strong attacker: it's like a trader who reads every enforcement notice. A blind mode would show only caught or not."],
   ["What Claude controlled", "the spoofer's tactics, never individual orders. The Java bot runs them, so every fake is still labelled and cancelled, and every market replays exactly from its seed."]]
    .forEach(([k, v]) => { const p = el("p"); p.append(el("strong", {}, k + ": "), document.createTextNode(v)); m.append(p); });
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
