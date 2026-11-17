<div align="center">

# CrossBook

**A matching engine with an audited ledger, and a lab for testing market-surveillance detectors against cheaters they weren't built for.**

[![Build and test](https://github.com/nishantshah0/cross-book/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/nishantshah0/cross-book/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Java 21](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)
![Spring Boot 4](https://img.shields.io/badge/Spring_Boot-4.1-6DB33F?logo=springboot&logoColor=white)
![React 19](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=black)
![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)

[Quick start](#quick-start) · [The exchange](docs/exchange.md) · [Surveillance lab](docs/surveillance-lab.md) · [API](docs/api.md) · [How it's built](docs/internals.md) · [Live desk](https://nishantshah0.github.io/cross-book/)

<img src="docs/dashboard.png" alt="The trading terminal: watchlist, chart, order book and order ticket" width="900">

</div>

## What it is

**An exchange.** Orders match by price and then time, fill in parts, and keep their place in the queue. Every order event is written in the same database transaction as the trade it describes, and after every integration test an audit proves no cash or shares were created or lost. On a simulated clock, the same seed replays a market to the millisecond. A React terminal lets you trade in it, live over WebSockets.

**A surveillance lab on top.** 142 bots trade through the exchange, some of them spoofers, and a separate answer key labels every fake order. Spoofing detectors read only the order log and are graded against the key. Then they're tested on things they weren't built for: detectors written by someone else, a market built by someone else (J.P. Morgan's ABIDES), cheaters modelled on real enforcement cases, and an AI agent trying to get past them.

No real money is involved. Prices come from a simulated feed, or optionally real Finnhub quotes.

## Results

Four detectors, four tests. Each cell is spoofers caught, then false alarms on honest accounts.

| Detector | This market | [ABIDES](https://github.com/jpmorganchase/abides-jpmc-public) | Coscia-style cheater | Sarao-style cheater |
| --- | --- | --- | --- | --- |
| Rules v1 | 10/10, 0 | 20/20, 0 | **1/10**, 0 | 10/10, 0 |
| Rules v2 | 10/10, 0 | 20/20, **23** | 10/10, 0 | 10/10, 0 |
| Written by Claude, blind | 10/10, 0 | 20/20, 0 | 10/10, 0 | **0/10**, 0 |
| EU regulation, as written | 10/10, **80** | 20/20, **30** | 10/10, **80** | 10/10, **80** |

**No detector passes everything, and each fails differently.** v1 misses spoofers that place the real order first. v2 passed every test in this market, but its fix had overfit to how this market's makers quote, and it flags ABIDES' honest market maker. The detector Claude wrote never imagined fakes that sit for minutes, so it misses all the Sarao-style spoofing. And the regulation's description of layering, run as a rule, flags every market maker. All markets are an hour long and unseen by the detectors; [the details are here](docs/surveillance-lab.md#testing-outside-my-own-market).

**The AI red team.** Claude (Opus 5.5) controls the spoofer's tactics and tries to stay unflagged while its fake orders still work. It's graded on markets it never saw:

- **Against v1, it got through.** On its third try it found that v1 judges each fake on its own, so it split them up. On 10 unseen markets: 0 of 20 spoofers flagged, and the fakes got 287 extra real shares traded per market (95% CI +155 to +419). It found the same hole blind, without seeing the alert text.
- **Random search sometimes gets there too.** 2 of 5 random searches met the goal, and one of their results held up as well as Claude's. Claude's edge is that 5 of its 9 tries worked, against 2 of 42.
- **Against v2, it didn't.** Nothing it tried both stayed hidden and still worked. But it stopped after 4 of its 10 tries, so that's weak evidence.

Replays of every run are on the [public desk](https://nishantshah0.github.io/cross-book/). Whether spoofing makes money here isn't proven: the fakes reliably get the real orders filled, but the profit's confidence interval still includes zero.

## Quick start

With Docker running:

```bash
git clone https://github.com/nishantshah0/cross-book.git
cd cross-book
docker compose --profile app up --build -d --wait
```

- **http://localhost:8080:** open a practice account with $100,000 of virtual cash.
- **/terminal:** trade.
- **/surveillance:** the alert queue, and red-team runs as they happen.

Bot markets need the exchange without its house market maker, on a simulated clock. To run one with spoofers and score every detector on it, restart the app that way, then start an evaluation:

```bash
MARKET_MODE=book CLOCK_MODE=simulated docker compose --profile app up -d --wait
curl -X POST localhost:8080/api/surveillance/evaluations -H 'Content-Type: application/json' -d '{"seeds": [101]}'
curl localhost:8080/api/jobs/1      # its report, once done (a few minutes)
```

For many seeds at once, the red team, ABIDES and outside detectors, see the [surveillance lab](docs/surveillance-lab.md).

## How it's built

- **Backend:** Java 21 and Spring Boot 4. One matcher thread per stock, and Postgres as the source of truth, with the schema in Flyway migrations.
- **Concurrency:** accounts are locked in id order so trades can't deadlock, the book changes only after the transaction commits, and an `Idempotency-Key` makes retries safe.
- **Speed:** about 2,000 orders a second end to end on a laptop, limited by database round trips. That's a deliberate cost: every order is durably logged with its fills in one transaction. See [performance](docs/performance.md).
- **Tests:** backend tests run against real Postgres (Testcontainers), and Playwright and Cypress drive the packaged app in CI.
- **Frontend:** React 19 and Vite, with live updates over STOMP WebSockets.

More: [the exchange](docs/exchange.md) (matching, pricing, simulated time) · [how it's built](docs/internals.md) (system, data model, layout) · [design notes](docs/architecture.md) · [API](docs/api.md) · [contributing](CONTRIBUTING.md).

## Limitations

- It's a simulation. Nothing here measures how a detector would do on real markets, where nobody labels which orders were fake.
- The detectors are rules, and the red team is small: one or two runs per setting.
- There's no authentication, so it's for local use. Accounts are long-only, with whole shares and no fees.

## License

[MIT](LICENSE)
