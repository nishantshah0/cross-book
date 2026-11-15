# Performance baseline

How much load CrossBook takes before any bots exist, and what limits it. This is the
"before" for the stress test later.

## Setup

- Apple M3, 8 cores, 16 GB, macOS 15.7. Docker Desktop with all 8 cores and 8 GB.
- The packaged app and Postgres 17 from `compose.yaml`, on their defaults: 200 Tomcat
  threads, a 10-connection Hikari pool, 12 demo symbols, price impact on.
- The load generator runs on the same machine, outside Docker:

```bash
java tools/loadtest/LoadTest.java --levels 1,10,50,200,500 --seconds 20 --warmup 10
```

Each simulated trader is a virtual thread with its own account, sending requests back to
back with no pause. It keeps about 10 shares of each stock, buying below that and selling
above it, so it never runs out of cash or shares. Its requests are 40% market orders,
45% limit orders 0.05% to 0.3% from the price (which mostly rest), and 15% cancels of its
oldest resting order. The ledger audit runs after each level.

## Results

| Traders | Requests/s | Orders placed/s | p50 ms | p95 ms | p99 ms | Max ms | Refused | Cancel too late | Busy (503) | Errors | Books after |
| ------: | ---------: | --------------: | -----: | -----: | -----: | -----: | ------: | --------------: | ---------: | -----: | ----------- |
| 1 | 635 | 433 | 1.5 | 2.6 | 4.3 | 36 | 1.1% | 54 | 0 | 0 | balanced, 42,932 trades |
| 10 | 2,502 | 1,802 | 3.4 | 7.8 | 11.5 | 156 | 0.9% | 1175 | 0 | 0 | balanced, 67,731 trades |
| 50 | 2,699 | 2,005 | 13.1 | 48.0 | 102.7 | 484 | 0.0% | 3 | 0 | 0 | balanced, 93,771 trades |
| 200 | 2,223 | 1,613 | 60.6 | 274.9 | 472.1 | 1046 | 0.0% | 0 | 0 | 0 | balanced, 112,969 trades |
| 500 | 2,692 | 1,943 | 158.5 | 395.8 | 543.5 | 856 | 0.0% | 0 | 0 | 0 | balanced, 134,610 trades |

"Refused" are orders the exchange turned down, almost all for lack of shares after a
trader's local count drifted. "Cancel too late" is a cancel that reached an order the
market had already filled: a normal race, not an error. Trade counts are cumulative.

- **Throughput tops out at about 2,000 orders a second** (2,500 to 2,700 requests a
  second with cancels), and it gets there with only 10 traders.
- **More traders only add waiting.** From 10 to 500 traders, throughput stays flat while
  the median request goes from 3 ms to 160 ms and p99 from 12 ms to 540 ms.
- **It stays correct.** No errors, no 503s from the engine's 10-second timeout, and the
  ledger balanced after every level, across 134,000 trades.

## What limits it

Neither container ran out of CPU: the app peaked at 3.6 cores and Postgres at 3.4 of 8.
So the limit is a queue, not raw compute. Each suspect was tested by changing one thing,
at 50 traders:

| Change | Orders/s | Verdict |
| ------ | -------: | ------- |
| None (12 stocks, warm) | 2,190 | baseline |
| Trade one stock only | 774 | One matcher thread manages about 770 orders a second, so 12 could reach about 9,000. They reach 2,200, so the per-stock thread is not what caps the total. |
| Postgres `synchronous_commit = off` | 2,290 | Waiting for the disk at each commit costs little. |
| Connection pool 10 → 30 | 1,634 | No gain. This run followed a restart, so the app was cold; a warm rerun with the default pool did 2,176. |

Six thread dumps taken under load show where the time goes:

- **Matcher threads wait on Postgres.** Most samples are blocked on a socket read inside
  the order's transaction. One order makes about ten round trips: lock the accounts, read
  the account's open orders to work out reservations, read positions, then write the
  order, its events, the trade, positions, the account and the house's books, and commit.
