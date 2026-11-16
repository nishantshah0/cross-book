package io.github.nishantshah0.papertrader.bots;

import io.github.nishantshah0.papertrader.PaperTraderProperties;
import io.github.nishantshah0.papertrader.account.AccountService;
import io.github.nishantshah0.papertrader.ledger.LedgerAudit;
import io.github.nishantshah0.papertrader.quote.QuoteCache;
import io.github.nishantshah0.papertrader.simulation.Simulation;
import io.github.nishantshah0.papertrader.trading.MarketMode;
import io.github.nishantshah0.papertrader.trading.MatchingEngine;
import io.github.nishantshah0.papertrader.trading.OrderService;
import io.github.nishantshah0.papertrader.trading.PositionRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.SplittableRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

// Runs a whole bot market in one call, in book mode on the simulated clock:
//
//   1. reset the exchange to the run's seed (prices, books, accounts all start over)
//   2. open one account per bot, in an order shuffled by the seed, under names like
//      trader_0042, so neither the id nor the name gives away what a bot is; who is who
//      goes in the answer key
//   3. give each bot its starting shares
//   4. wake the bots one at a time, in time order, moving the clock to each wake and
//      ticking the feed whenever a wake falls past the next tick
//
// Bots run inside the app and call the OrderService directly, one action at a time,
// so the run is exactly repeatable: the same request writes the same rows. Nothing
// else should trade on the exchange while a run is going.
@Service
public class BotRunner {

    private final Simulation simulation;
    private final AccountService accounts;
    private final OrderService orders;
    private final MatchingEngine engine;
    private final QuoteCache quotes;
    private final PositionRepository positions;
    private final AnswerKey answerKey;
    private final LedgerAudit ledger;
    private final JdbcTemplate jdbc;
    private final PaperTraderProperties properties;

    BotRunner(Simulation simulation, AccountService accounts, OrderService orders, MatchingEngine engine,
            QuoteCache quotes, PositionRepository positions, AnswerKey answerKey, LedgerAudit ledger,
            JdbcTemplate jdbc, PaperTraderProperties properties) {
        this.simulation = simulation;
        this.accounts = accounts;
        this.orders = orders;
        this.engine = engine;
        this.quotes = quotes;
        this.positions = positions;
        this.answerKey = answerKey;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.properties = properties;
    }

    // Refuses at once, before anything is touched, unless a run could go ahead: it needs
    // book mode, where bots trade only with each other, and the simulated clock.
    public void requireRunnable() {
        if (engine.mode() != MarketMode.BOOK) {
            throw new BotRunException("bot markets need book mode, where bots trade only with each other; "
                    + "start the app with papertrader.market.mode=book");
        }
        if (!simulation.status().clock().equals("simulated")) {
            throw new BotRunException("bot markets need the simulated clock, so a run can be replayed; "
                    + "start the app with papertrader.clock.mode=simulated");
        }
    }

    public synchronized BotRunReport run(BotRunRequest request) {
        BotRunRequest run = request.withDefaults();
        requireRunnable();
        List<String> symbols = symbols(run.symbols());
        simulation.reset(run.seed()); // refuses unless the clock is simulated
        long started = System.nanoTime();

        BotMarket market = new BotMarket(orders, engine, quotes, positions, simulation, answerKey, symbols,
                properties.startingCash());
        List<Bot> roster = roster(run, symbols);
        List<Bot> joining = shuffled(roster, Seeds.stream(run.seed(), 0, Seeds.SETUP));
        for (int i = 0; i < joining.size(); i++) {
            Bot bot = joining.get(i);
            bot.accountId = accounts.create("trader_%04d".formatted(i + 1)).getId();
            answerKey.add(bot);
        }
        joining.forEach(bot -> bot.allocate(market));

        Instant start = simulation.status().now();
        long end = start.plus(simulation.step().multipliedBy(run.ticks())).toEpochMilli();
        PriorityQueue<Bot> queue = new PriorityQueue<>(
                Comparator.comparingLong((Bot bot) -> bot.wakeAt).thenComparingLong(Bot::key));
        for (Bot bot : roster) {
            bot.wakeAt = start.toEpochMilli() + bot.nextGapMs();
            queue.add(bot);
        }
        while (!queue.isEmpty() && queue.peek().wakeAt < end) {
            Bot bot = queue.poll();
            moveClockTo(bot.wakeAt);
            bot.act(market);
            bot.wakeAt += bot.nextGapMs();
            queue.add(bot);
        }
        long remaining = run.ticks() - simulation.status().ticks();
        if (remaining > 0) {
            simulation.tick((int) remaining);
        }
        double seconds = (System.nanoTime() - started) / 1e9;
        return report(run, symbols, market, start, seconds);
    }

