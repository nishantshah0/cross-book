import { useEffect, useRef, useState } from "react";
import Icon from "./Icon";
import { api, money } from "./api";
import { bookSides } from "./book";

const REPO = "https://github.com/nishantshah0/cross-book";
const SOURCE =
  REPO + "/blob/main/src/main/java/io/github/nishantshah0/papertrader/";

const names = {
  AAPL: "Apple",
  NVDA: "NVIDIA",
  MSFT: "Microsoft",
  AMZN: "Amazon",
  GOOGL: "Alphabet",
  JPM: "JPMorgan Chase",
  META: "Meta Platforms",
  TSLA: "Tesla",
};
const demoSymbols = ["AAPL", "NVDA", "MSFT"];

const internals = [
  [
    "Matching",
    "Orders match on price, then time, and can fill in parts. One thread per stock owns that stock's book, so nothing within a stock interleaves.",
    "MatchingEngine.java",
    SOURCE + "trading/MatchingEngine.java",
  ],
  [
    "Settlement",
    "Each order settles in a single Postgres transaction: cash, shares, both orders and the trade. Accounts are locked in ID order, so two trades can never deadlock.",
    "OrderExecution.java",
    SOURCE + "trading/OrderExecution.java",
  ],
  [
    "Price impact",
    "Bigger orders cost more per share, following the square-root law, and every trade moves the price, which then fades back. The market maker also leans towards whichever side has more resting orders, so fake orders can move prices.",
    "PriceImpact.java",
    SOURCE + "trading/PriceImpact.java",
  ],
  [
    "Ledger audit",
    "The house keeps its own books, so no money or shares can appear or disappear. An audit rebuilds every balance from its trades, and runs after every integration test.",
    "LedgerAudit.java",
    SOURCE + "ledger/LedgerAudit.java",
  ],
  [
    "Event log",
    "Every order event is written with a sequence number in the same transaction as the change it describes, including orders that were refused.",
    "OrderEvent.java",
    SOURCE + "trading/OrderEvent.java",
  ],
  [
    "Tests",
    "The backend tests run against a real Postgres database. Playwright and Cypress drive the packaged app in CI on every push.",
    "CI runs",
    REPO + "/actions",
  ],
];

const faqs = [
  [
    "Am I trading with real money?",
    "No. Every account starts with virtual cash. Orders are simulated, and CrossBook has no connection to a brokerage or the ability to move real funds.",
  ],
  [
    "Who is on the other side of my trade?",
    "Another practice account, when its resting order beats the house. Otherwise the house: a simulated market maker that quotes a bid and an ask around the feed price. It charges more per share for bigger orders, and its prices move with the trading.",
  ],
  [
    "What happens to my limit order?",
    "It trades straight away with anything at your price or better. Whatever is left rests in the book, possibly partly filled, until someone trades with it, the house quote crosses it, or you cancel it. Cancelling keeps what already filled.",
  ],
  [
    "Where do the prices come from?",
    "A simulated feed by default, labelled as such. An optional live mode uses provider quotes, and the house stops trading whenever a quote goes stale.",
  ],
  [
    "How do I reopen an account?",
    "Open the terminal and enter your account ID, shown in the top-right corner once you have an account. Practice accounts are shared in this demo, so an account ID is not a private sign-in.",
  ],
  [
    "How close is this to real trading?",
    "There is a real order book, partial fills and a spread. But fills are whole shares, there is no shorting, commissions or corporate actions, and the house's size costs follow a simple model. Results here don't predict real-market performance.",
  ],
];

const eventLabels = {
  PLACED: "Placed",
  PARTIALLY_FILLED: "Part filled",
  FILLED: "Filled",
  CANCELLED: "Cancelled",
  REJECTED: "Rejected",
};

const price = (value) => Number(value).toFixed(2);

const describeEvent = (event) =>
  event.side.toLowerCase() +
  " " +
  (event.fillQuantity ?? event.quantity) +
  " " +
  event.symbol +
  (event.fillPrice != null ? " @ " + price(event.fillPrice) : "");