- **Request threads compete with the matchers for connections.** Before an order reaches
  its matcher, the request thread checks the account exists, which borrows a connection
  from the same 10-connection pool. Both kinds of thread show up waiting in Hikari for a
  connection.

So the ceiling is the database work per order, done in many small round trips, rather
than any one setting. A bigger pool alone did not help.

Throughput did not fall as the database grew: a warm run at 287,000 trades matched the
first baseline. An earlier dip to about 1,600 orders a second was a cold app straight
after a restart.

## One action at a time, for bot runs

A bot run that has to replay exactly from its seed can't fire orders in parallel: each
bot acts in turn and waits for its result. So the number that matters for bots is how
fast one caller goes, in-process, not how much concurrent HTTP the server takes.
`BotSpeedTest` measures that: 60 bots (market makers quoting and cancelling, takers
crossing the spread) take 4,000 actions in book mode on a simulated clock. It also counts
the SQL statements each action sends, from Hibernate's statistics, and fails if that
count creeps back above 8.5.

Logging every statement in that run showed where the round trips went:

| Statement | Per 4,000 actions | Fix |
| --------- | ----------------: | --- |
| Load the account's user row whenever the account was locked | 6,002 | The account's user is now lazy; only responses that show the username load it. |
| `select count(*)` to check the account exists before placing | 5,788 | Dropped: the order's transaction locks the account and answers 404 itself. |
| Read the same position again for each check and fill | about 3,600 | Each transaction reads a position once and reuses it. |
| Lock each account, and load each maker order, one query at a time | a few hundred | One locking query for all of an order's accounts (still taken in ID order) and one for all its makers. |
| Updates to accounts, orders and positions sent one by one | (batched) | JDBC batching sends each kind in one round trip at commit. |

| | Before | After |
| --- | ---: | ---: |
| SQL statements per action | 11.4 | 7.7 |
| Transactions per action | 2.2 | 1.3 |
| Actions per second, one at a time | 547 | 677 |

That is 24% faster. Turning off `synchronous_commit` on top added only 3% (696 a
second), so disk flushes are not the limit; round trips to Postgres in Docker are. What
is left is mostly inserts (the order, its events and trades), which cannot be batched
because their keys come from identity columns. Switching them to pooled sequences would
batch them, but the pool lives in the app's memory, so a simulation reset that restarts
the sequences would hand out different ids on a replay. That is not worth breaking
exact replays for.

The real bot runner (`BotRunner`, see the architecture notes) lands in the same place.
Its default market, 140 bots on twelve stocks for an hour of market time, took 48,885
actions in 68 seconds with seed 1 (about 715 a second), on a fresh database.

At this speed a run of 150,000 bot actions takes about four minutes. Independent runs,
one per seed, can go in parallel on separate databases, which is how scoring across many
seeds should scale.

The same changes against the concurrent HTTP load test, on the same laptop with a much
bigger database (407,000 trades against 135,000):

| Traders | Requests/s | Orders placed/s | p99 ms |
| ------: | ---------: | --------------: | -----: |
| 1 | 635 → 666 | 433 → 432 | 4.3 → 3.2 |
| 10 | 2,502 → 2,703 | 1,802 → 1,741 | 11.5 → 10.9 |
| 50 | 2,699 → 3,341 | 2,005 → 2,198 | 102.7 → 57.4 |

Latency under load roughly halved, and requests a second rose 24% at 50 traders, but
orders placed a second barely moved from the warm baseline of 2,190. Request threads no
longer borrow a connection to check the account, which helps the queue, while the work
inside each order's transaction is what still caps throughput.

## What to try in the stress test

1. Fewer round trips per order: keep each account's reservations in the matcher instead
   of re-reading open orders.
2. Then raise the load until the next limit shows, and remeasure with the same command.
