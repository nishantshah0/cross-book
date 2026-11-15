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

Portfolio and leaderboard reads use repeatable-read transactions to avoid mixing balances and positions from different commits. The leaderboard is one SQL query: prices live in memory, so the latest quotes are passed in as arrays and joined to positions, and each position rounds to cents before summing so the numbers match the portfolio page. With 1,339 accounts it takes about 11 ms, down from about 500 ms when it valued each portfolio separately. The database refuses to overfill an order: `filled_quantity` is checked against `quantity`, and a FILLED order must be fully filled.

Open limit orders reserve buying power and inventory for the shares they still have to fill. The holds are not stored: placement reads the account's OPEN orders under its lock and subtracts their remaining buy notionals (at the limit price) from cash and their remaining sell quantities from shares. A buy reserves at its limit and fills at or below it, so its hold always covers the fill. A market buy is checked against the house ask, the worst price it can fill at. Each fill still re-checks the balance under the lock; a resting order that cannot pay (only possible for orders placed before reservations existed) is rejected and skipped.

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

`papertrader.clock.mode=system` is the wall clock. `simulated` starts at `papertrader.clock.start` and moves only when `Simulation.tick` advances it, one feed interval at a time. A tick then:

1. asks the feed for every symbol's next price, in symbol order, from the seeded walk;
2. asks each symbol's matcher, one after another and waiting for each, to fade its lean and fill the resting orders the new quote crosses;
3. announces the new quotes to browsers.

On the wall clock the feed runs on a schedule and each matcher reacts to quote events on its own thread, so symbols tick in parallel. Under a simulated clock the scheduled refresh does nothing and the engine ignores quote events, because the simulation drives both in a fixed order. Orders placed between ticks already finish on their matcher before the call returns, including the sweep that follows them.

That is what makes a run replayable: from `Simulation.reset(seed)`, which truncates every trading table, restarts identity sequences, clears the books and leans, and puts prices back to their seeds at the start time, the same sequence of calls writes the same rows with the same ids, prices and timestamps. `SimulationReplayTest` runs a scripted session twice and compares the event log, quote history, ledger report and portfolios as JSON; breaking the feed's seeding makes it fail. Reset is a Java call for a simulation runner, not an endpoint. The guarantee assumes one caller at a time: concurrent clients would still race each other, as they would on any exchange.

A simulated clock refuses live quotes, which have their own timestamps.

## Checks

JUnit/Testcontainers exercises real Postgres migrations and transactional behavior, and audits the ledger and event log after every integration test. The coverage includes concurrent orders, idempotent retries, user-to-user fills across several resting orders and the house, partial fills and cancels, self-trade refusal, and two matcher threads trading between the same accounts at once. The book and engine have unit tests for price-time priority, house priority and when book changes are applied. A scheduled integration test changes a quote and waits for the matcher to fill without another order request. Playwright runs against the packaged app, including two independently subscribed browser tabs, cancellation, server validation, and mobile overflow checks.

## Frontend navigation

The root route serves the product home page. The trading workspace lives at `/terminal`; `/terminal/` is also supported. The backend forwards only these explicit terminal routes to the bundled index, so direct navigation and refresh work without swallowing unknown API or asset routes. The terminal logo returns home. Instrument links pass a validated symbol query parameter, and the saved practice account remains in local storage across navigation.

The home page and the terminal share one set of design tokens and self-hosted fonts, so nothing is fetched from a third party. The home page's demo is a working order ticket: its first order opens a guest practice account, saved in the browser so the terminal picks it up, and it shows the events that order created by reading the event log for that account. The live event list and ledger figures on the page come from `/api/events/recent` and `/api/ledger`. Account-opening links use `/terminal?setup=1`; successful account selection clears that parameter so refreshes resume the selected account instead of reopening setup.
