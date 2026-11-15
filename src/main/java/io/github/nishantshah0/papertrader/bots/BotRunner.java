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

    public synchronized BotRunReport run(BotRunRequest request) {
        BotRunRequest run = request.withDefaults();
        if (engine.mode() != MarketMode.BOOK) {
            throw new BotRunException("bot markets need book mode, where bots trade only with each other; "
                    + "start the app with papertrader.market.mode=book");
        }
        List<String> symbols = symbols(run.symbols());
        simulation.reset(run.seed()); // refuses unless the clock is simulated
        long started = System.nanoTime();

        BotMarket market = new BotMarket(orders, engine, quotes, positions, simulation, symbols,
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

    // The roster, by kind and number. Makers split the symbols so each symbol has about
    // two of them, the way real market makers each cover a handful of names.
    private static List<Bot> roster(BotRunRequest run, List<String> symbols) {
        List<Bot> roster = new ArrayList<>();
        int makers = run.marketMakers();
        int each = makers == 0 ? 0 : Math.min(symbols.size(), Math.max(1, (2 * symbols.size() + makers - 1) / makers));
        for (int i = 0; i < makers; i++) {
            List<String> covers = new ArrayList<>();
            for (int j = 0; j < each; j++) {
                covers.add(symbols.get((i * each + j) % symbols.size()));
            }
            roster.add(new MarketMaker(i, run.seed(), List.copyOf(covers)));
        }
        for (int i = 0; i < run.noiseTraders(); i++) {
            roster.add(new NoiseTrader(i, run.seed()));
        }
        return roster;
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
        return new BotRunReport(run.seed(), run.ticks(), run.noiseTraders(), run.marketMakers(), symbols, start,
                simulation.status().now(), market.actions, market.placed, market.refused, market.cancelled,
                market.cancelsTooLate, trades, prices, ledger.run().balanced(), Math.round(seconds * 100) / 100.0,
                Math.round(market.actions / seconds));
    }
}
