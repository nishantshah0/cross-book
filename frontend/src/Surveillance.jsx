import { useEffect, useMemo, useState } from "react";
import { api } from "./api.js";
import Icon from "./Icon.jsx";

// The surveillance desk, laid out like the alert workbenches exchanges use: an alert
// queue with the evidence behind each alert, and a red-team view where Claude plays the
// spoofer against the frozen detector (tools/agent/cheater.py). Runs are read from the
// files the cheater writes, polled while one is going.

const BUILT_IN = {
  layers: 4,
  fakeSize: 0.08,
  fakeOffsetCents: 1,
  fakeGapCents: 1,
  realSize: 0.02,
  realFirst: false,
  stepMinMs: 1000,
  stepMaxMs: 3000,
  restMs: 40000,
};
const GOAL = 1.5;
const STATIC = import.meta.env.VITE_STATIC === "1";
const REPO = "https://github.com/nishantshah0/cross-book";

// Locally the desk reads the app's API. The public build (GitHub Pages) has no app
// behind it, so it reads the runs committed to docs/runs/, and only those.
function load(path) {
  if (!STATIC) return api(path);
  const file =
    path === "/red-team/runs"
      ? "runs.json"
      : "runs/" + path.split("/").pop() + ".json";
  return fetch(import.meta.env.BASE_URL + "data/" + file).then((r) => {
    if (!r.ok) throw new Error("Run not found");
    return r.json();
  });
}

const num = (n, digits = 0) =>
  n == null
    ? "–"
    : Number(n).toLocaleString("en-US", {
        minimumFractionDigits: digits,
        maximumFractionDigits: digits,
      });
const times = (n) => (n == null ? "–" : num(n, 2) + "×");
const plusMinus = (n, digits = 0) =>
  n == null ? "–" : (n > 0 ? "+" : n < 0 ? "−" : "") + num(Math.abs(n), digits);
