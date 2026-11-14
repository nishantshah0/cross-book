package io.github.nishantshah0.papertrader;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nishantshah0.papertrader.simulation.Simulation;
import io.github.nishantshah0.papertrader.trading.MatchingEngine;
import io.github.nishantshah0.papertrader.trading.Order;
import io.github.nishantshah0.papertrader.trading.OrderRejectedException;
import io.github.nishantshah0.papertrader.trading.OrderService;
import io.github.nishantshah0.papertrader.trading.OrderStateException;
import io.github.nishantshah0.papertrader.trading.OrderSide;
import io.github.nishantshah0.papertrader.trading.OrderType;
import io.github.nishantshah0.papertrader.trading.PlaceOrderRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

// The exchange under a simulated clock, with price impact and the book lean switched on
// and a demo feed that walks. If the same seed and the same calls give the same event
// log, prices, ledger and portfolios, a run can be replayed and its scores repeated.
@SpringBootTest(properties = {
        "papertrader.clock.mode=simulated",
        "papertrader.clock.start=2026-01-05T14:30:00Z",
        "papertrader.feed.mode=demo",
        "papertrader.feed.seed=42",
        "papertrader.feed.interval-ms=15000",
        "papertrader.house.spread-bps=10",
        "papertrader.house.impact-bps=10",
        "papertrader.house.depth-shares=100",
        "papertrader.house.imbalance-bps=4",
        "papertrader.starting-cash=100000.00",
        "papertrader.seed-quotes.SIMA=100.00",
        "papertrader.seed-quotes.SIMB=50.00"
})
class SimulationReplayTest extends ApiTestSupport {

    private static final Instant START = Instant.parse("2026-01-05T14:30:00Z");

    @Autowired
    Simulation simulation;

    @Autowired
    OrderService service;

    @Autowired
    MatchingEngine engine;

    @Test
    void theSameSeedAndTheSameOrdersReplayExactly() throws Exception {
        String first = session(42);
        String second = session(42);

        assertThat(second).isEqualTo(first);
        // and the session did enough to make that mean something
        JsonNode log = json.readTree(get("/api/events?limit=1000").getResponse().getContentAsString());
        assertThat(log.size()).isGreaterThan(60);
        assertThat(log.toString()).contains("\"counterparty\":\"USER\"", "\"counterparty\":\"HOUSE\"",
                "\"type\":\"CANCELLED\"", "\"type\":\"PARTIALLY_FILLED\"");
    }

    @Test
    void aDifferentSeedGivesADifferentRun() throws Exception {
        assertThat(session(7)).isNotEqualTo(session(42));
    }

    @Test
    void timeOnlyMovesWhenTheSimulationTicks() throws Exception {
        simulation.reset(42);
        long trader = account("timekeeper");

        assertThat(simulation.status().now()).isEqualTo(START);
        var ticked = read(post("/api/simulation/ticks?count=2", ""), Simulation.Status.class);
        assertThat(ticked.ticks()).isEqualTo(2);
        assertThat(ticked.now()).isEqualTo(START.plusSeconds(30));
        assertThat(ticked.clock()).isEqualTo("simulated");

        Order order = service.place(trader, market(OrderSide.BUY, "SIMA", 3));
        Thread.sleep(50); // real time passes; simulated time does not

        assertThat(order.getCreatedAt()).isEqualTo(START.plusSeconds(30));
        JsonNode events = json.readTree(get("/api/events?accountId=" + trader).getResponse().getContentAsString());
        assertThat(events.findValuesAsString("occurredAt")).containsOnly("2026-01-05T14:30:30Z");
        assertThat(simulation.status().now()).isEqualTo(START.plusSeconds(30));
    }

    // a fixed script: one trader buys stock and offers it inside the spread, another
    // buys from them, a third works a resting bid on another symbol, with a cancel
    private String session(long seed) throws Exception {
        simulation.reset(seed);
        long seller = account("seller");
        long buyer = account("buyer");
        long bidder = account("bidder");
        List<Long> bids = new ArrayList<>();
        place(seller, market(OrderSide.BUY, "SIMA", 200));
        for (int t = 0; t < 12; t++) {
            simulation.tick(1);
            var house = engine.book("SIMA", 1).house();
            place(seller, limit(OrderSide.SELL, "SIMA", 12, house.ask().subtract(new BigDecimal("0.02"))));
            place(buyer, market(OrderSide.BUY, "SIMA", 5 + t));
            BigDecimal feed = quote("SIMB");
            Order bid = place(bidder, limit(OrderSide.BUY, "SIMB", 15, feed.subtract(new BigDecimal("0.03"))));
            if (bid != null) {
                bids.add(bid.getId());
            }
            if (t == 6) {
                cancel(bidder, bids.getFirst());
            }
        }
        simulation.tick(3);
        return body("/api/events?limit=1000") + body("/api/quotes/history") + body("/api/ledger")
                + body("/api/accounts/" + seller + "/portfolio") + body("/api/accounts/" + buyer + "/portfolio")
                + body("/api/accounts/" + bidder + "/portfolio");
    }

    // refusals are part of the run too: they are logged, so they are compared
    private Order place(long account, PlaceOrderRequest request) {
        try {
            return service.place(account, request);
        } catch (OrderRejectedException e) {
            return null;
        }
    }

    // the price may already have filled it, which a replay must reproduce too
    private void cancel(long account, long orderId) {
        try {
            service.cancel(account, orderId);
        } catch (OrderStateException e) {
            // already filled
        }
    }

    private long account(String name) throws Exception {
        var created = post("/api/accounts", "{\"username\":\"" + name + "\"}");
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
    }

    private BigDecimal quote(String symbol) throws Exception {
        return new BigDecimal(json.readTree(body("/api/quotes/" + symbol)).get("price").asString())
                .setScale(2, RoundingMode.HALF_UP);
    }

    private String body(String uri) throws Exception {
        var result = get(uri);
        assertThat(result).hasStatus(HttpStatus.OK);
        return result.getResponse().getContentAsString();
    }

    private static PlaceOrderRequest market(OrderSide side, String symbol, int quantity) {
        return new PlaceOrderRequest(symbol, side, null, quantity, null);
    }

    private static PlaceOrderRequest limit(OrderSide side, String symbol, int quantity, BigDecimal price) {
        return new PlaceOrderRequest(symbol, side, OrderType.LIMIT, quantity, price.setScale(2, RoundingMode.HALF_UP));
    }
}
