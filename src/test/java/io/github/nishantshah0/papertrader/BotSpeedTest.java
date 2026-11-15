package io.github.nishantshah0.papertrader;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nishantshah0.papertrader.account.AccountService;
import io.github.nishantshah0.papertrader.simulation.Simulation;
import io.github.nishantshah0.papertrader.trading.MatchingEngine;
import io.github.nishantshah0.papertrader.trading.OrderRejectedException;
import io.github.nishantshah0.papertrader.trading.OrderService;
import io.github.nishantshah0.papertrader.trading.OrderSide;
import io.github.nishantshah0.papertrader.trading.OrderStateException;
import io.github.nishantshah0.papertrader.trading.OrderStatus;
import io.github.nishantshah0.papertrader.trading.OrderType;
import io.github.nishantshah0.papertrader.trading.PlaceOrderRequest;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// How fast the exchange runs a bot market one action at a time, the way a replayable
// run has to: a mix of market makers quoting and cancelling and takers crossing the
// spread, in book mode on a simulated clock. It counts the SQL statements each action
// sends, which is what sets the speed, and fails if that count creeps back up.
@SpringBootTest(properties = {
        "papertrader.market.mode=book",
        "papertrader.clock.mode=simulated",
        "papertrader.feed.mode=static",
        "papertrader.starting-cash=1000000.00",
        "papertrader.seed-quotes.SPA=100.00",
        "papertrader.seed-quotes.SPB=100.00",
        "papertrader.seed-quotes.SPC=100.00",
        "papertrader.seed-quotes.SPD=100.00",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"
})
class BotSpeedTest extends ApiTestSupport {

    private static final List<String> SYMBOLS = List.of("SPA", "SPB", "SPC", "SPD");
    private static final int MAKERS = 20, TAKERS = 40, ACTIONS = 4_000, WARMUP = 1_000;

    @Autowired
    AccountService accounts;

    @Autowired
    OrderService service;

    @Autowired
    Simulation simulation;

    @Autowired
    MatchingEngine engine;

    @Autowired
    EntityManagerFactory entities;

    record Bot(long accountId, boolean maker, Map<String, ArrayDeque<Long>> open) {
    }

    @Test
    void oneActionAtATime() {
        Random random = new Random(1);
        List<Bot> bots = new ArrayList<>();
        for (int i = 0; i < MAKERS + TAKERS; i++) {
            long id = accounts.create("speed_" + i).getId();
            for (String symbol : SYMBOLS) {
                simulation.allocate(id, symbol, i < MAKERS ? 500 : 100);
            }
            Map<String, ArrayDeque<Long>> open = new HashMap<>();
            SYMBOLS.forEach(symbol -> open.put(symbol, new ArrayDeque<>()));
            bots.add(new Bot(id, i < MAKERS, open));
        }
        for (int i = 0; i < WARMUP; i++) {
            act(bots, random, i);
        }

        Statistics stats = entities.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        long started = System.nanoTime();
        int[] kinds = new int[3];
        for (int i = 0; i < ACTIONS; i++) {
            kinds[act(bots, random, i)]++;
        }
        double seconds = (System.nanoTime() - started) / 1e9;
        double perAction = (double) stats.getPrepareStatementCount() / ACTIONS;

        System.out.printf("bot speed: %d actions (%d limit, %d market, %d cancel) in %.2fs = %.0f actions/s, "
                + "%.1f SQL statements and %.1f transactions per action%n", ACTIONS, kinds[0], kinds[1], kinds[2],
                seconds, ACTIONS / seconds, perAction, (double) stats.getTransactionCount() / ACTIONS);
        assertThat(perAction).as("SQL statements per action").isLessThan(8.5);
    }

    // one bot does one thing; returns 0 for a limit order, 1 for a market order, 2 for a cancel
    private int act(List<Bot> bots, Random random, int step) {
        if (step % 1_000 == 999) {
            simulation.tick(1);
        } else {
            simulation.advance(Duration.ofMillis(10));
        }
        Bot bot = bots.get(random.nextInt(bots.size()));
        String symbol = SYMBOLS.get(random.nextInt(SYMBOLS.size()));
        OrderSide side = random.nextBoolean() ? OrderSide.BUY : OrderSide.SELL;
        try {
            if (!bot.maker()) {
                service.place(bot.accountId(), new PlaceOrderRequest(symbol, side, OrderType.MARKET,
                        1 + random.nextInt(5), null));
                return 1;
            }
            ArrayDeque<Long> open = bot.open().get(symbol);
            if (open.size() >= 4) {
                service.cancel(bot.accountId(), open.removeFirst());
                return 2;
            }
            BigDecimal reference = engine.lastPrice(symbol).orElse(new BigDecimal("100"));
            double offset = (0.0005 + random.nextDouble() * 0.005) * (side == OrderSide.BUY ? -1 : 1);
            BigDecimal price = reference.multiply(BigDecimal.valueOf(1 + offset)).setScale(2, RoundingMode.HALF_UP);
            var order = service.place(bot.accountId(), new PlaceOrderRequest(symbol, side, OrderType.LIMIT,
                    1 + random.nextInt(10), price));
            if (order.getStatus() == OrderStatus.OPEN) {
                open.addLast(order.getId());
            }
            return 0;
        } catch (OrderRejectedException | OrderStateException e) {
            return bot.maker() ? 0 : 1; // refused or already filled: still a round of work
        }
    }
}
