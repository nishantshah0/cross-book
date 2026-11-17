"""An outside detector, as small as one gets: flags accounts that cancel nearly every
limit order they place. A starting point for writing one, not a serious detector (it's
the cancel-ratio idea, which flags honest market makers too). See README.md."""
import json
import sys
from collections import defaultdict

MIN_ORDERS = 50
MIN_CANCELLED = 0.9

log = json.load(sys.stdin)
placed = defaultdict(list)
for order in log["orders"]:
    if order["type"] == "LIMIT":
        placed[order["accountId"]].append(order)

alerts = []
for account, orders in sorted(placed.items()):
    cancelled = [o for o in orders if o["end"] == "CANCELLED"]
    share = len(cancelled) / len(orders)
    if len(orders) >= MIN_ORDERS and share >= MIN_CANCELLED:
        alerts.append({"accountId": account, "score": round(share, 3),
                       "summary": "cancelled %d of %d limit orders" % (len(cancelled), len(orders))})
json.dump(alerts, sys.stdout)