const savedAccount = () => {
  try {
    return localStorage.getItem("paper-trader.account") || "";
  } catch {
    return "";
  }
};

// Calls load now and then every interval, skipping ticks while the tab is hidden.
function usePoll(load, interval, deps = []) {
  const saved = useRef(load);
  saved.current = load;
  useEffect(() => {
    let active = true;
    const run = () => {
      if (document.visibilityState !== "hidden") saved.current(() => active);
    };
    run();
    const timer = setInterval(run, interval);
    document.addEventListener("visibilitychange", run);
    return () => {
      active = false;
      clearInterval(timer);
      document.removeEventListener("visibilitychange", run);
    };
  }, deps);
}

function EventRows({ events }) {
  return events.map((event) => (
    <li key={event.seq} className={"event " + event.type.toLowerCase()}>
      <span className="event-seq">#{event.seq}</span>
      <span className="event-type">{eventLabels[event.type]}</span>
      <span className="event-what">{describeEvent(event)}</span>
      <span className="event-with">
        {event.counterparty === "HOUSE"
          ? "vs house"
          : event.counterparty === "USER"
            ? "vs trader"
            : ""}
      </span>
    </li>
  ));
}

// A working order ticket on the live exchange. The first order opens a guest practice
// account and remembers it, so the terminal picks up where the demo left off.
function Demo({ onTrade }) {
  const [symbol, setSymbol] = useState("AAPL"),
    [side, setSide] = useState("BUY"),
    [quantity, setQuantity] = useState("5"),
    [limit, setLimit] = useState(""),
    [book, setBook] = useState(null),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [receipt, setReceipt] = useState(null),
    [account, setAccount] = useState(savedAccount),
    [portfolio, setPortfolio] = useState(null);

  usePoll(
    async (active) => {
      try {
        const next = await api("/book/" + symbol);
        if (active()) setBook(next);
      } catch {
        /* the ticket stays disabled without a book */
      }
    },
    4000,
    [symbol],
  );
  useEffect(() => {
    if (!account) return;
    api("/accounts/" + account + "/portfolio")
      .then(setPortfolio)
      .catch(() => setAccount(""));
  }, [account]);

  const current = book?.symbol === symbol ? book : null;
  const ladder = bookSides(current, 4);
  const shares = Number(quantity);
  const held =
    portfolio?.positions.find((p) => p.symbol === symbol)?.availableQuantity ??
    0;

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      let id = account;
      if (!id) {
        const created = await api("/accounts", {
          method: "POST",
          body: JSON.stringify({
            username: "guest_" + Math.random().toString(36).slice(2, 10),
          }),
        });
        id = String(created.id);
        try {
          localStorage.setItem("paper-trader.account", id);
        } catch {
          /* private browsing: the account still works for this visit */
        }
        setAccount(id);
      }
      const [latest] = await api("/events/recent?limit=1");
      await api("/accounts/" + id + "/orders", {
        method: "POST",
        headers: { "Idempotency-Key": crypto.randomUUID() },
        body: JSON.stringify({
          symbol,
          side,
          quantity: shares,
          ...(limit ? { type: "LIMIT", limitPrice: Number(limit) } : {}),
        }),
      });
      const [events, updated] = await Promise.all([
        api("/events?accountId=" + id + "&after=" + (latest?.seq ?? 0)),
        api("/accounts/" + id + "/portfolio"),
      ]);
      setReceipt(events);
      setPortfolio(updated);
      onTrade();
      setBook(await api("/book/" + symbol));
    } catch (problem) {
      setReceipt(null);
      setError(problem.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="demo" aria-label="Live demo">
      <header className="demo-head">
        <h2>Try it</h2>
        <div className="tabs" role="group" aria-label="Demo symbol">
          {demoSymbols.map((s) => (
            <button
              key={s}
              aria-pressed={s === symbol}
              onClick={() => {
                setSymbol(s);
                setLimit("");
                setReceipt(null);
                setError("");
              }}
            >
              {s}
            </button>
          ))}
        </div>
      </header>
      <div className="demo-body">
        <div className="demo-book" aria-label={symbol + " order book"}>
          <div className="ladder-head">
            <span>Price</span>
            <span>Size</span>
          </div>
          {["asks", "bids"].map((key) => (
            <div className={"ladder " + key} key={key}>
              {key === "bids" && (
                <div className="ladder-spread">
                  {current?.house
                    ? "Spread " +
                      price(
                        Number(current.house.ask) - Number(current.house.bid),
                      )
                    : "Waiting for a quote"}
                </div>
              )}
              {ladder[key].map((level) => (
                <button
                  type="button"
                  key={key + level.price + level.house}
                  className="level"
                  title="Use this price for a limit order"
                  onClick={() => setLimit(price(level.price))}
                >
                  <span className="level-price">{price(level.price)}</span>
                  <span>{level.house ? "house" : level.quantity}</span>
                  {!level.house && (
                    <i
                      style={{
                        width: (level.quantity / ladder.deepest) * 100 + "%",
                      }}
                    />
                  )}
                </button>
              ))}
            </div>
          ))}
        </div>
        <form className="demo-ticket" onSubmit={submit}>
          <div className="segmented">
            {["BUY", "SELL"].map((s) => (
              <button
                type="button"
                key={s}
                aria-pressed={side === s}
                className={side === s ? "active " + s.toLowerCase() : ""}
                onClick={() => setSide(s)}
              >
                {s === "BUY" ? "Buy" : "Sell"}
              </button>
            ))}
          </div>
          <label>
            Shares
            <input
              type="number"
              min="1"
              max="1000"
              step="1"
              required
              value={quantity}
              onChange={(e) => setQuantity(e.target.value)}
            />
          </label>
          <label>
            Limit price
            <input
              type="number"
              min="0.01"
              step="0.01"
              placeholder="Market"
              value={limit}
              onChange={(e) => setLimit(e.target.value)}
            />
          </label>
          <button
            className={"place " + side.toLowerCase()}
            disabled={busy || !current || !(shares > 0)}
          >
            {busy
              ? "Placing…"
              : (side === "BUY" ? "Buy " : "Sell ") +
                (shares > 0 ? shares + " " : "") +
                symbol}
          </button>
          <p className="demo-account">
            {portfolio
              ? "Account #" +
                portfolio.accountId +
                " · " +
                money(portfolio.cash) +
                " cash · " +
                held +
                " " +
                symbol
              : "Your first order opens a guest account with $100,000 of virtual cash."}
          </p>
        </form>
      </div>
      <div className="demo-result" aria-live="polite">
        {error && (
          <p className="demo-error" role="alert">
            {error}
          </p>
        )}
        {receipt && (
          <>
            <ol className="events" aria-label="What your order did">
              <EventRows events={receipt} />
            </ol>
            <a className="demo-next" href="/terminal">
              See it in the terminal →
            </a>
          </>
        )}
        {!error && !receipt && (
          <p className="demo-hint">
            This is the real exchange, not a mock-up. Place an order and the
            events it creates show up here.
          </p>
        )}
      </div>
    </section>
  );
}