    // The roster, by kind and number. Makers and value traders each split the symbols so
    // every symbol has about two of each, the way real firms each cover a handful of names.
    private static List<Bot> roster(BotRunRequest run, List<String> symbols) {
        List<Bot> roster = new ArrayList<>();
        for (int i = 0; i < run.marketMakers(); i++) {
            roster.add(new MarketMaker(i, run.seed(), covers(i, run.marketMakers(), symbols)));
        }
        for (int i = 0; i < run.noiseTraders(); i++) {
            roster.add(new NoiseTrader(i, run.seed()));
        }
        for (int i = 0; i < run.bookReaders(); i++) {
            roster.add(new BookReader(i, run.seed()));
        }
        for (int i = 0; i < run.valueTraders(); i++) {
            roster.add(new ValueTrader(i, run.seed(), covers(i, run.valueTraders(), symbols)));
        }
        for (int i = 0; i < run.spoofers(); i++) {
            roster.add(new Spoofer(i, run.seed(), run.spoofLayers()));
        }
        for (int i = 0; i < run.lookalikes(); i++) {
            roster.add(Spoofer.lookalike(i, run.seed()));
        }
        return roster;
    }

    // the stocks the i-th of n bots of a kind follows: consecutive runs of symbols, about
    // two bots of the kind per symbol
    private static List<String> covers(int i, int n, List<String> symbols) {
        int each = Math.min(symbols.size(), Math.max(1, (2 * symbols.size() + n - 1) / n));
        List<String> covers = new ArrayList<>();
        for (int j = 0; j < each; j++) {
            covers.add(symbols.get((i * each + j) % symbols.size()));
        }
        return List.copyOf(covers);
    }

    // Moves the clock to a wake time: ticks for every tick at or before it, then
    // advances the rest of the way within the tick.
    private void moveClockTo(long millis) {
        Instant at = Instant.ofEpochMilli(millis);
        while (!at.isBefore(simulation.nextTick())) {
            simulation.tick(1);
        }
        Instant now = simulation.status().now();
        if (at.isAfter(now)) {
            simulation.advance(Duration.between(now, at));
        }
    }

    private List<String> symbols(List<String> requested) {
        if (requested.isEmpty()) {
            return quotes.symbols().stream().sorted().toList();
        }
        List<String> symbols = requested.stream().map(s -> s.toUpperCase(Locale.ROOT)).distinct().sorted().toList();
        symbols.forEach(quotes::require); // 404 for one the feed does not quote
        return symbols;
    }

    // Fisher-Yates with the run's setup stream
    private static List<Bot> shuffled(List<Bot> bots, SplittableRandom random) {
        List<Bot> shuffled = new ArrayList<>(bots);
        for (int i = shuffled.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Bot swap = shuffled.get(i);
            shuffled.set(i, shuffled.get(j));
            shuffled.set(j, swap);
        }
        return shuffled;
    }

    private BotRunReport report(BotRunRequest run, List<String> symbols, BotMarket market, Instant start,
            double seconds) {
        // a fill writes one row per side; count the buyer's, and leave out the house's
        // allocations, which are not trades between bots
        Map<String, long[]> traded = new HashMap<>();
        jdbc.query("""
                select symbol, count(*) as fills, sum(quantity) as shares
                from trades
                where side = 'BUY' and counterparty_order_id is not null
                group by symbol
                """, row -> {
            traded.put(row.getString("symbol"), new long[] {row.getLong("fills"), row.getLong("shares")});
        });
        List<BotRunReport.SymbolSummary> prices = new ArrayList<>();
        for (String symbol : symbols) {
            BigDecimal feed = quotes.require(symbol).price();
            BigDecimal last = engine.lastPrice(symbol).orElse(null);
            Double gap = last == null ? null
                    : Math.round((last.doubleValue() / feed.doubleValue() - 1) * 10_000 * 10) / 10.0;
            long[] counts = traded.getOrDefault(symbol, new long[2]);
            prices.add(new BotRunReport.SymbolSummary(symbol, feed, last, gap, counts[0], counts[1]));
        }
        long trades = prices.stream().mapToLong(BotRunReport.SymbolSummary::trades).sum();
        return new BotRunReport(run.seed(), run.ticks(), run.noiseTraders(), run.marketMakers(), run.bookReaders(),
                run.valueTraders(), run.spoofers(), run.spoofLayers(), run.lookalikes(), symbols, start, simulation.status().now(), market.actions, market.placed,
                market.refused, market.cancelled, market.cancelsTooLate, trades, prices, kinds(), activity(start), spoofing(),
                ledger.run().balanced(), Math.round(seconds * 100) / 100.0, Math.round(market.actions / seconds));
    }

