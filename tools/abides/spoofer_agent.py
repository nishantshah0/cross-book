"""The built-in layering spoofer, as an ABIDES agent, so it can run in a market someone
else built (J.P. Morgan's ABIDES, rmsc04: noise, value and momentum agents and two
adaptive market makers).

It runs the same scheme as bots/Spoofer.java with its default tactics: `layers` fake
orders a cent behind the best price and a cent apart, then a real order a cent inside
the spread on the other side, then everything is cancelled; each step 1 to 3 seconds
apart, then a rest of about 40 seconds. Sizes are scaled to ABIDES' market rather than
copied: ABIDES' median order is about 57 shares, so the real order is 50 and each fake
200, the same 4 to 1 as the built-in spoofer. It fakes demand when it holds shares and
supply when it doesn't (it starts with none; ABIDES lets it go short).

With layers=0 it's the honest twin: same wakes, same real orders, nothing fake. Every
order it places is recorded in `labels` (order id -> (episode, "SPOOF" or "REAL")),
which only the grader reads.
"""
from abides_markets.agents.trading_agent import TradingAgent
from abides_markets.messages.query import QuerySpreadResponseMsg
from abides_markets.orders import Side

SECOND = 1_000_000_000


class LayeringSpoofer(TradingAgent):

    def __init__(self, id, symbol, starting_cash, random_state, layers=4, fake_shares=200, real_shares=50,
                 offset_cents=1, gap_cents=1, step_ms=(1_000, 3_000), rest_ms=40_000):
        super().__init__(id, "spoofer_%d" % id, "LayeringSpoofer", random_state, starting_cash, False)
        self.symbol = symbol
        self.layers, self.fake_shares, self.real_shares = layers, fake_shares, real_shares
        self.offset, self.gap = offset_cents, gap_cents
        self.step_ms, self.rest_ms = step_ms, rest_ms
        self.step = "REST"
        self.awaiting = False
        self.fakes, self.real = [], None
        self.fake_side = None
        self.episode = 0
        self.labels = {}

    def wakeup(self, current_time):
        if not super().wakeup(current_time):
            return  # TradingAgent is still finding out when the market opens; it wakes us then
        self.get_current_spread(self.symbol)
        self.awaiting = True

    def receive_message(self, current_time, sender_id, message):
        super().receive_message(current_time, sender_id, message)
        if self.awaiting and isinstance(message, QuerySpreadResponseMsg):
            self.awaiting = False
            if self.mkt_closed:
                return
            bid, _, ask, _ = self.get_known_bid_ask(self.symbol)
            self.act(bid, ask)
            self.set_wakeup(current_time + self.next_gap())

    def get_wake_frequency(self):
        """How long after the open it first wakes (ABIDES asks every agent this)."""
        return int(self.random_state.exponential(self.rest_ms) * 1_000_000)

    def next_gap(self):
        if self.step == "REST":
            return int(self.random_state.exponential(self.rest_ms) * 1_000_000)
        low, high = self.step_ms
        return int(self.random_state.uniform(low, high) * 1_000_000)

    def act(self, bid, ask):
        if self.step == "REST":
            if not bid or not ask:
                return  # no book to play with; rest and try again
            self.fake_side = Side.BID if self.holdings.get(self.symbol, 0) > 0 else Side.ASK
            self.episode += 1
            self.layer(bid, ask)
            self.step = "EXECUTE"
        elif self.step == "EXECUTE":
            self.execute(bid, ask)
            self.step = "UNWIND"
        else:
            for order_id in self.fakes + ([self.real] if self.real is not None else []):
                if order_id in self.orders:
                    self.cancel_order(self.orders[order_id])
            self.fakes, self.real = [], None
            self.step = "REST"

    def place(self, shares, side, price, role):
        before = set(self.orders)
        self.place_limit_order(self.symbol, shares, side, price)
        new = [order_id for order_id in self.orders if order_id not in before]
        for order_id in new:
            self.labels[order_id] = (self.episode, role)
        return new[0] if new else None

    def layer(self, bid, ask):
        touch = bid if self.fake_side == Side.BID else ask
        for i in range(self.layers):
            away = self.offset + i * self.gap
            price = touch - away if self.fake_side == Side.BID else touch + away
            order_id = self.place(self.fake_shares, self.fake_side, price, "SPOOF")
            if order_id is not None:
                self.fakes.append(order_id)

    def execute(self, bid, ask):
        if not bid or not ask:
            return
        side = Side.ASK if self.fake_side == Side.BID else Side.BID
        price = max(ask - 1, bid + 1) if side == Side.ASK else min(bid + 1, ask - 1)
        self.real = self.place(self.real_shares, side, price, "REAL")
