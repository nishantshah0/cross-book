package io.github.nishantshah0.papertrader.trading;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

// How fast the order book matches on its own: no database, no locks, one thread, which is
// how each symbol's book runs in the app (one matcher thread owns it). The app as a whole
// is held to about 2,000 orders a second by the database, because each order commits its
// fills, balances and event-log rows in one transaction. This measures the part that
// isn't the database.
//
// Each operation is one incoming order or cancel from a seeded stream: 55% limit orders
// within 50 cents of 100.00 (about a fifth of them cross the spread and trade), 45%
// cancels of a random resting order. The book is primed to about 2,000 resting orders and
// the mix keeps it near that size. A trade plans its fills with match(), then applies
// them with reduce(), as the matching engine does once the fills have committed.
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(3)
public class OrderBookBenchmark {

    private static final int OPS = 1 << 20;
    private static final BigDecimal[] PRICES = new BigDecimal[101]; // 99.50 to 100.50, by the cent

    static {
        for (int i = 0; i < PRICES.length; i++) {
            PRICES[i] = BigDecimal.valueOf(9950 + i, 2);
        }
    }

    private OrderBook book;
    private final List<Long> resting = new ArrayList<>();
    private int[] kind, price, shares, side, pick;
    private int next;
    private long ids;

    @Setup(Level.Iteration)
    public void setUp() {
        SplittableRandom random = new SplittableRandom(42);
        kind = new int[OPS];
        price = new int[OPS];
        shares = new int[OPS];
        side = new int[OPS];
        pick = new int[OPS];
        for (int i = 0; i < OPS; i++) {
            kind[i] = random.nextInt(100) < 55 ? 0 : 1;
            side[i] = random.nextInt(2);
            // buyers mostly bid below 100.00 and sellers ask above it; the tails cross
            int offset = (int) Math.round(random.nextDouble() * 50 - 10);
            price[i] = side[i] == 0 ? 50 - offset : 50 + offset;
            price[i] = Math.max(0, Math.min(100, price[i]));
            shares[i] = 1 + random.nextInt(500);
            pick[i] = random.nextInt(Integer.MAX_VALUE);
        }
        book = new OrderBook("BENCH");
        resting.clear();
        ids = 0;
        next = 0;
        while (book.size() < 2_000) {
            step();
        }
    }

    @Benchmark
    public int orderOrCancel(Blackhole hole) {
        int filled = step();
        hole.consume(book.size());
        return filled;
    }

    // one incoming order or cancel; returns the shares it traded
    private int step() {
        int i = next++ & (OPS - 1);
        if (kind[i] == 1 && !resting.isEmpty()) {
            int at = pick[i] % resting.size();
            long id = resting.get(at);
            resting.set(at, resting.getLast());
            resting.removeLast();
            book.remove(id); // may already have filled; then it's a cancel that came too late
            return 0;
        }
        long id = ++ids;
        OrderSide orderSide = side[i] == 0 ? OrderSide.BUY : OrderSide.SELL;
        BigDecimal limit = PRICES[price[i]];
        int wanted = shares[i];
        int traded = 0;
        for (OrderBook.PlannedFill fill : book.match(orderSide, limit, wanted, id, null)) {
            book.reduce(fill.maker().id(), fill.quantity());
            traded += fill.quantity();
        }
        if (traded < wanted) {
            book.add(new RestingOrder(id, id, "BENCH", orderSide, limit, wanted - traded));
            resting.add(id);
        }
        return traded;
    }
}
