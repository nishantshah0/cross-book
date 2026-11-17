"""Written by claude-opus-5-5, blind, on 2026-11-17, by tools/agent/write_detector.py. Saved as
written; nothing below the line was edited by hand.

What Claude said it looks for:

I couldn't run this program here: I had no sample market and no way to execute code, so it has never been run. The thresholds are my own estimates and haven't been checked against real data.

**What counts as an episode.** It starts with one of an account's orders that traded (the real trade) at time T. The same account must also have orders in the same stock on the opposite side that:
- were placed shortly before T (no more than 60 seconds before the fill, and no more than 30 seconds before the real order was placed),
- were still resting at T,
- went essentially unfilled (at most 10%),
- were cancelled within 10 seconds after T.

Their total size must be at least 2× everything the account had resting on the side that traded, or 3× if they all sit at one price. Spreading them across several prices marks it as layering rather than spoofing.

**How it tells spoofing from market making.** A market maker also cancels orders on the other side after a fill, so three checks screen that out:
1. **Balance.** Market makers quote both sides at similar sizes, so the size ratio stays near 1 for them.
2. **No re-quote.** If the account puts back at least half the cancelled size on that side within 2 seconds, it is treated as a re-quote, not a spoof.
3. **Repetition.** An episode only counts if it uses cancelled orders not already counted in an earlier one. An account is flagged only with at least 3 episodes that make up at least 20% of the orders it got filled on, or 10+ episodes making up at least 10%. One-off coincidences in busy market-maker or ordinary trading are filtered out to keep false alarms down.

**Score and output.** The score grows with the number of episodes, the share of the account's trades they make up, and the median size ratio. `orders` lists only the cancelled orders held against the account; `evidence` gives the event-log lines of those orders and of the trades they supported.

**Limits.**
- It doesn't rebuild the order book, so it doesn't check how close to the best price the cancelled orders were.
- It can't see spoofing split across two accounts.
- It ignores spoofing attempts that never produced a trade.
- If the input causes an unexpected error, it prints `[]` and writes the error to stderr instead of exiting with an error. That market gets no alerts rather than failing.

The prompt it got:

--- prompt ---
Write a market-surveillance detector for spoofing and layering on a simulated equity exchange.

The exchange is a continuous limit order book with price-time priority across a dozen stocks, and about 140 accounts trade for an hour. Honest participants include market makers that quote both sides and re-quote often, and other traders who cancel orders for ordinary reasons. Some accounts may be spoofing. You will not see the market before your detector runs on it, and nobody will tune it for you afterwards. False alarms are costly: an analyst has to review every alert.

Your detector is a program. It reads one market's order log as JSON on stdin and prints its alerts as JSON on stdout. The contract:

## Input (stdin)

```json
{
  "orders": [
    {"id": 1, "accountId": 7, "symbol": "AAPL", "side": "BUY", "type": "LIMIT", "quantity": 40,
     "limitPrice": 231.90, "firstSeq": 11, "placedAt": "2026-01-05T14:30:00Z",
     "endedAt": "2026-01-05T14:30:03Z", "end": "CANCELLED", "filled": 0}
  ],
  "usernames": {"7": "trader_0007"}
}
```

Every order the market saw, in the order they were placed, as the event log tells it:
`end` is `FILLED`, `CANCELLED`, `REJECTED` or null if still open; `filled` is the shares
that traded; `limitPrice` is null for a market order; `firstSeq` is the event-log line
where it was placed. That's all a detector gets: nothing says who is a bot or who cheats.

## Output (stdout)

```json
[{"accountId": 7, "score": 3, "summary": "3 layering episodes", "orders": [1, 4, 9], "evidence": [11, 15, 40]}]
```

One entry per account it flags. `accountId` and `score` (higher is more suspicious) are
required. `orders` (the order ids it holds against the account) lets it be graded order
by order too; `summary` and `evidence` (event-log lines) are for whoever reads the alert.
Print `[]` to flag nobody. A non-zero exit fails the market, with stderr as the reason.

Requirements: a single Python 3 program using only the standard library; deterministic; it must handle about 50,000 orders in under a minute. Use your own judgment about what spoofing and layering look like in order data and how to tell them from market making. In `notes`, say in a few sentences what your detector looks for and why.

"""
# ----------------------------------------------------------------------------
#!/usr/bin/env python3
"""Spoofing / layering detector for a simulated limit-order-book exchange.

Reads {"orders": [...], "usernames": {...}} on stdin, prints a JSON list of alerts.
Standard library only, deterministic.
"""
import sys
import json
import math
import re
import calendar
from bisect import bisect_left, bisect_right
from collections import defaultdict
from datetime import datetime, timezone