function Record({ events, ledger }) {
  const profit = ledger
    ? ledger.house.reduce((sum, books) => sum + Number(books.profit ?? 0), 0)
    : null;
  return (
    <section id="record" className="home-section record">
      <div className="section-head">
        <h2>Everything is on the record</h2>
        <p>
          These numbers come from the running exchange. Each one links to the
          API that produced it.
        </p>
      </div>
      <div className="record-grid">
        <div>
          <h3>
            Latest order events <a href="/api/events/recent">/api/events</a>
          </h3>
          {events && events.length === 0 ? (
            <p className="quiet">
              No orders yet. Place one above and it appears here.
            </p>
          ) : (
            <ol className="events" aria-label="Latest order events">
              <EventRows events={events || []} />
            </ol>
          )}
        </div>
        <div>
          <h3>
            Ledger audit <a href="/api/ledger">/api/ledger</a>
          </h3>
          <dl className="facts">
            <div>
              <dt>Books</dt>
              <dd className={ledger && !ledger.balanced ? "negative" : ""}>
                {ledger == null
                  ? "Checking…"
                  : ledger === false
                    ? "Unavailable"
                    : ledger.balanced
                      ? "Balanced"
                      : "Out of balance"}
              </dd>
            </div>
            <div>
              <dt>Trades audited</dt>
              <dd>{ledger ? ledger.totals.trades.toLocaleString() : "—"}</dd>
            </div>
            <div>
              <dt>Accounts</dt>
              <dd>{ledger ? ledger.totals.accounts.toLocaleString() : "—"}</dd>
            </div>
            <div>
              <dt>Traders' cash</dt>
              <dd>{ledger ? money(ledger.totals.userCash) : "—"}</dd>
            </div>
            <div>
              <dt>House cash</dt>
              <dd>{ledger ? money(ledger.totals.houseCash) : "—"}</dd>
            </div>
            <div>
              <dt>Cash accounts started with</dt>
              <dd>{ledger ? money(ledger.totals.startingCash) : "—"}</dd>
            </div>
            <div>
              <dt>House profit at the last price</dt>
              <dd>{profit == null ? "—" : money(profit)}</dd>
            </div>
          </dl>
        </div>
      </div>
    </section>
  );
}

