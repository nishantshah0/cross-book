# Architecture and execution guarantees

## Data flow

```text
Demo generator or Finnhub quote API
                 |
             QuoteCache ---------> STOMP /topic/quotes
                 |  quote event (queued, never waits on a fill)
                 v
  REST ----> MatchingEngine: one thread + one OrderBook per symbol
                 |  plan fills from the book, then one transaction
                 v
     OrderExecution: lock every account involved, in ID order
                 |
     Postgres: orders, trades, cash, positions, house books
                 |
          after commit: apply the changes to the book
                 |
          AFTER_COMMIT event ----> STOMP /topic/accounts/{id}
                                           |
                              React reloads portfolio/orders/trades
```

QuoteCache keeps an immutable snapshot of at most 240 observed quotes per symbol. The REST history endpoint hydrates charts after a browser refresh. Older updates are ignored, equal-timestamp updates replace the last sample, and live-mode seed prices are excluded. This bounded memory buffer resets on server restart.

The quote feed runs on Spring's scheduler, independently of browser connections. Each quote it publishes is queued to that symbol's matcher thread and the feed moves on, so a slow fill for one symbol never delays quotes for the others.

## Who trades with whom

Two kinds of liquidity meet in each book:

- **Other users.** Limit orders that do not fill straight away rest in the book and wait for a counterparty.
- **The house**, a simulated market maker. It quotes a bid and an ask either side of the feed price (`papertrader.house.spread-bps`, 10 basis points across the spread by default) and fills any size, charging more per share for bigger orders (see Price impact below), so a market order always fills and a single user can trade alone.

An incoming order walks the opposite side of the book best price first, oldest first within a price, and trades at each resting order's limit. It stops at its own limit, and at the first level the house beats at its touch price, so no taker pays a user more than the house asks. At an equal price the user goes first. Whatever is left trades with the house, priced by size, and a limit order takes only the shares that price keeps within its limit; a limit order with shares still left rests, partly filled. A market order is refused while the quote is stale, because there is no house price to bound what it could cost.

Every quote also moves the house, and so does every placement and cancel, because those change the house's lean. Resting bids at or above the new house ask, and asks at or below the new house bid, fill in full at the house's touch price: they were resting, so the house came to them and they pay nothing for size.

### Book mode: no house

`papertrader.market.mode=book` (`MARKET_MODE=book`) takes the house out. A bot market needs this. With a house anchored to the feed, trading can only push the price 5% before it fades back, so a pump cannot pump, and a manipulator's profit comes mostly from the house rather than from other traders. In book mode:

- **Orders only trade with each other.** A market order takes what the opposite side of the book holds, best first, and the rest is cancelled; one that would find nothing at all is refused. Its buying-power check covers exactly the orders it was matched with. Limit orders rest until another trader crosses them; nothing sweeps them on a tick.
- **The feed is a reference value, not a price.** Quotes keep arriving, so traders can watch a "true value", but no fill is priced from them.
- **Positions are worth the last trade.** `Marks` is the one place a position gets its price, for portfolios, the leaderboard and the ledger: the feed in house mode, the last trade between two users in book mode (the feed until the first one). The matcher keeps the last price in memory and reloads it from the newest trade at startup.
- **Shares come from allocations.** A book market has no other source of shares, so under a simulated clock `POST /api/simulation/allocations` sells an account shares from the house at the feed price before a run starts. It goes through the house's books like any house fill, so every ledger check still holds; it is not a market trade, so it sets no last price. In book mode a fill with no counterparty order is always an allocation, which is how a detector tells them apart.
- **`GET /api/book/{symbol}/estimate`** prices a market order from the book's resting orders instead of the house's curve.

House mode stays the default, for one person practising against a market that always answers.

## Price impact

`PriceImpact` holds the model and `HouseMarket` prices fills from it; the matcher keeps the state. All of it is in basis points.

