# Contributing

## Local setup

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

Use a small branch with a clear purpose. Keep changes and commit messages focused on the behavior they change. Do not include generated bundles, dependencies, browser traces, credentials, or local environment files.

## Checks

From the repository root:

```bash
./mvnw verify
cd frontend
npm ci
npm run format:check
npm run build
```

On Windows use `.\mvnw.cmd`. For browser checks, start the complete app and run `npm test` from `frontend/`. Set `E2E_BASE_URL` to test a separate local instance. Browser tests create synthetic accounts; use a disposable database when preserving a clean practice environment matters.

CI runs on pull requests and pushes to main. It tests against real Postgres, builds the packaged app, and exercises browser/WebSocket flows. A newer commit cancels an obsolete run on the same ref.

## Changes to trading behavior

Explain the execution rule being changed. Add coverage for relevant transaction, idempotency, or concurrency behavior, and keep simulation limitations explicit. Never add real-money brokerage integration as an incidental change.

## Changes to the interface

Keep the home page and terminal dark. Check desktop and mobile layouts, keyboard navigation, loading/error states, and direct route refreshes. Chart values must come from observed quotes. Update screenshots when visible behavior changes, using the running app with synthetic accounts.

## Pull requests and issues

Describe the concrete problem, the resulting behavior, and the checks you ran. Mention anything you could not verify. Include a screenshot for visible changes. Bug reports should include reproducible steps and whether the feed was demo, static, or Finnhub. Never paste provider keys, tokens, or private data into an issue.
