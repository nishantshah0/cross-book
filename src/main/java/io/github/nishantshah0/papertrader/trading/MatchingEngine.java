package io.github.nishantshah0.papertrader.trading;

import io.github.nishantshah0.papertrader.quote.Quote;
import io.github.nishantshah0.papertrader.quote.QuoteCache;
import io.github.nishantshah0.papertrader.trading.OrderBook.Level;
import io.github.nishantshah0.papertrader.trading.OrderExecution.BookChange;
import io.github.nishantshah0.papertrader.trading.OrderExecution.BookOutOfSyncException;
import io.github.nishantshah0.papertrader.trading.OrderExecution.Result;
import io.github.nishantshah0.papertrader.time.SimulationClock;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// One matcher per symbol: a single thread that owns the symbol's OrderBook and runs
// every placement, cancel and quote-driven fill for it, one at a time. Within a symbol
// there are no races left to reason about, and symbols match in parallel.
//
// The book is an index, not the record. Postgres holds every order, and each change is
// its own transaction that locks the accounts it touches. The book is updated only after
// that transaction commits, from the changes it reports, so a rollback leaves the book as
// it was. If the book and the database ever disagree, the symbol's book is reloaded from
// its OPEN rows, which is also how it is built on startup.
@Component
public class MatchingEngine {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngine.class);
    // the book levels a snapshot keeps; readers ask for at most this many
    private static final int SNAPSHOT_LEVELS = 20;
    // where a waited-for task stands; see call()
    private static final int QUEUED = 0, STARTED = 1, ABANDONED = 2;

    private final Map<String, Matcher> matchers = new ConcurrentHashMap<>();
    private final OrderRepository orders;
    private final TradeRepository trades;
    private final OrderExecution execution;
    private final QuoteCache quotes;
    private final PriceImpact impact;
    private final int spreadBps;
    private final MarketMode mode;
    // under a simulated clock, quote ticks are driven by the simulation in symbol order,
    // so the engine ignores the quote events it would otherwise react to on its own
    private final boolean simulated;
    private final Duration timeout;

    MatchingEngine(OrderRepository orders, TradeRepository trades, OrderExecution execution, QuoteCache quotes,
            PriceImpact impact, @Value("${papertrader.house.spread-bps:10}") int spreadBps,
            @Value("${papertrader.market.mode:house}") String mode, Clock clock,
            @Value("${papertrader.engine.timeout-ms:10000}") long timeoutMs) {
        this.orders = orders;
        this.trades = trades;
        this.mode = MarketMode.parse(mode);
        this.execution = execution;
        this.quotes = quotes;
        this.impact = impact;
        this.spreadBps = spreadBps;
        this.simulated = SimulationClock.isSimulated(clock);
        this.timeout = Duration.ofMillis(timeoutMs);
        HouseQuote.around(BigDecimal.ONE, spreadBps); // fail at startup on a bad spread
    }

    // feed is the price the house leans away from; flowBps and bookBps are how far recent
    // trading and the resting orders lean it, so its mid is feed * (1 + (flow + book) / 10000).
    // In book mode house is always null and the leans are zero. lastPrice is the most
    // recent trade, null before the first.
    public record BookView(String symbol, MarketMode market, HouseQuote house, List<Level> bids, List<Level> asks,
            BigDecimal feed, double flowBps, double bookBps, BigDecimal lastPrice) {
    }

    // The book and the house as of the matcher's last change. Readers take the latest one
    // instead of queuing a task, so polling the book never waits behind an order.
    private record Snapshot(BookView view, HouseMarket house, double leanBps) {
    }

    private static final class Matcher {
        final String symbol;
        final OrderBook book;
        final ExecutorService thread;
        // The net shares users have bought from the house recently, which sets how far its
        // prices lean (see PriceImpact). Only the matcher's own thread writes it; it fades
        // back to zero on every quote tick, so it is not stored.
        double volume;
        // the price of the symbol's most recent trade, null before the first
        volatile BigDecimal lastPrice;
        volatile Snapshot snapshot;

        Matcher(String symbol) {
            this.symbol = symbol;
            this.book = new OrderBook(symbol);
            this.thread = Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().name("match-" + symbol).daemon().factory());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void rebuild() {
        var symbols = new TreeSet<>(quotes.symbols());
        orders.findByStatusOrderByCreatedAtAscIdAsc(OrderStatus.OPEN).forEach(order -> symbols.add(order.getSymbol()));
        for (String symbol : symbols) {
            submit(symbol, () -> {
                reload(symbol);
                sweep(symbol);
                return null;
            });
        }
    }

    public Order place(Long accountId, PlaceOrderRequest request, String key, String symbol) {
        return call(symbol, () -> {
            Matcher matcher = matcher(symbol);
            try {
                return apply(matcher, placeOnce(accountId, request, key, symbol, matcher));
            } catch (OrderRejectedException e) {
                // still on the symbol's thread, so the refusal takes its place in the log
                try {
                    execution.recordRefusal(accountId, symbol, request, e.getMessage());
                } catch (RuntimeException logFailure) {
                    log.warn("Could not log a refused {} order ({})", symbol, logFailure.getClass().getSimpleName());
                }
                throw e;
            } finally {
                sweep(symbol); // the order may have moved the house's quote through resting orders
            }
        });
    }

    private Result placeOnce(Long accountId, PlaceOrderRequest request, String key, String symbol, Matcher matcher) {
        try {
            return execution.place(accountId, request, key, symbol, matcher.book, house(symbol));
        } catch (BookOutOfSyncException e) {
            log.warn("{} book out of sync, reloading before retrying ({})", symbol, e.getMessage());
            reload(symbol);
            return execution.place(accountId, request, key, symbol, matcher.book, house(symbol));
        }
    }

    public Order cancel(Long accountId, Long orderId, String symbol) {
        return call(symbol, () -> {
            try {
                return apply(matcher(symbol), execution.cancel(accountId, orderId));
            } finally {
                sweep(symbol); // a cancel changes the book the house leans on
            }
        });
    }

    // shares bought from the house at the feed price, outside the market; see
    // OrderExecution.allocate
    public Order allocate(Long accountId, String symbol, int shares) {
        return call(symbol, () -> apply(matcher(symbol),
                execution.allocate(accountId, symbol, shares, quotes.require(symbol).price())));
    }

    // fills one resting order against the current house quote, if it crosses
    public OrderStatus match(long orderId, String symbol) {
        return call(symbol, () -> {
            Matcher matcher = matcher(symbol);
            RestingOrder resting = matcher.book.get(orderId).orElse(null);
            if (resting == null) {
                return orders.findById(orderId).map(Order::getStatus).orElseThrow();
            }
            return apply(matcher, execution.fillAgainstHouse(resting, house(symbol))).getStatus();
        });
    }

    // Runs on the feed thread for every new quote. It only queues the sweep, so a slow
    // fill for one symbol never holds up quotes for the others.
    @EventListener
    public void onQuote(Quote quote) {
        if (simulated) {
            return;
        }
        submit(quote.symbol(), () -> {
            tickOnMatcher(quote.symbol());
            return null;
        });
    }

    // One quote tick for a symbol, and waits for it: the lean fades and the house fills
    // any resting orders its new quote crosses. A simulation calls this for every symbol,
    // in order, so ticks never overlap and a replay does exactly the same work.
    public void tick(String symbol) {
        call(symbol, () -> {
            tickOnMatcher(symbol);
            return null;
        });
    }

    private void tickOnMatcher(String symbol) {
        Matcher matcher = matcher(symbol);
        matcher.volume = impact.decayed(matcher.volume);
        sweep(symbol);
        refresh(matcher);
    }

    // Empties every book and settles every lean, for a simulation that has just cleared
    // the database. Each matcher does its own, so no order is half way through.
    public void reset() {
        for (String symbol : matchers.keySet()) {
            call(symbol, () -> {
                Matcher matcher = matcher(symbol);
                matcher.book.clear();
                matcher.volume = 0;
                matcher.lastPrice = null;
                refresh(matcher);
                return null;
            });
        }
    }

    public BookView book(String symbol, int levels) {
        BookView view = snapshot(symbol).view();
        return new BookView(view.symbol(), view.market(), view.house(), view.bids().stream().limit(levels).toList(),
                view.asks().stream().limit(levels).toList(), view.feed(), view.flowBps(), view.bookBps(),
                view.lastPrice());
    }

    public int restingOrders(String symbol) {
        return call(symbol, () -> matcher(symbol).book.size());
    }

    // The house fills the resting orders its quote has moved through, each in its own
    // transaction, so one account's failure does not stop the rest. Every fill moves the
    // house, so each one is priced from the house as the last one left it.
    private void sweep(String symbol) {
        HouseMarket house = house(symbol);
        if (house == null) {
            return;
        }
        Matcher matcher = matcher(symbol);
        for (RestingOrder resting : matcher.book.crossedBy(house.touch())) {
            HouseMarket now = house(symbol);
            if (now == null || !matcher.book.contains(resting.id())) {
                continue;
            }
            try {
                apply(matcher, execution.fillAgainstHouse(resting, now));
            } catch (RuntimeException e) {
                log.warn("Order {} could not be filled, keeping it in the book ({})", resting.id(),
                        e.getClass().getSimpleName());
            }
        }
    }

    private void reload(String symbol) {
        Matcher matcher = matcher(symbol);
        matcher.book.clear();
        int rested = 0;
        for (Order order : orders.findBySymbolAndStatusOrderByCreatedAtAscIdAsc(symbol, OrderStatus.OPEN)) {
            if (order.getType() == OrderType.LIMIT) {
                matcher.book.add(RestingOrder.of(order));
                rested++;
            }
        }
        // in book mode the house's only trades are allocations, which are not market prices
        matcher.lastPrice = (mode == MarketMode.BOOK
                ? trades.findFirstBySymbolAndCounterpartyOrderIdIsNotNullOrderByIdDesc(symbol)
                : trades.findFirstBySymbolOrderByIdDesc(symbol)).map(Trade::getPrice).orElse(null);
        log.info("{} book loaded with {} resting limit orders", symbol, rested);
        refresh(matcher);
    }

    // What a market order for this many shares would pay per share right now. In house
    // mode it is the house's price, null while the quote is stale. In book mode it is the
    // average over the resting orders it would take, null if the levels a snapshot keeps
    // do not hold that many shares.
    public BigDecimal estimatePrice(String symbol, OrderSide side, int shares) {
        Snapshot snapshot = snapshot(symbol);
        if (mode == MarketMode.HOUSE) {
            return snapshot.house() == null ? null : snapshot.house().price(side, shares);
        }
        return OrderBook.averagePrice(side == OrderSide.BUY ? snapshot.view().asks() : snapshot.view().bids(), shares);
    }

    public MarketMode mode() {
        return mode;
    }

    // the price of the symbol's most recent trade, if it has traded; read without
    // waiting behind the matcher, and without starting one for an unknown symbol
    public Optional<BigDecimal> lastPrice(String symbol) {
        Matcher matcher = matchers.get(symbol);
        return matcher == null ? Optional.empty() : Optional.ofNullable(matcher.lastPrice);
    }

    // how far the house's mid currently sits from the feed price, in basis points
    public double leanBps(String symbol) {
        return snapshot(symbol).leanBps();
    }

    private Order apply(Matcher matcher, Result result) {
        for (BookChange change : result.changes()) {
            switch (change) {
                case BookChange.Reduce reduce -> matcher.book.reduce(reduce.orderId(), reduce.shares());
                case BookChange.Remove remove -> matcher.book.remove(remove.orderId());
                case BookChange.Rest rest -> matcher.book.add(rest.order());
            }
        }
        matcher.volume += result.houseVolume();
        if (result.lastPrice() != null) {
            matcher.lastPrice = result.lastPrice();
        }
        refresh(matcher);
        return result.order();
    }

    // publishes what readers see; runs on the matcher's thread after every change
    private void refresh(Matcher matcher) {
        Quote feed = quotes.get(matcher.symbol).orElse(null);
        HouseMarket house = house(matcher.symbol);
        double bookBps = feed == null ? 0 : bookLean(matcher, feed);
        double flowBps = impact.pressureBps(matcher.volume);
        matcher.snapshot = new Snapshot(new BookView(matcher.symbol, mode, house == null ? null : house.touch(),
                matcher.book.depth(OrderSide.BUY, SNAPSHOT_LEVELS), matcher.book.depth(OrderSide.SELL, SNAPSHOT_LEVELS),
                feed == null ? null : feed.price(), round(flowBps), round(bookBps), matcher.lastPrice), house,
                flowBps + bookBps);
    }

    // The latest snapshot, without waiting behind the matcher. A snapshot only refreshes
    // when the book changes, so the feed can go stale under one (a provider outage leaves
    // no event behind): then the house it shows is gone, exactly as orders would find it.
    private Snapshot snapshot(String symbol) {
        Snapshot snapshot = matcher(symbol).snapshot;
        if (snapshot == null) {
            return call(symbol, () -> {
                Matcher matcher = matcher(symbol);
                refresh(matcher);
                return matcher.snapshot;
            });
        }
        if (snapshot.house() != null && quotes.get(symbol).filter(quotes::isTradable).isEmpty()) {
            BookView view = snapshot.view();
            return new Snapshot(new BookView(view.symbol(), view.market(), null, view.bids(), view.asks(), view.feed(),
                    view.flowBps(), view.bookBps(), view.lastPrice()), null, snapshot.leanBps());
        }
        return snapshot;
    }

    // The house as it stands: the feed price, leaned by the resting orders near it and by
    // the net volume users have traded with it. Nothing while the quote is stale, and
    // never in book mode, which leaves every house path (sweeps, house fills) idle.
    private HouseMarket house(String symbol) {
        if (mode == MarketMode.BOOK) {
            return null;
        }
        Quote quote = quotes.get(symbol).orElse(null);
        if (quote == null || !quotes.isTradable(quote)) {
            return null;
        }
        Matcher matcher = matcher(symbol);
        return new HouseMarket(quote.price(), bookLean(matcher, quote), matcher.volume, spreadBps, impact);
    }

    private double bookLean(Matcher matcher, Quote feed) {
        if (mode == MarketMode.BOOK) {
            return 0;
        }
        return impact.imbalanceSkew(
                matcher.book.sharesNear(OrderSide.BUY, feed.price(), PriceImpact.IMBALANCE_WINDOW_BPS),
                matcher.book.sharesNear(OrderSide.SELL, feed.price(), PriceImpact.IMBALANCE_WINDOW_BPS));
    }

    private static double round(double bps) {
        return Math.round(bps * 100) / 100.0;
    }

    private Matcher matcher(String symbol) {
        return matchers.computeIfAbsent(symbol, Matcher::new);
    }

    private <T> Future<T> submit(String symbol, Callable<T> task) {
        return matcher(symbol).thread.submit(task);
    }

    // Waits for a task on the symbol's thread and rethrows what it threw, so callers see
    // the same OrderRejectedException or NotFoundException as a direct call would raise.
    //
    // If the wait runs out while the task is still queued, it is cancelled before it ever
    // starts, so EngineBusyException (a 503) always means nothing happened and the request
    // is safe to retry. If the task has already started, it is inside its transaction and
    // will finish, so the caller waits for its real outcome instead of being told it failed.
    //
    // Future.cancel cannot tell the two cases apart (it succeeds on a running task too), so
    // the task and the caller race to claim it: whichever moves it out of QUEUED first wins.
    private <T> T call(String symbol, Callable<T> task) {
        AtomicInteger state = new AtomicInteger(QUEUED);
        var future = submit(symbol, () -> state.compareAndSet(QUEUED, STARTED) ? task.call() : null);
        try {
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (state.compareAndSet(QUEUED, ABANDONED)) {
                    throw new EngineBusyException(symbol);
                }
                return future.get();
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            // the same race as a timeout: a 503 only if the task can no longer start; if it
            // already has, wait out its real result and keep the interrupt for the caller
            if (state.compareAndSet(QUEUED, ABANDONED)) {
                Thread.currentThread().interrupt();
                throw new EngineBusyException(symbol);
            }
            try {
                return uninterruptibly(future);
            } finally {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static <T> T uninterruptibly(Future<T> future) {
        while (true) {
            try {
                return future.get();
            } catch (InterruptedException ignored) {
                // keep waiting; the caller's interrupt is restored once the result is in
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException(e.getCause());
            }
        }
    }

    @PreDestroy
    void stop() {
        matchers.values().forEach(matcher -> matcher.thread.shutdownNow());
    }
}