- **Flow.** Each matcher keeps one number for its symbol: the net shares users have bought from the house, negative when they have sold. The house's lean is `impact-bps` × √(|volume| / `depth-shares`) in the direction of the volume, capped at ±5%. Every fill against the house moves the volume by its shares, and each quote tick multiplies it by 1 − `impact-decay`. It lives in memory: it starts at zero, fades back to zero, and is lost on restart.
- **Size.** A fill pays the lean averaged over the stretch of the curve it walks, from the volume it starts at to the one it leaves, so a single order of `depth-shares` from zero pays two thirds of the 10 basis points it moves the price. The price is the house's mid at that average, plus half the spread for a buy or minus it for a sell, rounded against the trader to four decimals. A limit order takes the largest number of shares whose price stays within its limit, found by binary search on the exact price.
- **Book.** The house leans by `imbalance-bps` × (bids − asks) / (bids + asks + `depth-shares`), counting resting shares within 25 basis points of the feed price. Including the house's own depth in the denominator scales the lean by size: with the default 500 shares of depth, a lone 1-share bid leans the price 0.008 basis points, a 500-share bid 2, and only an imbalance far larger than the depth approaches the maximum. Without it, a single share on one side would lean the house as far as any spoof.

The house's mid is the feed price × (1 + (flow + book) / 10,000), and its touch (what it quotes for the next share) sits half the spread either side.

Because the price is a function of the volume alone, what a sequence of fills pays is the area under the curve it walks, whatever the split. Walking out and back returns to where it started and covers the same area both ways, so a round trip loses exactly the spread plus rounding. An earlier version charged each order its own square-root cost but moved the price by the sum of those costs; separate small orders then pushed the price far more than they paid for, and a thousand one-share buys followed by one sale made about $2,000 on $100,000. A review caught it, and `PriceImpactTest` now attacks that case, uneven splits, selling first, starting from a volume others left, and 1,000 random sequences that end flat; none makes money. The fade is the one thing that can pay, and only against someone else's trading: selling into a price others pushed up and buying back once it fades takes their losses, through the house. Starting from a quiet market, the fade only ever pulls the price back against your own trades.

Every fill against the house follows the curve, including resting orders its quote moves through. When a quote tick, a placement or a cancel moves the house's touch through a resting order, the matcher sweeps: the house fills as much of the order as its curve allows within the order's limit, at the curve price, and the rest stays in the book. Each of those fills moves the volume too, which pushes the house's price away from the next resting order rather than through it, so a sweep settles rather than cascades. An order resting exactly at the touch gets nothing: on the curve, not one share fits there.

The book lean is the one deliberate weakness, because real markets have it: an integration test shows a spoofer's 900-share fake bid lifting the price it sells at by 3.6 basis points, while a 1-share bid moves it by less than a tenth of a cent.

The lean is capped below half the spread (4 against 5 basis points by default). So a spoofer can sell at a better price than it would have got, but it can never lift the house's bid above where the mid was before it started. On its own the house is a weak target; a spoofer's real profit has to come from other traders who read the book and react to its fake orders. The cheater bots and the book-reading bots will need tuning against that.

The integration tests that check exact prices run with impact and imbalance set to zero, so the house trades any size at exactly its quote. `PriceImpactApiTest` runs with them on.

Users can only meet inside the spread: an order priced through the house trades with the house first. With a zero spread every order trades with the house, which is how the integration tests keep their arithmetic exact.

An order that would trade with the same account's resting order is refused, and the account is asked to cancel the resting order first. Partly filled orders stay OPEN with a filled quantity and an average fill price; cancelling one keeps what filled.

## The order book

Each side of a book is a `TreeMap` from price to a level: bids sorted highest first, asks lowest first. A level is a `LinkedHashMap` from order ID to entry, which keeps arrival order, removes a cancelled order in O(1), and lets a partial fill replace an entry without losing its place in the queue. Matching touches only the levels it fills from, instead of scanning every open order.

Why a sorted map and not a heap: a heap gives the best price cheaply, but cancelling from the middle is O(n), and walking several levels means repeated pops. The sorted map does both directly.

## One matcher per symbol

Each symbol has a single-threaded executor that owns its book. Placements, cancels and quote-driven fills for that symbol all run on it, one at a time. Request threads hand their order over and wait for the result, so within a symbol nothing can interleave: there is no lock on the book and no race between a fill and a cancel to reason about. Different symbols match in parallel on their own threads.

The request thread holds no transaction while it waits. Only the matcher thread opens one, so waiting requests cannot use up the connection pool.

### Postgres stays the record

The book is an index of what might fill; it never decides that something did. A placement first plans its fills from the book without changing it, then runs one transaction that re-reads each resting order it plans to trade with and fills it only if the row is still OPEN with enough shares left. The transaction reports what it changed (shares taken from an order, an order removed, a new order resting), and the engine applies that to the book only after commit. A rollback, such as a rejection for insufficient cash, leaves the book exactly as it was.