INF = float("inf")

# ---- episode parameters (seconds / ratios) ----
LOOKBACK = 60.0      # spoof orders must be placed at most this long before the fill
PRE = 30.0           # ... and no earlier than this before the genuine order was placed
WC = 10.0            # spoof orders must be cancelled within this long after the fill
XLOOK = 120.0        # look-back for same-side resting orders (balance check)
REQ = 2.0            # window after the cancel in which a re-quote counts as market making
MAX_SPOOF_FILL = 0.10  # spoof order may have at most this fraction filled
MIN_RATIO = 2.0      # opposite-side cancelled size vs. same-side size
MIN_RATIO_SINGLE = 3.0  # required ratio if spoof orders sit at only one price

# ---- account-level flag rule ----
MIN_EPISODES = 3
MIN_EP_RATE = 0.20       # episodes per order of the account that traded
BIG_EPISODES = 10
MIN_EP_RATE_BIG = 0.10

_TS = re.compile(
    r"^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(\.\d+)?\s*(Z|z|[+-]\d{2}:?\d{2})?$"
)
_ts_cache = {}


def ts(s):
    if s is None:
        return None
    if isinstance(s, (int, float)):
        return float(s)
    v = _ts_cache.get(s)
    if v is not None:
        return v
    st = str(s).strip()
    m = _TS.match(st)
    if m:
        y, mo, d, h, mi, se = (int(x) for x in m.groups()[:6])
        v = float(calendar.timegm((y, mo, d, h, mi, se, 0, 0, 0)))
        if m.group(7):
            v += float(m.group(7))
        tz = m.group(8)
        if tz and tz not in ("Z", "z"):
            sign = 1 if tz[0] == "+" else -1
            t = tz[1:].replace(":", "")
            v -= sign * (int(t[:2]) * 3600 + int(t[2:]) * 60)
    else:
        try:
            d = datetime.fromisoformat(st.replace("Z", "+00:00"))
            if d.tzinfo is None:
                d = d.replace(tzinfo=timezone.utc)
            v = d.timestamp()
        except Exception:
            return None
    _ts_cache[s] = v
    return v


class O(object):
    __slots__ = ("id", "acct", "sym", "side", "qty", "price", "seq",
                 "placed", "ended", "end", "filled")


def num(x, default=0.0):
    try:
        if x is None:
            return default
        return float(x)
    except Exception:
        return default


def load(data):
    recs = []
    for idx, o in enumerate(data.get("orders") or []):
        if not isinstance(o, dict):
            continue
        side = str(o.get("side") or "").upper()
        if side not in ("BUY", "SELL"):
            continue
        end = o.get("end")
        end = str(end).upper() if end is not None else None
        if end == "REJECTED":
            continue
        placed = ts(o.get("placedAt"))
        if placed is None:
            continue
        ended = ts(o.get("endedAt"))
        r = O()
        r.id = o.get("id", idx)
        r.acct = o.get("accountId")
        r.sym = o.get("symbol")
        r.side = side
        r.qty = num(o.get("quantity"))
        r.price = o.get("limitPrice")
        r.seq = num(o.get("firstSeq"), float(idx))
        r.placed = placed
        r.ended = ended if ended is not None else INF
        r.end = end
        r.filled = num(o.get("filled"))
        if r.qty <= 0:
            continue
        recs.append(r)
    return recs