    private List<BotRunReport.KindActivity> activity(Instant start) {
        return jdbc.query("""
                select k.kind, count(*) as orders, count(*) filter (where o.filled_quantity > 0) as filled,
                       coalesce(sum(o.filled_quantity), 0) as shares
                from orders o join answer_key k on k.account_id = o.account_id
                where o.created_at > ?::timestamptz
                group by k.kind order by k.kind
                """, (row, n) -> new BotRunReport.KindActivity(BotKind.valueOf(row.getString("kind")),
                row.getLong("orders"), row.getLong("filled"), row.getLong("shares")), start.toString());
    }

    private BotRunReport.Spoofing spoofing() {
        return jdbc.queryForObject("""
                select count(distinct (l.account_id, l.episode)) as episodes,
                       count(*) filter (where l.role = 'SPOOF') as fakes,
                       count(*) filter (where l.role = 'SPOOF' and o.filled_quantity > 0) as fakes_filled,
                       count(*) filter (where l.role = 'REAL') as reals,
                       count(*) filter (where l.role = 'REAL' and o.filled_quantity > 0) as reals_filled,
                       coalesce(sum(o.filled_quantity) filter (where l.role = 'REAL'), 0) as real_shares
                from answer_key_orders l join orders o on o.id = l.order_id
                """, (row, n) -> row.getLong("episodes") == 0 ? null : new BotRunReport.Spoofing(
                row.getLong("episodes"), row.getLong("fakes"), row.getLong("fakes_filled"), row.getLong("reals"),
                row.getLong("reals_filled"), row.getLong("real_shares")));
    }

    // Trading P&L by kind, at the feed price: the end value of each account, less the end
    // value of the cash and starting shares it had once allocations were done.
    private List<BotRunReport.KindSummary> kinds() {
        Map<String, BigDecimal> feed = new HashMap<>();
        quotes.all().forEach(q -> feed.put(q.symbol(), q.price()));
        Map<Long, BigDecimal> value = new HashMap<>();
        Map<Long, BotKind> kindOf = new HashMap<>();
        jdbc.query("""
                select k.account_id, k.kind, a.cash - a.starting_cash as cash
                from answer_key k join accounts a on a.id = k.account_id
                """, row -> {
            kindOf.put(row.getLong("account_id"), BotKind.valueOf(row.getString("kind")));
            value.put(row.getLong("account_id"), row.getBigDecimal("cash"));
        });
        // what it holds now, at the feed
        jdbc.query("select account_id, symbol, quantity from positions", row -> {
            value.merge(row.getLong("account_id"),
                    feed.get(row.getString("symbol")).multiply(BigDecimal.valueOf(row.getLong("quantity"))),
                    BigDecimal::add);
        });
        // less the starting shares at the feed, plus the cash they cost (allocations are
        // the only trades without a counterparty order in book mode)
        jdbc.query("""
                select account_id, symbol, sum(quantity) as shares, sum(quantity * price) as cost
                from trades where counterparty_order_id is null
                group by account_id, symbol
                """, row -> {
            BigDecimal held = feed.get(row.getString("symbol")).multiply(BigDecimal.valueOf(row.getLong("shares")));
            value.merge(row.getLong("account_id"), row.getBigDecimal("cost").subtract(held), BigDecimal::add);
        });
        Map<BotKind, BigDecimal> total = new java.util.EnumMap<>(BotKind.class);
        Map<BotKind, Integer> bots = new java.util.EnumMap<>(BotKind.class);
        kindOf.forEach((account, kind) -> {
            total.merge(kind, value.get(account), BigDecimal::add);
            bots.merge(kind, 1, Integer::sum);
        });
        List<BotRunReport.KindSummary> kinds = new ArrayList<>();
        total.forEach((kind, pnl) -> kinds.add(new BotRunReport.KindSummary(kind, bots.get(kind),
                pnl.setScale(2, java.math.RoundingMode.HALF_UP),
                pnl.divide(BigDecimal.valueOf(bots.get(kind)), 2, java.math.RoundingMode.HALF_UP))));
        return kinds;
    }
}