If a row ever disagrees with the book, the transaction throws, the engine reloads that symbol's book from its OPEN rows and retries once. The same reload builds every book on startup, followed by a sweep against the cached quotes.

This is not built for exchange-scale throughput. A larger system would partition symbols across processes, journal orders and quotes to a durable stream, and replay the book from it instead of from the orders table.

## Transactions and races

A trade between two users changes two accounts in one transaction: both cash balances, both positions, both orders and a trade row for each side. Every transaction locks the accounts it touches in ascending ID order. Two matcher threads can trade between the same pair of accounts at once, one on each symbol; because both take the locks in the same order, neither can hold the lock the other is waiting for. A test runs exactly that cross-symbol pattern repeatedly.

Accounts also carry a `@Version` column. With the row locks in place it never conflicts; it exists so that any future write path that forgets the lock fails with an optimistic-lock error instead of silently overwriting a balance.

Portfolio and leaderboard reads use repeatable-read transactions to avoid mixing balances and positions from different commits. The leaderboard is one SQL query: prices live in memory, so the marks are passed in as arrays and joined to positions, and each position rounds to cents before summing so the numbers match the portfolio page. With 1,339 accounts it takes about 11 ms, down from about 500 ms when it valued each portfolio separately. The database refuses to overfill an order: `filled_quantity` is checked against `quantity`, and a FILLED order must be fully filled.

Open limit orders reserve buying power and inventory for the shares they still have to fill. The holds are not stored: placement reads the account's OPEN orders under its lock and subtracts their remaining buy notionals (at the limit price) from cash and their remaining sell quantities from shares. A buy reserves at its limit and fills at or below it, so its hold always covers the fill. A market buy is checked against the house's price for its whole size, the worst it can fill at; in book mode, against the resting orders it was matched with. Each fill still re-checks the balance under the lock; a resting order that cannot pay (only possible for orders placed before reservations existed) is rejected and skipped.

Notional values round half up to cents per fill; prices and average costs use four decimals. Fills are whole-share and long-only.

## The books balance

The house is a real counterparty with its own books: a row per symbol in `house_positions`, holding the shares it is long or short and the cash it has taken in or paid out. A fill against the house moves that row the opposite way to the user, in the same transaction. Only the symbol's matcher thread writes it, so it needs no lock.

That makes two conservation laws checkable:

- **Cash.** Users' cash plus the house's cash equals the cash every account started with.
- **Shares.** For each symbol, users' positions plus the house's quantity sum to zero. Every share a user holds is one the house sold.

`LedgerAudit` checks both, and also rebuilds every balance from the trades that produced it: each account's cash and positions, the house's books, each order's filled quantity, the pairing of the two trade rows in a user-to-user fill, fills within their limits, and open orders still backed by what they reserve. Each check is a query for rows that break a rule, run together in one repeatable-read snapshot. Every fill commits atomically, so the audit can run while trading continues.

The audit also checks the order event log against the books: every trade has exactly one fill event with the same quantity, price and counterparty, and every order's events start with PLACED and end in a state that matches the order's status and filled quantity.

The audit runs after every integration test, so a test that moves money also proves none went missing. It is served at `GET /api/ledger`, along with the house's position and profit in each symbol.

## The order event log

`order_events` is an append-only record of everything that happens to an order: PLACED, a PARTIALLY_FILLED or FILLED event for each fill (with its trade, price and counterparty), and CANCELLED or REJECTED with a reason. Each event is written in the same transaction as the change it describes, so a rolled-back fill leaves no event behind and the log can never disagree with the orders and trades tables.

`seq` comes from a database sequence, so it is unique and increasing in the order events were written. One matcher thread writes each symbol's events, so within a symbol, seq order is exactly the order things happened, including the taker's fill before the maker's in a user-to-user trade. Across symbols, two matchers write at once; a reader tailing the live log should re-read a short window rather than assume an event can never appear below the last seq it saw.

A refused order rolls back its own transaction, so the refusal is logged afterwards in a transaction of its own, still on the symbol's thread. It has no `order_id`, because no order was ever created. Requests that fail validation before reaching the engine (a malformed body or an unknown symbol) are not orders and are not logged.