function Markets() {
  const [quotes, setQuotes] = useState([]),
    [mode, setMode] = useState("");
  usePoll(async (active) => {
    try {
      const [q, s] = await Promise.all([api("/quotes"), api("/quotes/status")]);
      if (active()) {
        setQuotes(q);
        setMode(s.mode);
      }
    } catch {
      if (active()) setMode("unavailable");
    }
  }, 15000);
  const feed =
    mode === "finnhub"
      ? "Provider quotes"
      : mode === "static"
        ? "Static prices"
        : mode === "unavailable"
          ? "Feed unavailable"
          : mode
            ? "Simulated prices"
            : "Connecting";
  return (
    <section id="markets" className="home-section">
      <div className="section-head">
        <h2>Markets</h2>
        <p>{feed}. Pick a stock to open its chart and order ticket.</p>
      </div>
      <div className="broker-market-table">
        <table aria-label="Available market quotes">
          <thead>
            <tr>
              <th>Stock</th>
              <th>Last price</th>
              <th>Quoted at</th>
              <th>
                <span className="sr-only">Open</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {Object.entries(names).map(([symbol, name]) => {
              const q = quotes.find((quote) => quote.symbol === symbol);
              const valid = q && new Date(q.asOf).getTime() > 0;
              return (
                <tr key={symbol}>
                  <td>
                    <a
                      className="home-quote"
                      href={"/terminal?symbol=" + symbol}
                    >
                      <b>{symbol}</b>
                      <small>{name}</small>
                    </a>
                  </td>
                  <td className="market-price">
                    {valid ? money(q.price) : "—"}
                  </td>
                  <td className="quote-time">
                    {valid
                      ? new Date(q.asOf).toLocaleTimeString([], {
                          hour: "2-digit",
                          minute: "2-digit",
                          second: "2-digit",
                        })
                      : "—"}
                  </td>
                  <td>
                    <a
                      className="quote-action"
                      href={"/terminal?symbol=" + symbol}
                      aria-label={"Open " + symbol + " in terminal"}
                    >
                      Trade
                    </a>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}

export default function Home() {
  const [menu, setMenu] = useState(false),
    [events, setEvents] = useState(null),
    [ledger, setLedger] = useState(null),
    [trades, setTrades] = useState(0);
  const close = () => setMenu(false);
  usePoll(
    async (active) => {
      try {
        const next = await api("/events/recent?limit=8");
        if (active()) setEvents(next);
      } catch {
        if (active()) setEvents((current) => current ?? []);
      }
    },
    3000,
    [trades],
  );
  usePoll(
    async (active) => {
      try {
        const report = await api("/ledger");
        if (active()) setLedger(report);
      } catch {
        if (active()) setLedger(false);
      }
    },
    30000,
    [trades],
  );
  return (
    <div className="home">
      <a className="home-skip" href="#home-main">
        Skip to content
      </a>
      <header className="home-nav">
        <a href="/" className="home-brand" aria-label="CrossBook home">
          CrossBook
        </a>
        <nav
          className={menu ? "home-links open" : "home-links"}
          aria-label="Main navigation"
          id="mobile-menu-content"
        >
          <a href="#record" onClick={close}>
            Live record
          </a>
          <a href="#internals" onClick={close}>
            How it works
          </a>
          <a href="#markets" onClick={close}>
            Markets
          </a>
          <a href="#questions" onClick={close}>
            FAQ
          </a>
          <a href="/surveillance">Surveillance</a>
          <a href={REPO} target="_blank" rel="noreferrer" onClick={close}>
            GitHub
          </a>
        </nav>
        <a href="/terminal" className="button nav-launch">
          Launch terminal
        </a>
        <button
          className="home-menu"
          aria-label="Toggle navigation"
          aria-expanded={menu}
          aria-controls="mobile-menu-content"
          onClick={() => setMenu((open) => !open)}
        >
          {menu ? <Icon name="close" /> : <span aria-hidden="true">☰</span>}
        </button>
      </header>

      <main id="home-main">
        <section className="hero">
          <div className="hero-copy">
            <h1>Trade on a real order book.</h1>
            <p>
              CrossBook is a stock exchange simulator. Your orders match against
              other traders on price and time, and a simulated market maker
              takes the rest. Fills can be partial, limit orders rest in the
              book, and every trade is audited.
            </p>
            <div className="hero-actions">
              <a className="button primary" href="/terminal?setup=1">
                Open practice account
              </a>
              <a className="button" href="/terminal">
                Explore the terminal
              </a>
            </div>
            <p className="hero-fine">
              $100,000 in virtual cash. No sign-up, no real money.
            </p>
          </div>
          <Demo onTrade={() => setTrades((count) => count + 1)} />
        </section>

        <Record events={events} ledger={ledger} />

        <section id="internals" className="home-section">
          <div className="section-head">
            <h2>How it works</h2>
            <p>
              A Spring Boot matching engine on PostgreSQL, with a React
              terminal. The code for each part is one click away.
            </p>
          </div>
          <dl className="internals">
            {internals.map(([title, text, label, href]) => (
              <div key={title}>
                <dt>{title}</dt>
                <dd>{text}</dd>
                <dd className="source">
                  <a href={href} target="_blank" rel="noreferrer">
                    {label} ↗
                  </a>
                </dd>
              </div>
            ))}
          </dl>
        </section>

        <Markets />

        <section id="questions" className="home-section">
          <div className="section-head">
            <h2>Questions</h2>
          </div>
          <div className="faq-list">
            {faqs.map(([question, answer]) => (
              <details key={question}>
                <summary>{question}</summary>
                <p>{answer}</p>
              </details>
            ))}
          </div>
        </section>
      </main>

      <footer className="home-footer">
        <nav aria-label="Footer navigation">
          <a href="/terminal">Terminal</a>
          <a href={REPO} target="_blank" rel="noreferrer">
            Source code
          </a>
          <a
            href={REPO + "/blob/main/docs/architecture.md"}
            target="_blank"
            rel="noreferrer"
          >
            Architecture notes
          </a>
          <a href="/api/ledger">Ledger audit</a>
        </nav>
        <p>
          CrossBook is a simulation. No real funds are held and no orders are
          sent to a brokerage. Practice accounts are shared in this demo, and an
          account ID is not a private credential. Results here do not predict
          real-market performance.
        </p>
      </footer>
    </div>
  );
}
