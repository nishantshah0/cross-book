# The exchange

How orders match, how prices move, and the clock that lets a market replay exactly. The design notes behind it are in [architecture.md](architecture.md).

## How matching works

Each symbol has two sources of liquidity:

| Source           | Prices                                                                                                    | Size                  |
| ---------------- | --------------------------------------------------------------------------------------------------------- | --------------------- |
| **Other users**  | Their resting limit orders                                                                                | What they posted      |
| **The house**    | A bid and an ask either side of the feed price, 10 basis points apart by default (`HOUSE_SPREAD_BPS`)     | Any, priced by size   |

An incoming order:

1. Trades with resting user orders, best price first and oldest first within a price, at each resting order's limit.
2. Stops at its own limit, or at any level where the house offers a better price. At an equal price the user goes first.
3. Trades whatever is left with the house, which charges more per share for more shares. A limit order takes only the shares whose price stays within its limit.
4. If shares are still left, a limit order **rests in the book, partly filled**. A market order always fills completely.

Users meet inside the spread; an order priced through the house trades with the house. When the house's quote moves, resting orders it crosses fill in full at its price. An order that would trade with the same account's resting order is refused.

### Prices move with trading

The house is a market maker, not a bottomless wall of liquidity:

- **Trading moves the price.** The house's price depends on one number: how many shares users have bought from it, net, recently. It leans by `HOUSE_IMPACT_BPS` × √(net shares / `HOUSE_DEPTH_SHARES`), the square-root law real market impact follows. By default, net buying of 500 shares lifts the price 10 basis points, and 2,000 shares lift it 20. That volume fades towards zero by `HOUSE_IMPACT_DECAY` on every quote tick, so the price drifts back to the feed.
- **Size costs more, and splitting doesn't help.** Each fill pays the house's price averaged along that curve, from where the volume was to where the fill leaves it. So buying 1,000 shares costs the same in one order as in a thousand, and selling them back walks the same curve the other way. A round trip can only lose the spread, however it's split. Unit tests attack this with uneven splits, one-share pieces, selling first and 1,000 random sequences, and none makes money. The one thing that can pay is selling into a price *other* traders pushed up and buying back after it fades: that's trading against their flow, not a hole in the house's prices.
- **The book leans the quote.** The house shifts its quote towards whichever side has more resting shares within 0.25% of the price, as real market makers read order book imbalance. The lean is scaled by the house's depth, so it takes real size to move it: a 1-share bid leans the price by a few hundredths of a basis point, and only a very large imbalance approaches the `HOUSE_IMBALANCE_BPS` maximum. That makes spoofing possible but not free: a large bid that is never meant to fill lifts the price a seller gets, and it risks being filled. An integration test shows a 900-share fake bid letting the spoofer sell 3.6 basis points higher than an honest seller.

<details>
<summary><b>Worked example</b></summary>
<br>

The feed is at 50.00 with a 1% spread, so the house bids **49.75** and asks **50.25**.

| Step | Order                                 | Fills                                                     | Result                                        |
| ---- | ------------------------------------- | --------------------------------------------------------- | --------------------------------------------- |
| 1    | Alice (holds 10): sell limit 10 @ 50.10 | None yet                                                | Rests in the book, beating the house ask      |
| 2    | Bob: market buy 4                     | 4 @ 50.10 from Alice                                      | Alice's sell is 4/10 filled                   |
| 3    | Bob: market buy 8                     | 6 @ 50.10 from Alice, then 2 @ 50.25 from the house       | Alice is filled; Bob's average is 50.1375     |

This is the `usersInsideTheSpreadTradeWithEachOtherAndTheRestGoesToTheHouse` integration test.

</details>

### Book mode: no house

Set `MARKET_MODE=book` and the house is gone: orders only trade with each other, so trading alone sets the price. This is the mode for bot markets, where a pump has to be able to move the price and a manipulator's profit has to come from other traders.

- A market order takes what the book holds and the rest is cancelled. With nothing on the other side, it's refused.
- The feed keeps running as a reference value that traders can watch, but nothing is priced from it.
- Positions are valued at the last trade, not the feed. Buying through thin asks moves the price as far as the book lets it, and it stays there until someone trades it back.
- A book market has no other source of shares, so a simulated run starts by allocating them (see [Simulated time](#simulated-time)).

**Reservations.** Open limit orders reserve what they still need: a buy holds its limit price times its remaining shares, and a sell holds its remaining shares. New orders can only use buying power and shares that aren't already reserved, so a resting order stays funded until it fills or is cancelled. Cancelling a partly filled order keeps what already filled.

**Simplifications.** Fills are whole-share and long-only, with no commissions, shorting, or slippage beyond the house spread.

## Quote modes

| Mode             | Behavior                                                       |
| ---------------- | -------------------------------------------------------------- |
| `demo` (default) | A simulated random walk, updated every 15 seconds and labeled. Set `FEED_SEED` to replay the same prices; otherwise the seed is logged at startup. |
| `static`         | Configured prices never change, for deterministic experiments. |
| `finnhub`        | Real provider quotes, fetched server-side. Needs your own key. |

### Simulated time

Set `CLOCK_MODE=simulated` and time stands still at `CLOCK_START` until you advance it:

```bash
curl -X POST 'http://localhost:8080/api/simulation/ticks?count=40'   # ten minutes of market time, instantly
curl http://localhost:8080/api/simulation                            # clock, time, ticks taken, feed seed
```

Each tick moves the clock to the next point on a fixed grid of feed intervals, draws every stock's next price from the seeded walk, then lets each stock's matcher react in turn. Between ticks, the clock can also move by less than a tick, so an order and its cancel a fraction of a second later get their own timestamps. A bot runner also needs to hand out starting shares, which are bought from the house at the feed price:

```bash
curl -X POST 'http://localhost:8080/api/simulation/advance?millis=250'                       # within the current tick
curl -X POST 'http://localhost:8080/api/simulation/allocations?accountId=1&symbol=AAPL&shares=500'
```

 Every timestamp the app writes comes from that clock. So a run that starts from the same `FEED_SEED` and places the same orders in the same order produces the same event log, prices, balances and timestamps, down to the database ids. An integration test runs a scripted session twice and compares all of it. On the normal wall clock, the tick endpoint answers 409.

<details>
<summary><b>Using Finnhub</b></summary>
<br>

Copy `.env.example` to `.env` and set:

```dotenv
FEED_MODE=finnhub
FINNHUB_API_KEY=your_key
FEED_INTERVAL_MS=15000
```

Then rerun the Quick start Compose command. The key stays on the server. The adapter calls [Finnhub's quote endpoint](https://finnhub.io/docs/api/quote); twelve symbols at the default interval use up to 48 requests a minute, so set the interval to fit your plan.

Live mode never executes against seed prices, and a quote must be under 120 seconds old to trade on. When the feed is stale, including outside market hours, the house stops trading: market orders are refused and limit orders rest until a fresh quote crosses them. Users can still trade with each other. The tests never contact Finnhub.

</details>