const pct = (n) => (n == null ? "–" : num(100 * n) + "%");
const plural = (n, word) => n + " " + word + (n === 1 ? "" : "s");
const ago = (ms) => {
  const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
  if (s < 60) return s + "s ago";
  if (s < 3600) return Math.floor(s / 60) + "m ago";
  if (s < 86400) return Math.floor(s / 3600) + "h ago";
  return Math.floor(s / 86400) + "d ago";
};
const runDate = (name) => {
  const m = /^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})/.exec(name);
  if (!m) return name;
  const d = new Date(+m[1], m[2] - 1, +m[3], +m[4], +m[5]);
  return d.toLocaleString("en-US", {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
};

const KNOBS = {
  layers: ["fakes", (v) => String(v)],
  fakeSize: ["fake size", (v) => num(v * 100, 1) + "%"],
  fakeOffsetCents: ["offset", (v) => v + "¢"],
  fakeGapCents: ["gap", (v) => v + "¢"],
  realSize: ["real size", (v) => num(v * 100, 1) + "%"],
  realFirst: ["real first", (v) => (v ? "yes" : "no")],
  stepMinMs: ["step min", (v) => num(v / 1000, 1) + "s"],
  stepMaxMs: ["step max", (v) => num(v / 1000, 1) + "s"],
  restMs: ["rest", (v) => num(v / 1000) + "s"],
};
function changes(layers, tactics) {
  const all = { layers, ...(tactics || {}) };
  return Object.entries(KNOBS)
    .filter(([k]) => all[k] !== undefined && all[k] !== BUILT_IN[k])
    .map(([k, [label, show]]) => ({
      key: k,
      text: label + " " + show(BUILT_IN[k]) + " → " + show(all[k]),
    }));
}

function Changes({ layers, tactics, empty }) {
  const list = changes(layers, tactics);
  if (!list.length)
    return empty ? <span className="muted">{empty}</span> : null;
  return (
    <span className="knobs">
      {list.map((c) => (
        <code key={c.key}>{c.text}</code>
      ))}
    </span>
  );
}

function Verdict({ flagged, of }) {
  return flagged ? (
    <span className="tag flagged">
      flagged {flagged}/{of}
    </span>
  ) : (
    <span className="tag clear">
      clear {of}/{of}
    </span>
  );
}

function usePoll(load, ms, deps) {
  const [state, setState] = useState({ data: null, error: null });
  useEffect(() => {
    let alive = true;
    let timer;
    const tick = async () => {
      try {
        const data = await load();
        if (alive) setState({ data, error: null });
      } catch (e) {
        if (alive) setState((s) => ({ data: s.data, error: e.message }));
      }
      if (alive && ms) timer = setTimeout(tick, ms);
    };
    tick();
    return () => {
      alive = false;
      clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return state;
}

export default function Surveillance() {
  const initial = new URLSearchParams(window.location.search);
  const [tab, setTab] = useState(
    initial.get("tab") === "alerts" && !STATIC ? "alerts" : "red-team",
  );
  const choose = (next) => {
    setTab(next);
    const url = new URL(window.location.href);
    url.searchParams.set("tab", next);
    window.history.replaceState(null, "", url);
  };
  return (
    <div className="terminal surveillance">
      <header className="topbar">
        <a
          className="brand"
          href={STATIC ? REPO : "/"}
          aria-label="CrossBook home"
        >
          CrossBook
        </a>
        <h1>Surveillance</h1>
        <nav className="desk-tabs" aria-label="Surveillance views">
          {(STATIC
            ? [["red-team", "Red team"]]
            : [
                ["red-team", "Red team"],
                ["alerts", "Alerts"],
              ]
          ).map(([id, label]) => (
            <button
              key={id}
              className={tab === id ? "active" : ""}
              aria-current={tab === id ? "page" : undefined}
              onClick={() => choose(id)}
            >
              {label}
            </button>
          ))}
        </nav>
        <div className="topbar-right">
          <a
            className="secondary topbar-link"
            href={STATIC ? REPO : "/terminal"}
          >
            {STATIC ? "GitHub" : "Terminal"}
          </a>
        </div>
      </header>
      {STATIC && (
        <p className="notice static-note">
          Recorded runs from the repo: pick one and press Replay. Clone it to
          watch runs live and to see the alert queue.
        </p>
      )}
      <main>{tab === "alerts" ? <Alerts /> : <RedTeam />}</main>
    </div>
  );
}

/* ---------- red team ---------- */

function RedTeam() {
  const runs = usePoll(() => load("/red-team/runs"), STATIC ? 0 : 4000, []);
  const [picked, setPicked] = useState(
    new URLSearchParams(window.location.search).get("run"),
  );
  const list = runs.data || [];
  const selected = picked || list[0]?.name || null;
  const pick = (name) => {
    setPicked(name);
    const url = new URL(window.location.href);
    url.searchParams.set("run", name);
    window.history.replaceState(null, "", url);
  };
  if (runs.data && !list.length)
    return (
      <section className="empty-desk">
        <h2>No red-team runs yet</h2>
        <p className="muted">
          Start one and it shows up here live. Claude plays a spoofer against
          the frozen detector, ten tries, then gets graded on markets it never
          saw.
        </p>
        <code>
          caffeinate -i uv run tools/agent/cheater.py --via claude-code
        </code>
      </section>
    );
  return (
    <div className="desk-grid">
      <aside className="panel run-list" aria-label="Runs">
        <div className="panel-heading">
          <h2>Runs</h2>
          <span className="muted">{list.length || ""}</span>
        </div>
        {runs.error && !runs.data && <p className="desk-error">{runs.error}</p>}
        <ul>
          {list.map((r) => (
            <li key={r.name}>
              <button
                className={r.name === selected ? "run active" : "run"}
                onClick={() => pick(r.name)}
              >
                <span className="run-top">
                  <PhaseDot phase={r.phase} />
                  <strong>{runDate(r.name)}</strong>
                  {runTags(r).map((t) => (
                    <span className="tag" key={t}>
                      {t}
                    </span>
                  ))}
                </span>
                <span className="run-sub">
                  {r.flaggedHeldOut != null
                    ? r.flaggedHeldOut +
                      "/" +
                      r.spoofersHeldOut +
                      " flagged · " +
                      times(r.lift)
                    : phaseLabel(r.phase) +
                      " · " +
                      r.trials +
                      "/" +
                      r.budget +
                      " trials used"}
                </span>
                <span className="run-sub faint">
                  {who(r)} · {ago(r.updatedAt)}
                </span>
              </button>
            </li>
          ))}
        </ul>
      </aside>
      {selected ? <Run name={selected} key={selected} /> : <div />}
    </div>
  );
}

function runTags(r) {
  return [
    r.searcher === "scripted" && "dry",
    r.searcher === "random" && "random",
    r.blind && "blind",
    r.detector &&
      r.detector !== "spoofing-rules" &&
      r.detector.replace("spoofing-rules-", ""),
  ].filter(Boolean);
}

function who(r) {
  if (r.searcher === "random") return "random search";
  if (!r.model) return "scripted";
  return r.model + (r.via === "claude-code" ? " · Claude Code" : "");
}

// A finished run, as it looked partway through: step k has the first k trials done and
// the next one running; then grading; then the end. Replay steps through these.
function replayView(run, step) {
  const n = run.trials.length;
  const markets = 2 * run.trainSeeds.length;
  if (step < n) {
    const next = run.trials[step];
    return {
      ...run,
      trials: run.trials.slice(0, step),
      final: null,
      heldOut: null,
      status: {
        phase: "trial",
        replay: true,
        trial: step,
        hypothesis: next.hypothesis,
        layers: next.layers,
        tactics: next.tactics,
        done: markets / 2,
        total: markets,
      },
    };
  }
  if (step === n && run.final)
    return {
      ...run,
      heldOut: null,
      status: {
        phase: "grading",
        replay: true,
        who:
          run.searcher === "random"
            ? "the best random draw"
            : "Claude's tactics",
        done: run.heldOutSeeds.length,
        total: 2 * run.heldOutSeeds.length,
      },
    };
  return run;
}

const LIVE = new Set(["starting", "trial", "thinking", "grading"]);
function phaseLabel(phase) {
  return (
    {
      starting: "Starting",
      trial: "Running a trial",
      thinking: "Claude is deciding",
      grading: "Grading held out",
      done: "Finished",
      failed: "Stopped",
    }[phase] || phase
  );
}
function PhaseDot({ phase }) {
  return (
    <i
      className={
        "phase-dot " +
        (LIVE.has(phase) ? "live" : phase === "failed" ? "failed" : "done")
      }
      aria-hidden="true"
    />
  );
}

function Run({ name }) {
  const [live, setLive] = useState(!STATIC);
  const { data, error } = usePoll(
    async () => {
      const r = await load("/red-team/runs/" + name);
      setLive(!STATIC && LIVE.has(r.status?.phase));
      return r;
    },
    live ? 2000 : 0,
    [name, live],
  );
  const autoplay = new URLSearchParams(window.location.search).has("replay");
  const [replay, setReplay] = useState(autoplay ? 0 : null);
  const steps = data ? data.trials.length + 2 : 0;
  useEffect(() => {
    if (replay == null || !data) return;
    if (replay >= steps - 1) return setReplay(null);
    const t = setTimeout(
      () => setReplay(replay + 1),
      replay === 0 ? 2600 : 3400,
    );
    return () => clearTimeout(t);
  }, [replay, data, steps]);
  const [, redraw] = useState(0);
  useEffect(() => {
    const t = setInterval(() => redraw((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, []);
  if (!data)
    return (
      <section className="run-main">
        <p className="desk-error">{error || "Loading…"}</p>
      </section>
    );
  const run = replay == null ? data : replayView(data, replay);
  const finished = data.status?.phase === "done";
  const status = run.status || { phase: "done" };
  const trials = run.trials || [];
  const cheater = run.heldOut?.cheater;
  const builtIn = run.heldOut?.["built-in"];
  const spoofers = 2 * run.heldOutSeeds.length;
  const tried = Math.max(0, trials.length - 1);
  const clean = trials.slice(1).filter((t) => !t.summary.flaggedMarkets);
  const best = clean.reduce(
    (b, t) => (!b || (t.summary.lift || 0) > (b.summary.lift || 0) ? t : b),
    null,
  );
  return (
    <section className="run-main">
      <div className="run-header">
        <div>
          <h2>{headline(run, cheater)}</h2>
          <p className="muted">
            {run.searcher === "random"
              ? "Random search, seed " + run.searchSeed
              : run.model
                ? run.model +
                  (run.via === "claude-code"
                    ? " via Claude Code"
                    : " via the API")
                : "Scripted dry run"}
            {run.blind ? " · blind: alert text withheld" : ""} · detector{" "}
            <code>{run.detector}</code> · training seeds{" "}
            {run.trainSeeds.join(", ")} · held-out seeds {run.heldOutSeeds[0]}–
            {run.heldOutSeeds.at(-1)} · {run.ticks / 4} min markets
          </p>
        </div>
        <div className="run-actions">
          {finished && (
            <button
              className="secondary replay"
              onClick={() => setReplay(replay == null ? 0 : null)}
            >
              {replay == null ? "Replay" : "Stop replay"}
            </button>
          )}
          <StatusPill status={status} />
        </div>
      </div>

      <div className="account-strip kpis" aria-label="Run summary">
        {(cheater
          ? [
              [
                "Held-out flagged",
                cheater.flaggedSpoofers + " / " + spoofers,
                "built-in " + builtIn.flaggedSpoofers + " / " + spoofers,
                cheater.flaggedSpoofers ? "negative" : "positive",
              ],
              [
                "Lift, held out",
                times(cheater.lift),
                "built-in " + times(builtIn.lift),
              ],
              [
                "Extra shares / market",
                plusMinus(cheater.paired.shares[0]),
                cheater.paired.shares[1] == null
                  ? "one seed"
                  : "95% CI " +
                    plusMinus(cheater.paired.shares[1]) +
                    " to " +
                    plusMinus(cheater.paired.shares[2]),
              ],
              [
                "Fakes helped",
                cheater.paired.better + " / " + cheater.paired.seeds,
                "seeds",
              ],
              ["Trials", tried + " / " + (run.budget || tried), "used"],
            ]
          : [
              ["Trials", tried + " / " + (run.budget || 10), "used"],
              [
                "Built-in spoofer",
                trials[0] ? times(trials[0].summary.lift) : "…",
                trials[0]
                  ? "flagged " +
                    trials[0].summary.flaggedMarkets +
                    "/" +
                    trials[0].summary.rows.length
                  : "running first",
              ],
              [
                "Best unflagged lift",
                best ? times(best.summary.lift) : "none yet",
                best ? "trial " + trials.indexOf(best) : "",
                best && best.summary.lift >= GOAL ? "positive" : "",
              ],
              ["Goal", times(GOAL), "unflagged on every market"],
            ]
        ).map(([label, value, sub, tone]) => (
          <div className="metric kpi" key={label}>
            <span>{label}</span>
            <strong className={tone || ""}>{value}</strong>
            <small>{sub}</small>
          </div>
        ))}
      </div>

      {LIVE.has(status.phase) && status.phase !== "starting" && (
        <Now status={status} run={run} />
      )}

      <section className="panel">
        <div className="panel-heading">
          <h2>Detector vs. Claude</h2>
          <span className="muted">
            lift per trial · goal {times(GOAL)} · squares are training markets
          </span>
        </div>
        <TrialChart run={run} status={status} cheater={cheater} />
      </section>

      <section className="panel">
        <div className="panel-heading">
          <h2>Trial blotter</h2>
          <span className="muted">
            what Claude tried, newest first · click a row for the evidence
          </span>
        </div>
        <Blotter trials={trials} />
      </section>

      {run.final && (
        <section className="panel">
          <div className="panel-heading">
            <h2>Claude's write-up</h2>
            <span className="muted">on submitting, for the defenders</span>
          </div>
          <div className="writeup">
            <Changes
              layers={run.final.layers}
              tactics={run.final.tactics}
              empty="the built-in tactics"
            />
            <p>{run.final.reasoning || "(no reasoning given)"}</p>
          </div>
        </section>
      )}

      {cheater && (
        <section className="panel">
          <div className="panel-heading">
            <h2>Held-out verdict</h2>
            <span className="muted">
              {plural(run.heldOutSeeds.length, "unseen market")}, each with and
              without the fakes · paired 95% intervals
            </span>
          </div>
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Spoofer</th>
                  <th>Flagged</th>
                  <th>Real shares, fakes / control</th>
                  <th>Lift</th>
                  <th>Extra shares / market</th>
                  <th>Extra fill rate</th>
                  <th>Extra P&amp;L / market</th>
                  <th>Fakes helped</th>
                </tr>
              </thead>
              <tbody>
                {[
                  ["Claude's tactics", cheater],
                  ["Built-in", builtIn],
                ].map(([label, h]) => (
                  <tr key={label}>
                    <td>{label}</td>
                    <td className={h.flaggedSpoofers ? "negative" : "positive"}>
                      {h.flaggedSpoofers} / {spoofers}
                    </td>
                    <td>
                      {num(h.shares)} / {num(h.controlShares)}
                    </td>
                    <td>{times(h.lift)}</td>
                    <td>{interval(h.paired.shares)}</td>
                    <td>{plusMinus(h.paired.fillPoints[0], 1)} pts</td>
                    <td>${interval(h.paired.pnl)}</td>
                    <td>
                      {h.paired.better} / {h.paired.seeds}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      <section className="panel how">
        <div className="panel-heading">
          <h2>How to read this</h2>
        </div>
        <dl>
          <dt>Lift</dt>
          <dd>
            Shares the spoofers' real orders traded with fakes, divided by the
            same bots on the same seed with no fakes. Only the fakes differ.
          </dd>
          <dt>Flagged</dt>
          <dd>
            The <code>{run.detector}</code> detector raised an alert. It reads
            only the order event log; a separate answer key says who cheated.
          </dd>
          <dt>What Claude sees</dt>
          <dd>
            After each trial: flagged or not, the alert's text, and the lift. A
            strong attacker, like a trader who reads every enforcement notice.
          </dd>
          <dt>What Claude controls</dt>
          <dd>
            The spoofer's tactics, never single orders. The Java bot runs them,
            so every fake is labelled and every market replays from its seed.
          </dd>
        </dl>
      </section>
    </section>
  );
}

function interval(m) {
  return m[1] == null
    ? plusMinus(m[0])
    : plusMinus(m[0]) + " (" + plusMinus(m[1]) + " to " + plusMinus(m[2]) + ")";
}

function headline(run, cheater) {
  const phase = run.status?.phase;
  const actor =
    run.searcher === "random"
      ? "Random search"
      : run.searcher === "scripted" || !run.model
        ? "A scripted run"
        : "Claude";
  const its = actor === "Claude" ? "Claude's" : "its";
  if (cheater) {
    const ci = cheater.paired.shares;
    if (cheater.flaggedSpoofers)
      return (
        "The detector held: " + its + " spoofer was caught on unseen markets"
      );
    if (cheater.lift >= GOAL && ci[1] != null && ci[1] > 0)
      return (
        actor + " got a spoofer past the detector, and the fakes still worked"
      );
    if (cheater.lift >= GOAL)
      return (
        actor +
        " got past the detector; whether the fakes still work isn't settled"
      );
    return actor + " got past the detector, but the fakes stopped working";
  }
  if (phase === "failed") return "This run stopped before it finished";
  if (phase === "done")
    return run.firstSuccess
      ? actor + " got past the detector in trial " + run.firstSuccess
      : actor +
          " found nothing that got past the detector in " +
          plural(run.budget, "trial");
  if (run.final) return actor + " has submitted; grading on unseen markets";
  return actor + " is searching for tactics the detector misses";
}

function StatusPill({ status }) {
  const { phase, done, total, trial, who, updatedAt, error } = status;
  let text = phaseLabel(phase);
  if (phase === "trial")
    text =
      (trial === 0 ? "Built-in spoofer" : "Trial " + trial) +
      " · " +
      done +
      "/" +
      total +
      " markets";
  if (phase === "grading") text = "Grading " + who + " · " + done + "/" + total;
  if (phase === "failed" && error) text = "Stopped: " + error;
  return (
    <span className={"status-pill " + (LIVE.has(phase) ? "live" : phase)}>
      <PhaseDot phase={phase} />
      <span>
        {LIVE.has(phase)
          ? (status.replay ? "Replay · " : "Live · ") + text
          : text}
      </span>
      {LIVE.has(phase) && updatedAt && !status.replay && (
        <small>{ago(updatedAt * 1000)}</small>
      )}
    </span>
  );
}

function Now({ status, run }) {
  const fraction = status.total ? status.done / status.total : 0;
  let title, body;
  if (status.phase === "thinking") {
    title = "Claude is deciding what to try next";
    body =
      "It has the results of " +
      plural((run.trials || []).length, "trial") +
      ". Its next idea shows up here when it picks one.";
  } else if (status.phase === "grading") {
    title = "Grading " + status.who + " on unseen markets";
    body = "Each held-out seed runs with the fakes and as the honest control.";
  } else {
    title =
      status.trial === 0
        ? "Running the built-in spoofer"
        : "Trial " + status.trial;
    body =
      status.trial === 0
        ? "The harness runs it first, so Claude starts from what it gets."
        : status.hypothesis;
  }
  return (
    <section className="panel now-panel">
      <div className="panel-heading">
        <h2>
          <span className="now-label">Now</span>
          {title}
        </h2>
        {status.phase !== "thinking" && (
          <span className="muted">
            {status.done} of {status.total} markets
          </span>
        )}
      </div>
      <div className="now-body">
        {body && <p>{body}</p>}
        {status.phase === "trial" && (
          <Changes layers={status.layers} tactics={status.tactics} />
        )}
        <div
          className={status.phase === "thinking" ? "meter thinking" : "meter"}
          role="progressbar"
          aria-valuemin={0}
          aria-valuemax={status.total || 0}
          aria-valuenow={status.done || 0}
        >
          <i
            style={{
              width:
                status.phase === "thinking" ? "100%" : fraction * 100 + "%",
            }}
          />
        </div>
      </div>
    </section>
  );
}

function TrialChart({ run, status, cheater }) {
  const [hover, setHover] = useState(null);
  const trials = run.trials || [];
  const points = trials.map((t, i) => ({
    label: i === 0 ? "built-in" : "T" + i,
    lift: t.summary.lift,
    cells: t.summary.rows.map((r) => r.spoofed.flagged.length > 0),
    layers: t.layers,
    tactics: t.tactics,
  }));
  if (status.phase === "trial" && status.trial >= trials.length)
    points.push({
      label: status.trial === 0 ? "built-in" : "T" + status.trial,
      pending: true,
      cells: run.trainSeeds.map(() => null),
      layers: status.layers,
      tactics: status.tactics,
      progress: status.total ? status.done / status.total : 0,
    });
  if (cheater)
    points.push({
      label: "held out",
      lift: cheater.lift,
      cells: cheater.rows.map((r) => r.spoofed.flagged.length > 0),
      final: true,
      layers: run.final.layers,
      tactics: run.final.tactics,
    });
  const live = LIVE.has(status.phase);
  const slots =
    live && !cheater
      ? Math.max(points.length, (run.budget || 10) + 1)
      : points.length;
  const rows = Math.max(...points.map((p) => p.cells.length), 1);
  const W = 880,
    left = 44,
    right = 12,
    top = 14,
    plotH = 170,
    cell = 10,
    gap = 3;
  const H = top + plotH + 22 + rows * (cell + gap) + 6;
  const max = Math.ceil(Math.max(2, ...points.map((p) => p.lift || 0)));
  const y = (v) => top + plotH - (v / max) * plotH;
  const band = (W - left - right) / Math.max(slots, 1);
  const bw = Math.min(22, band * 0.45);
  const ticks = [];
  for (let v = 0; v <= max; v += max <= 4 ? 1 : max <= 10 ? 2 : 5)
    ticks.push(v);
  const hovered = hover == null ? null : points[hover];
  return (
    <div className="trial-chart">
      <div className="chart-scroll">
        <svg
          viewBox={`0 0 ${W} ${H}`}
          role="img"
          aria-label="Lift per trial and flagged training markets"
        >
          {ticks.map((v) => (
            <g key={v}>
              <line
                x1={left}
                x2={W - right}
                y1={y(v)}
                y2={y(v)}
                className={v === 0 ? "axis" : "grid"}
              />
              <text x={left - 8} y={y(v) + 4} textAnchor="end">
                {v}×
              </text>
            </g>
          ))}
          <line
            x1={left}
            x2={W - right}
            y1={y(GOAL)}
            y2={y(GOAL)}
            className="goal"
          />
          <text
            x={W - right}
            y={y(GOAL) - 5}
            textAnchor="end"
            className="goal-label"
          >
            goal {times(GOAL)}
          </text>
          {points.map((p, i) => {
            const cx = left + band * i + band / 2;
            const x0 = cx - bw / 2;
            const h = p.lift ? y(0) - y(p.lift) : 0;
            const r = Math.min(3, h);
            return (
              <g
                key={i}
                className={hover === i ? "col hovered" : "col"}
                onPointerEnter={() => setHover(i)}
                onPointerLeave={() => setHover(null)}
                onFocus={() => setHover(i)}
                onBlur={() => setHover(null)}
                tabIndex={0}
              >
                <rect
                  className="hit"
                  x={left + band * i}
                  y={top}
                  width={band}
                  height={H - top}
                />
                {p.pending ? (
                  <rect
                    className="bar pending"
                    x={x0}
                    width={bw}
                    y={y(0) - Math.max(4, plotH * 0.5 * p.progress)}
                    height={Math.max(4, plotH * 0.5 * p.progress)}
                    rx={3}
                  />
                ) : (
                  h > 0 && (
                    <path
                      className={
                        "bar" +
                        (p.final ? " final" : "") +
                        (p.cells.some(Boolean) ? " caught" : "")
                      }
                      d={`M${x0},${y(0)} V${y(0) - h + r} Q${x0},${y(0) - h} ${x0 + r},${y(0) - h} H${x0 + bw - r} Q${x0 + bw},${y(0) - h} ${x0 + bw},${y(0) - h + r} V${y(0)} Z`}
                    />
                  )
                )}
                <text
                  x={cx}
                  y={y(0) + 15}
                  textAnchor="middle"
                  className={p.final ? "strong" : ""}
                >
                  {p.label}
                </text>
                {p.cells.map((flag, j) => (
                  <rect
                    key={j}
                    x={cx - cell / 2}
                    y={y(0) + 22 + j * (cell + gap)}
                    width={cell}
                    height={cell}
                    rx={2}
                    className={
                      flag == null
                        ? "cell pending"
                        : flag
                          ? "cell flagged"
                          : "cell clear"
                    }
                  />
                ))}
              </g>
            );
          })}
        </svg>
      </div>
      <div className="chart-readout" aria-live="polite">
        {hovered ? (
          <>
            <strong>{hovered.pending ? "running" : times(hovered.lift)}</strong>
            <span>
              {hovered.final
                ? "submitted tactics, held out"
                : hovered.label === "built-in"
                  ? "built-in spoofer"
                  : "trial " + hovered.label.slice(1)}
              {!hovered.pending &&
                " · flagged in " +
                  hovered.cells.filter(Boolean).length +
                  " of " +
                  hovered.cells.length +
                  " markets"}
            </span>
            <Changes
              layers={hovered.layers}
              tactics={hovered.tactics}
              empty="built-in tactics"
            />
          </>
        ) : (
          <span className="muted">
            <i className="cell-key flagged" /> flagged market{" "}
            <i className="cell-key clear" /> clear market{" "}
            <i className="cell-key bar" /> lift <i className="cell-key final" />{" "}
            held out · hover a column for its tactics
          </span>
        )}
      </div>
    </div>
  );
}

function Blotter({ trials }) {
  const [open, setOpen] = useState(null);
  const rows = useMemo(() => trials.map((t, i) => [t, i]).reverse(), [trials]);
  if (!trials.length)
    return <p className="desk-empty">The built-in spoofer runs first.</p>;
  return (
    <div className="table-scroll">
      <table className="blotter">
        <thead>
          <tr>
            <th>#</th>
            <th className="text-col">Claude's hypothesis</th>
            <th>Verdict</th>
            <th>Lift</th>
            <th>Real shares</th>
            <th>Fill rate</th>
            <th>Fakes hit</th>
            <th>Spoofer P&amp;L</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(([t, i]) => {
            const s = t.summary;
            const sum = (k, side) =>
              s.rows.reduce((a, r) => a + (r[side][k] || 0), 0);
            const fill = (side) =>
              s.rows.reduce((a, r) => a + r[side].fillRate, 0) / s.rows.length;
            const isOpen = open === i;
            return [
              <tr
                key={i}
                className={isOpen ? "open" : ""}
                onClick={() => setOpen(isOpen ? null : i)}
                tabIndex={0}
                onKeyDown={(e) =>
                  (e.key === "Enter" || e.key === " ") &&
                  (e.preventDefault(), setOpen(isOpen ? null : i))
                }
                aria-expanded={isOpen}
              >
                <td>{i === 0 ? "BI" : "T" + i}</td>
                <td className="hypothesis-cell">
                  <span className="clip">
                    {i === 0
                      ? "Built-in spoofer, run first by the harness"
                      : t.hypothesis}
                  </span>
                </td>
                <td>
                  <Verdict flagged={s.flaggedMarkets} of={s.rows.length} />
                </td>
                <td className={s.lift >= GOAL ? "positive" : ""}>
                  {times(s.lift)}
                </td>
                <td>
                  {num(s.shares)}{" "}
                  <span className="faint">/ {num(s.controlShares)}</span>
                </td>
                <td>
                  {pct(fill("spoofed"))}{" "}
                  <span className="faint">/ {pct(fill("control"))}</span>
                </td>
                <td>
                  {num(sum("fakesFilled", "spoofed"))}{" "}
                  <span className="faint">
                    / {num(sum("fakes", "spoofed"))}
                  </span>
                </td>
                <td
                  className={
                    sum("pnl", "spoofed") >= 0 ? "positive" : "negative"
                  }
                >
                  ${plusMinus(sum("pnl", "spoofed"))}
                </td>
              </tr>,
              isOpen && (
                <tr key={i + "-detail"} className="detail">
                  <td colSpan={8}>
                    <div className="evidence">
                      {i > 0 && <p className="full">{t.hypothesis}</p>}
                      <Changes
                        layers={t.layers}
                        tactics={t.tactics}
                        empty="Built-in tactics"
                      />
                      <table>
                        <thead>
                          <tr>
                            <th>Market</th>
                            <th>Verdict</th>
                            <th>Real shares, fakes / control</th>
                            <th>Fill rate, fakes / control</th>
                            <th>Schemes</th>
                            <th>Fakes hit</th>
                            <th>P&amp;L, fakes / control</th>
                          </tr>
                        </thead>
                        <tbody>
                          {s.rows.map((r) => (
                            <tr key={r.seed}>
                              <td>seed {r.seed}</td>
                              <td>
                                {r.spoofed.flagged.length ? (
                                  <span className="tag flagged">flagged</span>
                                ) : (
                                  <span className="tag clear">clear</span>
                                )}
                              </td>
                              <td>
                                {num(r.spoofed.shares)} /{" "}
                                {num(r.control.shares)}
                              </td>
                              <td>
                                {pct(r.spoofed.fillRate)} /{" "}
                                {pct(r.control.fillRate)}
                              </td>
                              <td>{num(r.spoofed.episodes)}</td>
                              <td>
                                {num(r.spoofed.fakesFilled)} /{" "}
                                {num(r.spoofed.fakes)}
                              </td>
                              <td>
                                ${plusMinus(r.spoofed.pnl)} / $
                                {plusMinus(r.control.pnl)}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                      {s.rows
                        .flatMap((r) => r.spoofed.alerts)
                        .slice(0, 1)
                        .map((a) => (
                          <p className="alert-text" key={a}>
                            <span>Alert shown to Claude</span>
                            {a}
                          </p>
                        ))}
                    </div>
                  </td>
                </tr>
              ),
            ];
          })}
        </tbody>
      </table>
    </div>
  );
}

/* ---------- alerts ---------- */

function Alerts() {
  const detectors = usePoll(() => api("/surveillance/detectors"), 0, []);
  const [detector, setDetector] = useState("spoofing-rules");
  const alerts = usePoll(
    () => api("/surveillance/alerts?detector=" + detector),
    0,
    [detector],
  );
  const score = usePoll(
    () => api("/surveillance/score?detector=" + detector),
    0,
    [detector],
  );
  const [open, setOpen] = useState(null);
  const list = alerts.data || [];
  const sc = score.data;
  return (
    <div className="alerts-desk">
      <div className="desk-filters">
        <label>
          <span>Detector</span>
          <select
            value={detector}
            onChange={(e) => (setDetector(e.target.value), setOpen(null))}
          >
            {(detectors.data || ["spoofing-rules", "cancel-ratio"]).map((d) => (
              <option key={d}>{d}</option>
            ))}
          </select>
        </label>
        <span className="muted">
          Scans the order event log of the market currently in this app. Run a
          bot market to fill it.
        </span>
      </div>
      {sc && (
        <div
          className="account-strip kpis"
          aria-label="Score against the answer key"
        >
          {[
            ["Alerts", num(sc.flagged), "accounts flagged"],
            [
              "Cheaters caught",
              sc.caught + " / " + sc.cheaters,
              "from the answer key",
              sc.missed ? "negative" : sc.cheaters ? "positive" : "",
            ],
            [
              "False alarms",
              num(sc.falseAlarms),
              Object.entries(sc.falseAlarmsByKind || {})
                .map(([k, v]) => v + " " + k.toLowerCase().replace("_", " "))
                .join(", ") || "none",
              sc.falseAlarms ? "negative" : "positive",
            ],
            [
              "Precision",
              sc.precision == null ? "–" : pct(sc.precision),
              "of alerts were cheaters",
            ],
            [
              "Fake orders found",
              sc.orders?.recall == null ? "–" : pct(sc.orders.recall),
              sc.orders
                ? num(sc.orders.pointedAtSpoof) +
                  " of " +
                  num(sc.orders.spoofOrders)
                : "judges accounts only",
            ],
          ].map(([label, value, sub, tone]) => (
            <div className="metric kpi" key={label}>
              <span>{label}</span>
              <strong className={tone || ""}>{value}</strong>
              <small>{sub}</small>
            </div>
          ))}
        </div>
      )}
      <section className="panel">
        <div className="panel-heading">
          <h2>Alert queue</h2>
          <span className="muted">
            highest score first · click an alert for its evidence
          </span>
        </div>
        {alerts.error && <p className="desk-error">{alerts.error}</p>}
        {alerts.data && !list.length && (
          <p className="desk-empty">No alerts on the current market.</p>
        )}
        {list.length > 0 && (
          <div className="table-scroll">
            <table className="blotter">
              <thead>
                <tr>
                  <th>Score</th>
                  <th>Account</th>
                  <th className="text-col">Why</th>
                  <th>Orders cited</th>
                  <th>Log lines</th>
                </tr>
              </thead>
              <tbody>
                {[...list]
                  .sort((a, b) => b.score - a.score)
                  .map((a) => {
                    const isOpen = open === a.accountId;
                    return [
                      <tr
                        key={a.accountId}
                        className={isOpen ? "open" : ""}
                        onClick={() => setOpen(isOpen ? null : a.accountId)}
                        tabIndex={0}
                        onKeyDown={(e) =>
                          (e.key === "Enter" || e.key === " ") &&
                          (e.preventDefault(),
                          setOpen(isOpen ? null : a.accountId))
                        }
                        aria-expanded={isOpen}
                      >
                        <td>
                          <span className="score">
                            {num(a.score, a.score < 10 ? 2 : 0)}
                          </span>
                        </td>
                        <td>
                          {a.username}{" "}
                          <span className="faint">#{a.accountId}</span>
                        </td>
                        <td className="hypothesis-cell">
                          <span className="clip">{a.summary}</span>
                        </td>
                        <td>{num(a.orders.length)}</td>
                        <td>{num(a.evidence.length)}</td>
                      </tr>,
                      isOpen && (
                        <tr key={a.accountId + "-detail"} className="detail">
                          <td colSpan={5}>
                            <div className="evidence">
                              <p className="full">{a.summary}</p>
                              {a.orders.length > 0 && (
                                <p className="ids">
                                  <span>Orders</span>
                                  {a.orders.slice(0, 60).join(", ")}
                                  {a.orders.length > 60 &&
                                    " … " + (a.orders.length - 60) + " more"}
                                </p>
                              )}
                              {a.evidence.length > 0 && (
                                <p className="ids">
                                  <span>Event log lines</span>
                                  {a.evidence.slice(0, 60).join(", ")}
                                  {a.evidence.length > 60 &&
                                    " … " + (a.evidence.length - 60) + " more"}
                                </p>
                              )}
                            </div>
                          </td>
                        </tr>
                      ),
                    ];
                  })}
              </tbody>
            </table>
          </div>
        )}
      </section>
      <p className="desk-foot muted">
        <Icon name="activity" size={14} /> The answer key is only for grading:
        the detectors never read it.
      </p>
    </div>
  );
}