Migration V6 rebuilds the log for orders placed before it existed, from their creation time, trades and final status. Refusals from that time left no rows and cannot be recovered.

`GET /api/events` pages through the log by seq, optionally for one symbol or account. It shows every account's activity, which suits a local practice app with no authentication; a public deployment would restrict it.

### Waiting, timing out and reading

A request waits up to `papertrader.engine.timeout-ms` (10 seconds) for its task. If the task is still queued when the wait runs out, it is abandoned before it starts and the client gets a 503: so a 503 always means nothing happened, and the request is safe to retry. If the task has already started, it is inside its transaction and will finish, so the request waits for its real result instead of reporting a failure that may not be true. `Future.cancel` cannot tell those two cases apart (it reports success on a running task too), so the task and the waiting caller race to move a shared flag out of "queued": the task runs only if it wins, and the caller returns 503 only if it does.

Reads never queue. After every change, each matcher publishes an immutable snapshot of its book, the house's quote and its lean, and `GET /api/book`, the house price for a size and the lean all read the latest one. The book on screen can be one change behind the matcher, never torn.

## Safe retries

The optional idempotency key is scoped to the account and limited to 128 characters. Placement looks up an existing key before planning fills or checking balances. Retries of the same order run on the same matcher thread one after another, so the second always sees the first. Matching payloads return the existing order (including its latest status); changed payloads return 409. A partial unique index provides a second line of defense: the key is checked before the order's account is locked, so the same key sent on two symbols at once reaches two matchers and both can pass the check. The index stops the second insert, and placement turns that into the same 409 a sequential retry would get; a test races the two symbols twenty times and checks every loser gets it.

The browser retains a failed request's key until that payload changes or succeeds. Account switching clears pending retries. This prevents a network timeout followed by a retry from creating two fills.

## Feed modes and failures

- Demo: a random walk every 15 seconds, pulled gently back towards each symbol's configured seed price and kept within 6% of it. Clearly labeled simulated prices. `papertrader.feed.seed` (`FEED_SEED`) fixes the walk, so a run's prices can be replayed; left blank, the app picks a seed and logs it at startup. The prices replay exactly; what traders do with them still depends on timing until the simulated clock exists.
- Static: unchanged configured prices, used by integration tests.
- Finnhub: server-side HTTP requests with five-second connection/read timeouts. Seeds have an epoch timestamp and are never executable. Invalid, future-dated, or out-of-order quotes cannot replace good data. While a quote is older than 120 seconds the house is out: market orders are refused, and limit orders rest until a fresh quote crosses them. Users can still trade with each other.

Provider failures log only the symbol and exception class, retaining the last good data. The browser displays quote timestamps. Credentials are environment configuration and are never sent to the browser. Actual Finnhub account access is not exercised by CI.

## Real-time delivery

Only committed order changes are published. Clients subscribe to the same-origin STOMP endpoint; inbound client SEND frames are ignored because this channel is receive-only. Account events carry account ID, order ID, status and filled quantity, prompting a REST refresh; both sides of a user-to-user trade get one. Quote events carry symbol, price, and timestamp.

The simple broker has no durable replay. Reconnecting clients reload current REST state, and periodic refresh repairs missed messages. One app instance is the supported deployment; multiple instances would need shared quote and messaging infrastructure.

## Local demo boundary

There is deliberately no identity/authentication layer. Account IDs select shared practice accounts; they are not credentials. The Compose ports bind to 127.0.0.1. Do not expose this app publicly without authentication, account-level authorization for REST/STOMP, request limits, and a deployment-specific secret configuration.

## Time

Everything that records time reads one injected `java.time.Clock`: orders, trades, positions, the house's books, accounts, events, quotes and the ledger report. Each transaction reads it once and passes that instant down, so every row one order writes carries the same timestamp, and an event's time is taken from the row it describes. There is no static clock to leak between contexts.

`papertrader.clock.mode=system` is the wall clock. `simulated` starts at `papertrader.clock.start` and moves only when the simulation moves it. Ticks sit on a fixed grid, `start + n × interval`. A tick moves the clock to the next point on the grid, then:

1. asks the feed for every symbol's next price, in symbol order, from the seeded walk;
2. asks each symbol's matcher, one after another and waiting for each, to fade its lean and fill the resting orders the new quote crosses;
3. announces the new quotes to browsers.

