<div align="center">

# CrossBook

**A stock matching engine in Java and Postgres, built to be correct under concurrency: price-time matching, a ledger that provably balances, safe retries, and markets that replay to the millisecond.**

[![Build and test](https://github.com/nishantshah0/cross-book/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/nishantshah0/cross-book/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Java 21](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)
![Spring Boot 4](https://img.shields.io/badge/Spring_Boot-4.1-6DB33F?logo=springboot&logoColor=white)
![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)
![React 19](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=black)

[Quick start](#quick-start) · [How it's built](docs/internals.md) · [Design notes](docs/architecture.md) · [The exchange](docs/exchange.md) · [Performance](docs/performance.md) · [API](docs/api.md)

<img src="docs/dashboard.png" alt="The trading terminal: watchlist, chart, order book and order ticket" width="900">

</div>

## What it does

Users place market and limit orders through a REST API or a React trading terminal. Orders match by price, then time, and fill in parts; a partly filled order keeps its place in the queue. Fills, balances and quotes are pushed to every open tab over WebSockets. No real money is involved: prices come from a simulated feed, or optionally real Finnhub quotes.

## Engineering highlights

**Correctness**
- Every order event (placed, filled, cancelled, rejected) is written in the same transaction as the change it describes, so the event log can never disagree with the balances.
- After every integration test, a ledger audit proves no cash or shares were created or destroyed.
- Database check constraints back up the rules in code: no negative cash or positions, and no order filled past its size.

**Concurrency**
- Each stock has one matcher thread, so orders on a stock never interleave, and stocks match in parallel.
- Accounts are always locked in id order, so two trades between the same accounts can't deadlock.
- Fills are planned in memory and applied to the book only after the transaction commits, so a rollback leaves nothing to undo.
- Open orders reserve the cash or shares they need, so the same money can't be spent twice. An `Idempotency-Key` makes retries return the original order.
- Each of these has a test that races real requests against each other.

**Determinism**
- On a simulated clock with a seeded price feed, the same inputs produce the same event log, balances, timestamps and database ids. A test runs a whole market twice and compares a hash of every row.

**Performance**
- About **2,000 orders a second** end to end on a laptop (p99 12 ms at 10 concurrent traders).
- The limit is database round trips inside each order's transaction. CPU, disk flushing, pool size and the threading model were each ruled out by changing them one at a time.
- Logging every SQL statement found three wasteful queries; fixing them cut statements per action from 11.4 to 7.7, and a test fails if the count creeps back. Details in [docs/performance.md](docs/performance.md).

**Testing and delivery**
- Backend tests run against real Postgres (Testcontainers).
- Playwright and Cypress drive the packaged app in CI.
- Flyway migrations, RFC 9457 error responses, and long jobs run off the request thread.

## Built on top: a market-surveillance lab

The exchange's exact replay and complete event log make it a testbed for fraud detection. 142 bots trade through it, two of them spoofers (placing fake orders to move the price), and an answer key labels every fake order. Spoofing detectors read only the order log and are graded against the key.

The main finding: my best detector passed every test in this market, then raised 23 false alarms on an honest market maker in J.P. Morgan's open-source market simulator ([ABIDES](https://github.com/jpmorganchase/abides-jpmc-public)), because its rules had overfit to how my own bots quote. Results, methods, and an LLM-driven attacker compared against random search are in the [surveillance lab](docs/surveillance-lab.md).

## Quick start

With Docker running:

```bash
git clone https://github.com/nishantshah0/cross-book.git
cd cross-book
docker compose --profile app up --build -d --wait
```

- **http://localhost:8080:** open a practice account with $100,000 of virtual cash.
- **/terminal:** trade.
- **/surveillance:** the detectors' alert queue.

To develop locally or run the tests, see [CONTRIBUTING.md](CONTRIBUTING.md).

## Stack

Java 21, Spring Boot 4.1 (Web MVC, Data JPA, WebSocket/STOMP), PostgreSQL 17, Flyway, React 19 and Vite, JUnit, Testcontainers, Playwright, Cypress, Docker Compose and GitHub Actions.

## Limitations

- **Simplified trading:** long-only, whole shares, no fees, market and limit orders only.
- **Local use only:** there's no authentication, and accounts are unauthenticated practice accounts.
- **One node:** the matcher and database run on one machine. Matching state is rebuilt from Postgres on restart.

## License

[MIT](LICENSE)
