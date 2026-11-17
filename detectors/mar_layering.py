"""Layering and spoofing as the EU Market Abuse Regulation describes it, as an outside
detector (see README.md).

The specification is the text of Commission Delegated Regulation (EU) 2016/522, Annex II,
Section 1, point 5(e): submitting "multiple or large orders", often away from the touch,
on one side of the order book, to execute a trade on the other side, and removing those
orders once the trade has taken place. Point 4(f) adds a supporting sign: a high ratio of
cancelled orders. https://www.legislation.gov.uk/eur/2016/522/annex/II

How each part of that becomes a rule here:

  a trade on the other side  an order of the account that traded (filled > 0). The log
                             has when an order ended, not each fill, so the trade is
                             dated from the order's placement to its end.
  orders on one side         the same account's limit orders on the opposite side of the
                             same stock, resting while that order was, and cancelled with
                             under a quarter filled
  removed once the trade     cancelled no earlier than the trading order was placed, and
  has taken place            no later than WINDOW after it ended
  multiple or large          two or more such orders, or one at least LARGE times the
                             stock's median order size (large for the market, not just
                             next to the account's own trade)

Each trade that meets all of that is an episode. MIN_EPISODES or more raise an alert.
The score is the episode count; the summary also gives the account's cancelled share of
its limit orders (point 4(f)), which supports an alert but never raises one alone.

The thresholds below were chosen from the text before this detector was first run and
are not tuned on any market. "Away from the touch" is left out: the text says "often",
and the log doesn't show the book, so the touch at each moment would have to be rebuilt.
This detector was written by the same author as the built-in spoofer and detectors, so
it is independent in specification, not in authorship.
"""
import json
import statistics
import sys
from collections import defaultdict
from datetime import datetime, timedelta

WINDOW = timedelta(seconds=5)
LARGE = 3.0
MAX_FILLED = 0.25
MIN_EPISODES = 3


def when(text):
    return datetime.fromisoformat(text.replace("Z", "+00:00")) if text else None


log = json.load(sys.stdin)
orders = log["orders"]
for o in orders:
    o["placed"], o["ended"] = when(o["placedAt"]), when(o["endedAt"])

median_size = {symbol: statistics.median(o["quantity"] for o in group) for symbol, group in
               ((s, [o for o in orders if o["symbol"] == s]) for s in {o["symbol"] for o in orders})}

books = defaultdict(list)  # (account, symbol) -> orders
for o in orders:
    books[(o["accountId"], o["symbol"])].append(o)

episodes = defaultdict(int)
cited = defaultdict(list)
evidence = defaultdict(set)
for (account, symbol), book in books.items():
    pulled = [o for o in book if o["type"] == "LIMIT" and o["end"] == "CANCELLED" and o["ended"]
              and o["filled"] < MAX_FILLED * o["quantity"]]
    if not pulled:
        continue
    for trade in book:
        if trade["filled"] == 0 or trade["ended"] is None:
            continue
        linked = [o for o in pulled
                  if o["side"] != trade["side"] and o["id"] != trade["id"]
                  and o["placed"] <= trade["ended"] and o["ended"] >= trade["placed"]  # resting alongside it
                  and trade["placed"] <= o["ended"] <= trade["ended"] + WINDOW]          # removed once it traded
        if not linked:
            continue
        large = any(o["quantity"] >= LARGE * median_size[symbol] for o in linked)
        if len(linked) >= 2 or large:
            episodes[account] += 1
            for o in linked:
                if o["id"] not in cited[account]:
                    cited[account].append(o["id"])
                evidence[account].add(o["firstSeq"])
            evidence[account].add(trade["firstSeq"])

limit_orders = defaultdict(lambda: [0, 0])  # account -> [limit orders, cancelled]
for o in orders:
    if o["type"] == "LIMIT":
        limit_orders[o["accountId"]][0] += 1
        limit_orders[o["accountId"]][1] += o["end"] == "CANCELLED"

alerts = []
for account, count in sorted(episodes.items()):
    if count >= MIN_EPISODES:
        placed, cancelled = limit_orders[account]
        alerts.append({"accountId": account, "score": count, "orders": cited[account],
                       "evidence": sorted(evidence[account]),
                       "summary": "%d trades after which the account pulled multiple or large orders it had "
                                  "resting on the other side; cancelled %d%% of its limit orders"
                                  % (count, round(100 * cancelled / placed) if placed else 0)})
json.dump(alerts, sys.stdout)