On the wall clock the feed runs on a schedule and each matcher reacts to quote events on its own thread, so symbols tick in parallel. Under a simulated clock the scheduled refresh does nothing and the engine ignores quote events, because the simulation drives both in a fixed order. Orders placed between ticks already finish on their matcher before the call returns, including the sweep that follows them.

That is what makes a run replayable: from `Simulation.reset(seed)`, which truncates every trading table, restarts identity sequences, clears the books and leans, and puts prices back to their seeds at the start time, the same sequence of calls writes the same rows with the same ids, prices and timestamps. `SimulationReplayTest` runs a scripted session twice and compares the event log, quote history, ledger report and portfolios as JSON; breaking the feed's seeding makes it fail. Reset is a Java call for a simulation runner, not an endpoint. The guarantee assumes one caller at a time: concurrent clients would still race each other, as they would on any exchange.

Between ticks, `Simulation.advance` (`POST /api/simulation/advance?millis=`) moves the clock forward by less than a tick, so things that happen within one interval get their own times: an order placed and cancelled 250 ms later shows exactly 250 ms between its events. Time-to-cancel is the main signal of spoofing, and without this every event in a tick would share a timestamp. Advancing may not reach the next grid point; that is the tick's job, and the next tick still lands on the grid however far the clock went.

A simulated clock refuses live quotes, which have their own timestamps.

## Bot markets

`BotRunner.run` builds a market from a seed and a roster and runs it to the end in one call. It refuses unless the app is in book mode on a simulated clock, and it starts with `Simulation.reset(seed)`, so every run starts from the same empty exchange.

**The roster.** Four honest kinds and one cheater:

| Kind | Sees | Does |
| --- | --- | --- |
| Noise trader | the book | Buys or sells at random, at market or with a limit a little off the middle; gives up on old orders |
| Market maker | the feed | Quotes a bid and an ask in a few stocks around the feed price, shifted against its inventory |
| Book reader | the book | Trades at market in the direction of the most lopsided book, when it is lopsided enough |
| Value trader | the feed | Trades a stock back towards its feed price when the book drifts more than 15 bps away |
| Spoofer | the book | Layers fake orders on one side, trades a small real order on the other, then pulls everything |

Makers and value traders each follow a run of consecutive symbols, about two of each per symbol. The feed price stands in for value; only the makers and value traders get to see it.

**Position limits.** Every honest bot can sell down to nothing and buy up to twice the shares it started with. The limit has to be the same both ways. Without the buying half, a bot with plenty of cash but only a few shares of an expensive stock can soon only buy it, and the price drifts up: a measured run had COST end 5.5% above its feed, with the makers and value traders holding none of it and the book readers holding 304 shares. The same mistake appeared earlier in a smaller form, when a noise trader that couldn't sell bought instead (4.7% drift). In both cases the fix was to do nothing at the limit rather than trade the other way.

**Scheduling.** Each bot keeps its next wake time. A priority queue orders the bots by wake time, then by a fixed bot key, so ties break the same way every run. The runner takes the earliest bot, moves the clock to its wake (ticking for every grid point on the way, then `advance` for the rest), lets it act once, and draws its next wake. Gaps are exponential, so wakes arrive like independent orders, except inside a spoofing scheme, where the spoofer's steps come 1 to 3 seconds apart, so each fake order lives 2 to 6 seconds. A market maker's re-quote is four calls at one instant (two cancels, two orders), which is how a real maker behaves.

