<div align="center">

# CrossBook

**A stock exchange engine with a bot market and planted cheaters, for testing market surveillance.**<br>
Run 142 simulated traders, spoofers included, and replay the market to the millisecond, or trade in it yourself from a full terminal. No real money involved.

[![Build and test](https://github.com/nishantshah0/cross-book/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/nishantshah0/cross-book/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Java 21](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)
![Spring Boot 4](https://img.shields.io/badge/Spring_Boot-4.1-6DB33F?logo=springboot&logoColor=white)
![React 19](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=black)
![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)

[**Live surveillance desk**](https://nishantshah0.github.io/cross-book/) · [Quick start](#quick-start) · [Catching the spoofers](#catching-the-spoofers) · [How matching works](#how-matching-works) · [Architecture](#architecture) · [API](#api) · [Design notes](docs/architecture.md)

<img src="docs/dashboard.png" alt="The trading terminal: watchlist, chart, order book and order ticket" width="900">

<sub>The trading terminal. The order book shows other traders' resting orders around the house's bid and ask; click a price to use it as your limit. Fills, cash and positions update over the WebSocket.</sub>

<img src="docs/media/red-team-replay.gif" alt="Replay of an AI red-team run on the surveillance desk" width="900">

<sub>The <a href="https://nishantshah0.github.io/cross-book/">surveillance desk</a> replaying an AI red-team run: Claude (Opus 5.5) probes the spoofing detector one trial at a time, then its tactics are graded on 10 markets it never saw. <a href="docs/media/red-team-replay.mp4">MP4</a>.</sub>

</div>

## Highlights

- **Order book with price-time priority.** Users trade with each other first, best price then oldest. Orders fill in parts, and a partly filled order keeps its place in the queue.
- **A simulated market maker.** The house quotes a bid and an ask around the feed price, so a market order always has something to trade with, even if you're the only user.
- **One matcher thread per symbol.** Symbols match in parallel and nothing within a symbol interleaves. Postgres stays the source of truth.
- **Books that always balance.** The house keeps its own books, so every dollar and share a user gains, the house gives up. A ledger audit proves nothing was created or destroyed, and it runs after every integration test.
- **Surveillance that's graded honestly.** A rule-based spoofing detector caught 10 of 10 spoofers on five unseen seeds with no false alarms across 1,410 honest accounts, against a cancel-ratio baseline that raised 107. Every alert cites the event-log lines behind it.
- **A bot market with a planted cheater.** One command runs 142 simulated traders for an hour of market time in about a minute, spoofers included. Over 30 seeds against an honest control, the fake orders got the spoofer's real orders traded on every seed (2.5 times the shares), though a profit isn't proven yet. The same seed gives the same market down to the millisecond, and only a separate answer key says who cheated, order by order.
- **A complete audit trail.** Every order event (placed, partly filled, filled, cancelled, rejected) is logged with a sequence number in the same transaction as the change, so the log never disagrees with the books.
- **Safe under concurrency.** Accounts are locked in ID order so trades can't deadlock, the book changes only after the transaction commits, and retries with an `Idempotency-Key` never place the same order twice.
- **A full trading terminal.** Everything on one screen: a searchable watchlist, a chart with a crosshair and time ranges, an order book ladder you can click to set a limit price, the order ticket, positions and order history, and a leaderboard.
- **A home page that proves it.** A live demo places a real order and shows the events it created, next to the running event log and ledger audit.
- **Live everywhere.** Fills, quotes and portfolio changes are pushed over STOMP WebSockets to every open tab, with a polling fallback.
- **Tested against the real thing.** The backend tests run on Postgres through Testcontainers, and Playwright and Cypress drive the packaged app in CI.

> [!NOTE]
> No real money or brokerage connection. The default feed generates simulated prices; an optional Finnhub mode uses real quotes. Practice accounts are unauthenticated, so this is built for local use.

## Quick start

With Docker running:

```bash
git clone https://github.com/nishantshah0/cross-book.git
cd cross-book
docker compose --profile app up --build -d --wait
```

Open **http://localhost:8080**, choose **Open practice account**, pick a lowercase name and you start with **$100,000** in virtual cash. Keep the account ID to reopen the account later. The terminal is at **http://localhost:8080/terminal**, and the surveillance desk (the detector's alert queue, and AI cheater runs as they happen) is at **http://localhost:8080/surveillance**.

The first build downloads Java and Node dependencies. The app and database listen on localhost only. To stop them and keep your data:

```bash
docker compose --profile app down
```

<details>
<summary><b>Screenshots</b></summary>
<br>

The home page, with a working demo that places a real order and shows the events it created:

![CrossBook home page](docs/home.png)

</details>

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

### Bot markets

One command runs a whole market of simulated traders, in book mode on the simulated clock:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=bots   # wipes the local database first
```

It resets the exchange, runs 142 bots across all twelve stocks for an hour of market time, logs a report, and leaves the app up so you can browse the result. `BOT_SEED`, `BOT_TICKS`, `BOT_NOISE_TRADERS`, `BOT_MARKET_MAKERS`, `BOT_BOOK_READERS`, `BOT_VALUE_TRADERS`, `BOT_SPOOFERS` and `BOT_LOOKALIKES` change the run. From a running app, `POST /api/bots/runs` starts one as a job: it answers `202` with a job id at once, and `GET /api/jobs/{id}` has the report when the run is over.

| Bots | Kind | Sees | Does |
| ---: | --- | --- | --- |
| 100 | Noise trader | the book | Buys or sells at random, sometimes at market, sometimes with a limit a little off the middle |
| 8 | Market maker | the feed | Quotes a bid and an ask in a few stocks around the feed price, leaning against its inventory |
| 20 | Book reader | the book | Trades in the direction of the most lopsided book: lots of resting bids means buy |
| 10 | Value trader | the feed | Trades a stock back towards its feed price when the book drifts more than 15 bps away, and cancels whatever doesn't fill at once |
| 2 | **Spoofer** (cheater) | the book | Stacks large fake orders on one side, trades a small real order on the other, then cancels the fakes |
| 2 | Lookalike | the book | The spoofer's honest twin: same starting stake, same timing, same real orders, no fakes |

The feed price stands in for a stock's true value, and only makers and value traders see it. Every honest bot has a position limit: it can sell down to zero or buy up to twice what it started with.

#### Does the spoofing work?

A spoofer is only worth catching if its fake orders actually do something. So each spoofed run is paired with a **control**: the same seed and the same bot, with the same timing and the same real orders, but zero fake layers (`"spoofLayers": 0`). The control places no fake orders, so it's honest. Over 30 seeds (201 to 230), an hour of market time each, with both spoofers' numbers added together:

| | With fakes | Control | Difference, 95% CI | Seeds where fakes helped |
| --- | ---: | ---: | ---: | ---: |
| Real orders that traded | 35% | 19% | **+16 points** (+13 to +19) | **30 of 30** |
| Shares the real orders traded | 283 | 113 | **+170** (+131 to +209) | **30 of 30** |
| Spoofer trading P&L | +$37 | −$13 | +$50 (−$15 to +$115) | 18 of 30 |
| Book readers' P&L, all 20 | −$1,346 | −$1,180 | −$166 (−$562 to +$230) | 17 of 30 |
| Worst stock vs its feed | 75 bps | 73 bps | +2 bps (−33 to +37) | – |

The confidence intervals are paired t intervals over the 30 seeds.

What the fakes clearly do is get the spoofer's real orders traded. On every one of the 30 seeds the real orders filled more often, and they traded 2.5 times the shares on average (from 1.2 to 5.9 times, depending on the seed). The fakes make the book look heavy on one side, and the book readers trade into the spoofer's real order.

What they don't clearly do, yet, is make money. The spoofer made $50 more an hour on average, but that's on $200,000 of starting cash, and the interval includes zero: in 12 of the 30 seeds it did no better than its honest twin. The book readers' extra losses and the effect on prices are just as unclear. Real spoofing cases match this: the edge was about a tick a trade, made back over thousands of trades. So a longer run might show it, but these runs don't. To check, run `python3 tools/lab/lab.py spoof-pairs --seeds 201-230` (about 50 minutes with 6 workers).

Honest markets aren't tight either. The worst stock in a control market ended 73 bps from its feed on average and once 3.0%, so the 30 seeds include markets where an honest stock wandered as far as a manipulated one.

#### What a run looks like

The default market with the spoofers, seed 1, on an Apple M3 laptop with a fresh database:

| | |
| --- | --- |
| Wall time | 65 seconds for an hour of market time, about 756 actions a second |
| Actions | 49,240 |
| Trades between bots | 12,268 |
| Refused orders | 12 |
| Last trade vs feed price | within 39 bps for every stock |
| Spoofing episodes | 155, with 570 fake orders, each pulled 2 to 6 seconds after it was placed |
| Ledger | balanced |

The bots call the order service directly rather than going over HTTP, so their orders are checked, matched, logged and audited like anyone's.

#### What makes a run fair to grade a detector on

- **It replays exactly.** The same request writes the same rows, down to the ids and the millisecond timestamps. A test runs a market with every kind of bot twice and compares a hash of every event, trade, balance and label.
- **Bots don't share random numbers.** Each bot has its own random streams, seeded from the run's seed and the bot's name: one for when it wakes and one for what it does. Rerunning a market without one bot leaves every other bot's schedule exactly as it was, so the difference is that bot's effect.
- **Bots act through each tick.** Each bot wakes at random intervals, and the clock moves to each wake, so the event log has realistic timing between orders and cancels.
- **Only the answer key says who is who.** Accounts are named `trader_0001` and up, opened in an order shuffled by the seed, so neither the name nor the id gives a bot away. `answer_key` records each account's bot and whether it cheats. `answer_key_orders` labels every order a spoofer placed as `SPOOF` or `REAL`, by episode, so a detector can be graded on exact orders. A detector reads only the market data.
- **Nothing but the spoofing gives a spoofer away.** A spoofer starts with two stocks at a quarter of its cash each, a shape no honest kind had, and starting stakes are in the trade log. So every market also has lookalikes, honest twins with the same stake, timing and real orders but no fakes. Of the accounts that start like a spoofer, half are honest, and a test checks it. A detector that flags lookalikes is flagging how the spoofer trades, not the spoofing. (An outside review found this leak.)

#### Bugs the measurements caught

The honest market has to stay near its feed price, or a detector graded on it learns nothing. Twice, it didn't:

1. **A noise trader that couldn't sell bought instead.** That one line tilted the market towards buying, and every stock drifted up, by as much as 4.7% in an hour. Fix: do nothing at the limit.
2. **Bots could buy far more than they could sell.** Each had tens of thousands in cash but only a few shares of an expensive stock. Once those were sold it could only buy, and COST ended 5.5% above its feed, with the makers and value traders holding none. Fix: the symmetric position limit above.

Both were found by tracing which bots held what, not by tuning numbers until the drift went away. Leaning the makers harder against their inventory was tried first, and it made things worse.

What's still simplified: noise traders are purely random, with no momentum or herding. Nobody can short. Bots act one at a time, which is what makes a run exact and caps it at about 700 actions a second. Layering is the only cheat so far.

**Reservations.** Open limit orders reserve what they still need: a buy holds its limit price times its remaining shares, and a sell holds its remaining shares. New orders can only use buying power and shares that aren't already reserved, so a resting order stays funded until it fills or is cancelled. Cancelling a partly filled order keeps what already filled.

**Simplifications.** Fills are whole-share and long-only, with no commissions, shorting, or slippage beyond the house spread.

## Catching the spoofers

Two detectors read the order event log, and only the event log. A test fails if the detector code ever mentions the answer key or the bots. Grading happens in a separate package that holds the alerts and the answer key side by side.

- **Spoofing rules.** These follow how exchange rules describe spoofing: large orders with no intent to trade, while the same trader trades a smaller order the other way. An order is suspect if it's a limit order that was cancelled less than a quarter filled within 10 seconds, and the same account placed an order at least 3 times smaller on the other side of that stock while it rested. That other order has to come strictly later, so a market maker posting its bid and ask together doesn't count. Three or more such episodes raise an alert, and the alert cites the exact event-log lines behind it.
- **Cancel-ratio baseline.** The naive check every rule has to beat: flag anyone who cancels at least 80% of their limit orders.

The rules' thresholds were set on seeds 1 to 3 and then frozen. Scored on five seeds they never saw (101 to 105), with one hour of market time each and every spoofed market paired with its control market:

| Detector | Spoofers caught | False alarms | Precision | Fake orders found |
| --- | ---: | ---: | ---: | ---: |
| **Spoofing rules** | **10 / 10** | **0** | **100%** | **87%** (2,278 of 2,617, none wrong) |
| Cancel-ratio baseline | 10 / 10 | 107 | 9% | – |

The baseline's 107 false alarms are 77 honest market makers, which re-quote all day, and all 30 lookalikes and control spoofers, which run the spoofer's code with the fakes turned off. The rules flagged none of the 1,410 honest accounts across the ten markets, the lookalikes and control spoofers included. On seed 101 they missed 47 fakes. 27 were less than 3 times the real order, usually a spoofer's last fake ask, cut short when it ran low on shares. 12 got a quarter or more filled before the cancel, and 8 came from schemes where no real order was placed. An account is still caught by its other episodes, so these only cost order-level recall.

What this doesn't show yet: the rules were written knowing what this spoofer does. Held-out seeds test whether they hold up in different markets, not against a cheater who changes how it spoofs. That's the next step: a cheater that adapts to the detector.

```bash
curl -X POST localhost:8080/api/surveillance/evaluations -H 'Content-Type: application/json' \
  -d '{"seeds": [101, 102, 103, 104, 105]}'                  # a job: about 12 minutes; resets the exchange
curl localhost:8080/api/jobs/1                                          # its status, then its report
curl 'localhost:8080/api/surveillance/alerts?detector=spoofing-rules'   # on the market as it stands
curl 'localhost:8080/api/surveillance/score?detector=spoofing-rules'    # the same, graded
```

## Architecture

### System overview

```mermaid
flowchart TB
    UI["React terminal"]
    API["REST API"]
    FEED["Quote feed<br/>demo · static · Finnhub"]
    ENG["Matching engine<br/>one thread and book per symbol"]
    EXEC["Order execution<br/>one transaction per order"]
    DB[("PostgreSQL")]
    WS["WebSocket events<br/>sent after commit"]

    UI -- "place · cancel" --> API
    API --> ENG
    FEED -- "price ticks" --> ENG
    ENG -- "planned fills" --> EXEC
    EXEC -- "commit" --> DB
    EXEC -. "fills" .-> WS
    FEED -. "quotes" .-> WS
    WS -. "live updates" .-> UI
```

- **One matcher per symbol.** A single thread owns each symbol's book and runs every placement, cancel and quote-driven fill for it in turn. Symbols match in parallel, and nothing within a symbol interleaves.
- **Postgres is the record.** The in-memory book is only an index of open orders. It's rebuilt from the `orders` table on startup, and reloaded if the book and the database ever disagree.
- **Events after commit.** Account updates go out over the WebSocket only once the transaction commits, so a client never sees a fill that was rolled back.

### Life of an order

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant S as OrderService
    participant M as match-AAPL thread
    participant X as OrderExecution
    participant DB as PostgreSQL

    C->>S: POST /api/accounts/{id}/orders
    S->>M: queue on the symbol's matcher
    M->>M: plan fills from the book and the house quote
    M->>X: place(order, planned fills)
    X->>DB: lock the accounts involved, in ID order
    X->>DB: check buying power and shares
    X->>DB: write the order, trades, cash and positions
    DB-->>X: commit
    X-->>M: book changes to apply
    M->>M: apply them to the book
    M-->>S: order
    S-->>C: 201 Created
    X--)C: OrderChanged over the WebSocket, to every party
```

The request thread holds no database connection while it waits for the matcher, so a busy symbol can't drain the connection pool. If the order hasn't started after 10 seconds, it's cancelled before it ever runs and the client gets a 503, which therefore always means nothing happened and the request is safe to retry. An order already running when the wait runs out is inside its transaction, so the client waits for its real result instead. If anything fails before the commit, the book is untouched and the client gets a problem response. Reading the book or the house's prices never waits at all: each matcher publishes a snapshot after every change.

### Concurrency and correctness

| Problem                                            | How it's handled                                                                                                   |
| -------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| Two orders on one symbol racing for the same shares | They're queued on that symbol's matcher thread, so they run one after the other.                                    |
| Two users trading on two symbols at once           | Both transactions lock the same pair of accounts, always lowest ID first, so neither can hold what the other needs. |
| A transaction that rolls back                      | Fills are only planned in memory. The book changes after the commit, so a failure leaves nothing to undo.           |
| The book drifting from the database                | Each fill checks the order's row. On a mismatch the symbol's book is reloaded and the order retried once.           |
| Spending the same cash twice                       | Open orders reserve the cash or shares they still need, and every order is checked against what's left.            |
| A client retrying a request                        | The same `Idempotency-Key` and payload returns the original order. A different payload gets a 409.                 |
| A stale price feed                                 | The house stops trading until a fresh quote arrives. Market orders are refused; limit orders wait.                  |
| Money or shares appearing from nowhere             | The house keeps its own books, and the ledger audit checks that everything balances after every integration test.   |

### Data model

```mermaid
erDiagram
    USERS ||--|| ACCOUNTS : owns
    ACCOUNTS ||--o{ POSITIONS : holds
    ACCOUNTS ||--o{ ORDERS : places
    ORDERS ||--o{ TRADES : "fills in"
    ORDERS |o--o{ TRADES : "is the other side of"
    HOUSE_POSITIONS ||--o{ TRADES : "takes the other side of"
    ORDERS ||--o{ ORDER_EVENTS : "logged in"
    TRADES ||--o| ORDER_EVENTS : "recorded by"

    ACCOUNTS {
        bigint id PK
        numeric cash
        numeric starting_cash
        bigint version
    }
    POSITIONS {
        bigint account_id FK
        varchar symbol
        int quantity
        numeric avg_cost
        numeric realized_pnl
    }
    ORDERS {
        bigint id PK
        bigint account_id FK
        varchar symbol
        varchar side
        varchar order_type
        int quantity
        int filled_quantity
        numeric limit_price
        numeric avg_fill_price
        varchar status
        varchar idempotency_key
    }
    TRADES {
        bigint id PK
        bigint order_id FK
        bigint account_id FK
        bigint counterparty_order_id FK
        varchar side
        int quantity
        numeric price
        timestamptz executed_at
    }
    HOUSE_POSITIONS {
        varchar symbol PK
        bigint quantity
        numeric cash
    }
    ORDER_EVENTS {
        bigint seq PK
        timestamptz occurred_at
        varchar type
        bigint order_id FK
        bigint account_id FK
        int filled_quantity
        bigint trade_id FK
        int fill_quantity
        numeric fill_price
        varchar reason
    }
```

The schema is managed by Flyway, and Hibernate only validates it. Check constraints in the database back up the rules in the code: cash and positions can't go negative, an order can't fill more than its quantity, and only a fully filled order can be `FILLED`. A trade with no `counterparty_order_id` was against the house, and moved the house's row for that symbol the opposite way. The house can be short and out of pocket, so its quantity and cash may go negative. `order_events` is append-only: one row each time an order is placed, fills, or ends, plus orders refused before they were placed, which have no `order_id`.

### Repository layout

```text
src/main/java/.../papertrader/
├── account/      accounts and users
├── bots/         the bot runner, the honest bots, the spoofer and the answer key
├── surveillance/ the detectors, which read only the event log
├── grading/      scoring alerts against the answer key, and multi-seed evaluations
├── jobs/         long work (bot runs, evaluations) off the request thread
├── ledger/       the ledger audit
├── portfolio/    portfolio valuation, P&L and the leaderboard
├── quote/        price feed, quote cache and history
├── trading/      order book, matching engine, order execution, positions
└── web/          WebSocket config, events and error handling
src/main/resources/db/migration/   Flyway migrations, V1 to V8
frontend/src/                      React home page and trading terminal
frontend/tests/                    Playwright browser and WebSocket tests
frontend/cypress/                  Cypress buying-power tests
docs/architecture.md               design notes and tradeoffs
docs/performance.md                load test results and bottleneck analysis
tools/loadtest/                    the load generator
tools/lab/                         runs many seeded markets at once, one per worker
tools/agent/                       an AI cheater: Claude searches for spoofing tactics the detector misses
```

More on the threading model, the race handling and the tradeoffs is in [docs/architecture.md](docs/architecture.md).

### Tech stack

| Layer    | Tools                                                                       |
| -------- | --------------------------------------------------------------------------- |
| Backend  | Java 21, Spring Boot 4.1, Spring Data JPA, Flyway, STOMP WebSockets          |
| Database | PostgreSQL 17                                                               |
| Frontend | React 19, Vite 7, STOMP.js                                                  |
| Testing  | JUnit, Testcontainers, Mockito, Playwright, Cypress                        |
| Delivery | Docker Compose, GitHub Actions                                              |

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

## Development

You'll need **JDK 21**, **Node 22** and Docker. Maven comes with the wrapper, and `.nvmrc` pins the Node version.

```bash
# Backend (Spring Boot starts the Compose database for you)
./mvnw spring-boot:run

# Frontend, in another terminal
cd frontend
npm ci
npm run dev
```

On Windows, use `.\mvnw.cmd`. Vite proxies REST and WebSocket traffic to port 8080. Stop the packaged app container before running the backend locally.

### Running the tests

```bash
./mvnw verify                          # backend, against real Postgres
cd frontend
npm ci
npm run format:check
npm run build
npx playwright install chromium
npm test                               # browser tests, against the running app
```

Browser tests expect the full app at `http://127.0.0.1:8080`; set `E2E_BASE_URL` to point them elsewhere. They create throwaway accounts, so use a disposable database if you care about a clean practice environment.

After every integration test, the ledger audit checks that the books still balance and that the event log agrees with them, so any test that moves money also tests that none went missing. The tests cover the order event log, user-to-user and partial fills, price-time and house priority, self-trade refusal, concurrent trades between the same accounts on two symbols, scheduled limit fills, double-spend and fill/cancel races, idempotent retries, stale quotes, migrations, P&L, navigation, account persistence, responsive layouts, and WebSocket delivery across two tabs. CI runs on every pull request and on main.

## Performance

On an Apple M3 laptop, with the app and Postgres in Docker, the exchange handles about **2,000 orders a second** (2,500 requests a second with cancels). It reaches that with 10 simulated traders; past that, more traders only add waiting, taking p99 latency from 12 ms at 10 traders to 540 ms at 500. Every level finished with no errors and balanced books, across 134,000 trades.

The limit is database round trips inside each order's transaction. It isn't CPU, disk flushing, the connection pool size or the one-thread-per-stock design. Each of those was ruled out by changing it alone.

Bot runs that replay exactly have to act one at a time, so that speed is measured separately, in-process. Logging every SQL statement found a lazy-loading miss, a redundant existence check and repeated position reads. Fixing them cut statements per action from 11.4 to 7.7 and took a one-at-a-time bot market from 547 to 677 actions a second. `BotSpeedTest` fails if the count creeps back up. The method, numbers and thread-dump findings are in [docs/performance.md](docs/performance.md), and the load generator is a single Java file:

```bash
java tools/loadtest/LoadTest.java --levels 1,10,50,200,500 --seconds 20
```

## API

All bodies are JSON and errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details. Cash has two decimal places; prices and average costs use four decimal places. Everything is USD.

| Method | Path                                    | Purpose                                    |
| ------ | --------------------------------------- | ------------------------------------------ |
| POST   | `/api/accounts`                         | Create a practice account                  |
| GET    | `/api/accounts/{id}`                    | Cash and starting balance                  |
| GET    | `/api/accounts/{id}/portfolio`          | Positions, value and P&L                   |
| POST   | `/api/accounts/{id}/orders`             | Place an order (optional `Idempotency-Key`) |
| GET    | `/api/accounts/{id}/orders?status=OPEN` | List orders; `status` is optional          |
| GET    | `/api/accounts/{id}/orders/{orderId}`   | One order                                  |
| DELETE | `/api/accounts/{id}/orders/{orderId}`   | Cancel an open order                       |
| GET    | `/api/accounts/{id}/trades`             | Fills, newest first                        |
| GET    | `/api/book/{symbol}`                    | Top 10 price levels, the house quote and the last trade |
| GET    | `/api/book/{symbol}/estimate?side=&shares=` | What a market order of that size would pay per share |
| GET    | `/api/quotes`, `/api/quotes/{symbol}`   | Cached quotes and timestamps               |
| GET    | `/api/quotes/status`                    | Feed mode                                  |
| GET    | `/api/quotes/history`                   | Up to 240 recent quotes per symbol         |
| GET    | `/api/leaderboard`                      | Top 20 by return percentage                |
| GET    | `/api/events?after=&symbol=&accountId=` | Order event log, by sequence number        |
| GET    | `/api/events/recent`                    | The latest order events, newest first      |
| GET    | `/api/ledger`                           | Run the ledger audit; the house's books    |
| GET    | `/api/simulation`                       | Clock mode, time, ticks taken, feed seed   |
| POST   | `/api/simulation/ticks?count=`          | Advance a simulated clock by whole ticks   |
| POST   | `/api/simulation/advance?millis=`       | Move a simulated clock within the current tick |
| POST   | `/api/simulation/allocations?accountId=&symbol=&shares=` | Sell an account starting shares at the feed price (simulated clock only) |
| POST   | `/api/bots/runs`                        | Start a whole bot market as a job (book mode, simulated clock); `202` with the job |
| GET    | `/api/bots/answer-key`                  | Which account is which bot, for grading     |
| GET    | `/api/bots/answer-key/orders`           | Every spoofer order, labelled `SPOOF` or `REAL` by episode |
| GET    | `/api/surveillance/detectors`           | The detectors                              |
| GET    | `/api/surveillance/alerts?detector=`    | A detector's alerts on the current market, with the event-log lines behind each |
| GET    | `/api/surveillance/score?detector=`     | Those alerts graded against the answer key |
| POST   | `/api/surveillance/evaluations`         | Start a job scoring every detector on a spoofed and a control market per seed |
| POST   | `/api/surveillance/trials`              | Start a job running one market (with optional spoofer `tactics`) and return each detector's verdict on each spoofer |
| GET    | `/api/red-team/runs`                    | AI cheater runs from `tools/agent/cheater.py`, newest first, live ones included |
| GET    | `/api/red-team/runs/{name}`             | One run as it stands: every trial, Claude's hypotheses, the held-out result |
| GET    | `/api/jobs/{id}`                        | A job's status, and its result once it's done |
| GET    | `/actuator/health`                      | Health check                               |

<details>
<summary><b>Example: place a limit order</b></summary>
<br>

```bash
curl -X POST http://localhost:8080/api/accounts/1/orders \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 6f1c2a' \
  -d '{"symbol":"AAPL","side":"BUY","type":"LIMIT","quantity":2,"limitPrice":225.50}'
```

Leave out `type` and `limitPrice` for a market order.

</details>

**WebSockets.** Connect to the STOMP endpoint at `/ws` and subscribe to `/topic/quotes` and `/topic/accounts/{id}`. Account events fire after commit and tell the client to refresh its portfolio, orders and trades. Browsers can't publish to the broker.

## Limitations

- **No authentication.** Account IDs aren't passwords. Add identity and authorization before deploying publicly.
- **Small-scale by design.** Order and trade lists aren't paginated.
- **Out of scope.** Corporate actions, exchange calendars, persistent price history and distributed messaging.

## What I'd do next

- **An adaptive cheater.** An AI agent that tunes the spoofer to get past the detector, run after run, so the rules are tested against a cheater who changes tactics rather than one they were written for.
- **More cheaters.** Wash trading between colluding accounts, and pump-and-dump.
- **More order types.** Stop orders, time-in-force (IOC, FOK, day) and amend-in-place.
- **Persist quote history.** Chart history lives in memory and resets on restart.
- **Authentication**, so it can be hosted publicly.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for setup, checks and guidelines. Please use the issue forms for bugs and feature requests.

## License

[MIT](LICENSE)