def find_episodes(recs):
    groups = defaultdict(lambda: {"BUY": [], "SELL": []})
    for r in recs:
        groups[(r.acct, r.sym)][r.side].append(r)

    episodes = defaultdict(list)
    for key in sorted(groups.keys(), key=lambda k: (str(k[0]), str(k[1]))):
        g = groups[key]
        for s in g:
            g[s].sort(key=lambda r: (r.placed, r.seq))
        parr = {s: [r.placed for r in g[s]] for s in g}
        for X, Y in (("BUY", "SELL"), ("SELL", "BUY")):
            xs, ys = g[X], g[Y]
            if not xs or not ys:
                continue
            px, py = parr[X], parr[Y]
            for G in xs:
                if G.filled <= 0 or G.ended == INF:
                    continue
                T = G.ended
                lo = bisect_left(py, max(T - LOOKBACK, G.placed - PRE))
                hi = bisect_right(py, T)
                spoofs = []
                for i in range(lo, hi):
                    Ob = ys[i]
                    if Ob.end != "CANCELLED":
                        continue
                    if Ob.filled > MAX_SPOOF_FILL * Ob.qty:
                        continue
                    if Ob.ended < T or Ob.ended > T + WC:
                        continue
                    spoofs.append(Ob)
                if not spoofs:
                    continue
                qs = sum(Ob.qty for Ob in spoofs)
                if qs < MIN_RATIO * G.qty:
                    continue
                # same-side balance: other resting orders of this account at T
                lo2 = bisect_left(px, T - XLOOK)
                hi2 = bisect_right(px, T)
                qx = 0.0
                for j in range(lo2, hi2):
                    R = xs[j]
                    if R is G:
                        continue
                    if R.ended >= T:
                        qx += max(R.qty - R.filled, 0.0) if R.end == "FILLED" else R.qty
                ratio = qs / (G.qty + qx)
                nprices = len(set(Ob.price for Ob in spoofs))
                if ratio < MIN_RATIO or (ratio < MIN_RATIO_SINGLE and nprices < 2):
                    continue
                # market-maker re-quote check: opposite side promptly replaced
                lastc = max(Ob.ended for Ob in spoofs)
                sid = set(Ob.id for Ob in spoofs)
                lo3 = bisect_left(py, lastc)
                hi3 = bisect_right(py, lastc + REQ)
                rq = 0.0
                for k in range(lo3, hi3):
                    if ys[k].id not in sid:
                        rq += ys[k].qty
                if rq >= 0.5 * qs:
                    continue
                episodes[G.acct].append((T, G.seq, G, spoofs, ratio, nprices))
    return episodes


def main():
    raw = sys.stdin.read()
    data = json.loads(raw) if raw.strip() else {}
    if not isinstance(data, dict):
        data = {}
    recs = load(data)

    n_traded = defaultdict(int)
    for r in recs:
        if r.filled > 0:
            n_traded[r.acct] += 1

    episodes = find_episodes(recs)
    alerts = []
    for acct, eps in episodes.items():
        eps.sort(key=lambda e: (e[0], e[1]))
        used = set()
        kept = []
        for e in eps:
            new = [Ob for Ob in e[3] if Ob.id not in used]
            if not new:
                continue
            for Ob in new:
                used.add(Ob.id)
            kept.append((e, new))
        E = len(kept)
        if E == 0:
            continue
        rate = E / float(max(n_traded[acct], 1))
        if not ((E >= MIN_EPISODES and rate >= MIN_EP_RATE) or
                (E >= BIG_EPISODES and rate >= MIN_EP_RATE_BIG)):
            continue
        ratios = sorted(e[4] for e, _ in kept)
        med = ratios[len(ratios) // 2]
        layered = sum(1 for e, _ in kept if e[5] >= 2)
        score = round(E * (0.5 + min(rate, 1.0)) * math.log2(1.0 + min(med, 16.0)), 2)
        order_ids = []
        evidence = []
        syms = set()
        for e, new in kept:
            G = e[2]
            syms.add(str(G.sym))
            evidence.append(G.seq)
            for Ob in new:
                order_ids.append(Ob.id)
                evidence.append(Ob.seq)
        evidence = sorted(set(int(x) if float(x).is_integer() else x for x in evidence))
        try:
            order_ids = sorted(set(order_ids))
        except TypeError:
            order_ids = sorted(set(order_ids), key=str)
        kind = "layering" if layered * 2 >= E else "spoofing"
        summary = ("%d %s episodes in %s: cancelled opposite-side orders median %.1fx the "
                   "executed side, pulled right after the fill (%d multi-level); "
                   "%.0f%% of the account's executions" %
                   (E, kind, ",".join(sorted(syms)[:6]), med, layered, 100.0 * min(rate, 1.0)))
        alerts.append({"accountId": acct, "score": score, "summary": summary,
                       "orders": order_ids, "evidence": evidence[:500]})

    alerts.sort(key=lambda a: (-a["score"], str(a["accountId"])))
    sys.stdout.write(json.dumps(alerts))
    sys.stdout.write("\n")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:  # never fail the market on an unexpected input quirk
        sys.stderr.write("detector error: %r\n" % (exc,))
        sys.stdout.write("[]\n")