**Random streams.** No two bots share a random stream. Each bot has a timing stream and a decision stream, seeded by hashing the run's seed with the bot's key (its kind's fixed id and its number) and the stream's purpose, with SplitMix64. `StrictMath` keeps the arithmetic identical across machines. Because a bot's wakes come from its own timing stream, removing one bot, or changing what another one decided, can't shift anyone else's schedule. That's what a counterfactual needs: run the market with and without the cheater, and the gap between the two is the cheater's effect. What other bots *decide* can still change, because they react to a different market, and that's the effect being measured.

**The spoofer.** Each scheme (an episode) is three steps. First it stacks large limit orders (four by default, each 8% of its cash) a cent apart, starting a cent behind the best price on the fake side. Then it posts a real order of 2% of its cash on the other side, a cent inside the spread, so it's first in line. Finally it cancels the fakes, and the real order if it hasn't filled. To sell it fakes demand, and to buy back it fakes supply, depending on whether it holds more or less than it started with. It works two stocks chosen by its seed and rests about 40 seconds between schemes. The fakes are exposed: a seller who hits them trades with the spoofer for real, and that happens to a few percent of them.

**Who pays, and the control.** Spoofing only pays if someone reacts to the fake orders. A book market has no house to fool, and the makers quote off the feed, so the victims are the book readers. To check the effect really comes from the fakes, `spoofLayers` sets the number of layers, and 0 runs the control: the same bot, with the same timing and real orders, but nothing fake. The control is honest, so the answer key marks it as such. It's also the hardest innocent account a detector will meet. The measured comparison is in the README.

**The answer key.** Accounts are opened in an order shuffled by the run's setup stream and named `trader_0001` upward, so account ids and names carry no information about the bot. The `answer_key` table maps each account to its bot name, kind and whether it cheats. `answer_key_orders` labels every order a spoofer placed as `SPOOF` or `REAL`, with its episode number, so a detector can be graded on the exact orders it flags, not only on accounts. Both are for grading only: a detector reads orders, trades and events.

**Trading P&L.** The report scores each kind at the feed price. It takes each account's value at the end and subtracts the value it would have had by just holding its starting shares. So a stock that happened to drift up or down doesn't count as anyone's skill or loss.

**Simplifications.** Noise traders are uniformly random, with no momentum or herding. Everything is long-only. Bots act one at a time, which is what makes a run exact, and it caps a run at about 700 actions a second. One spoofing pattern (layering) is built so far; wash trading and pump-and-dump are next.

## Surveillance

Detectors live in `surveillance/` and see a `MarketLog`: every order rebuilt from `order_events` (placed, filled in parts, how it ended, with the sequence number of its `PLACED` event), plus account usernames. That's what a surveillance desk gets from an exchange. `SpoofingRulesTest` reads every source file in the package and fails if one mentions the answer key, the bots or the grading code, so a detector can't peek even by accident.

`grading/` is the only code that holds alerts and the answer key together. A `Score` counts accounts (caught, missed, false alarms by what each account really was) and orders: of the orders labelled `SPOOF`, how many the detector pointed at, and of those it pointed at, how many were `SPOOF`. A spoofer's `REAL` orders count as neither. `Evaluation` runs a spoofed market and its control for each seed and adds up the counts across seeds before dividing, so a seed with no alerts doesn't skew an average.

**The rules' first version missed every spoofer.** It compared an order's size with the stock's median limit order. But market makers place most of the limit orders, so their quote size set the median, and the spoofer's fakes were only about twice that. The fix follows the definition of spoofing more closely: compare the fake with the same trader's own order on the other side. That holds whatever else is in the market. The thresholds were set on seeds 1 to 3, then frozen and scored on 101 to 105.

**Why the market makers aren't caught.** They also place large orders and cancel nearly all of them within seconds, which is exactly why the cancel-ratio baseline flags them. Two things clear them: they post their bid and ask at the same instant, never one after the other, and both sides are the same size.

## Checks

JUnit/Testcontainers exercises real Postgres migrations and transactional behavior, and audits the ledger and event log after every integration test. The coverage includes concurrent orders, idempotent retries, user-to-user fills across several resting orders and the house, partial fills and cancels, self-trade refusal, and two matcher threads trading between the same accounts at once. The book and engine have unit tests for price-time priority, house priority and when book changes are applied. A scheduled integration test changes a quote and waits for the matcher to fill without another order request. Playwright runs against the packaged app, including two independently subscribed browser tabs, cancellation, server validation, and mobile overflow checks.

## Frontend navigation

The root route serves the product home page. The trading workspace lives at `/terminal`; `/terminal/` is also supported. The backend forwards only these explicit terminal routes to the bundled index, so direct navigation and refresh work without swallowing unknown API or asset routes. The terminal logo returns home. Instrument links pass a validated symbol query parameter, and the saved practice account remains in local storage across navigation.

The home page and the terminal share one set of design tokens and self-hosted fonts, so nothing is fetched from a third party. The home page's demo is a working order ticket: its first order opens a guest practice account, saved in the browser so the terminal picks it up, and it shows the events that order created by reading the event log for that account. The live event list and ledger figures on the page come from `/api/events/recent` and `/api/ledger`. Account-opening links use `/terminal?setup=1`; successful account selection clears that parameter so refreshes resume the selected account instead of reopening setup.
