# API

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
