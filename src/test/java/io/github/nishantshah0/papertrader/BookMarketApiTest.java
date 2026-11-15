package io.github.nishantshah0.papertrader;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nishantshah0.papertrader.account.AccountResponse;
import io.github.nishantshah0.papertrader.portfolio.PortfolioResponse;
import io.github.nishantshah0.papertrader.simulation.Simulation;
import io.github.nishantshah0.papertrader.trading.OrderEventResponse;
import io.github.nishantshah0.papertrader.trading.OrderResponse;
import io.github.nishantshah0.papertrader.trading.OrderStatus;
import io.github.nishantshah0.papertrader.trading.TradeResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

// A market with no house, under a simulated clock: orders only trade with each other,
// so the book alone sets the price, and positions are worth what the stock last traded
// at. Shares come from allocations, bought from the house at the feed price before
// trading starts. Each test uses its own symbol, so no book carries over between them.
@SpringBootTest(properties = {
        "papertrader.market.mode=book",
        "papertrader.clock.mode=simulated",
        "papertrader.clock.start=2026-01-05T14:30:00Z",
        "papertrader.feed.mode=static",
        "papertrader.feed.interval-ms=15000",
        "papertrader.starting-cash=10000.00",
        "papertrader.seed-quotes.BKA=100.00",
        "papertrader.seed-quotes.BKB=100.00",
        "papertrader.seed-quotes.BKC=100.00",
        "papertrader.seed-quotes.BKD=100.00",
        "papertrader.seed-quotes.BKE=100.00",
        "papertrader.seed-quotes.BKF=100.00"
})
class BookMarketApiTest extends ApiTestSupport {

    private static final Instant START = Instant.parse("2026-01-05T14:30:00Z");
    private static final Duration TICK = Duration.ofSeconds(15);

    @Test
    void aMarketOrderWithNothingToTradeWithIsRefused() throws Exception {
        AccountResponse buyer = createAccount();

        var refused = post(orders(buyer), "{\"symbol\":\"BKA\",\"side\":\"BUY\",\"quantity\":5}");

        assertThat(refused).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(detail(refused)).isEqualTo("no sell orders in the BKA book to trade with");
        List<OrderEventResponse> events = events(buyer);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type().name()).isEqualTo("REJECTED");
            assertThat(event.orderId()).isNull();
        });
    }

    @Test
    void aLimitOrderOnlyEverTradesWithAnotherTrader() throws Exception {
        AccountResponse buyer = createAccount();

        // five times the feed price: in house mode the house would sell straight away
        OrderResponse bid = place(buyer, "{\"symbol\":\"BKB\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":2,"
                + "\"limitPrice\":500.00}");
        post("/api/simulation/ticks?count=3", "");

        assertThat(bid.status()).isEqualTo(OrderStatus.OPEN);
        assertThat(order(buyer, bid.id()).status()).isEqualTo(OrderStatus.OPEN);
        assertThat(order(buyer, bid.id()).filledQuantity()).isZero();
        JsonNode book = json.readTree(get("/api/book/BKB").getResponse().getContentAsString());
        assertThat(book.get("market").asString()).isEqualTo("BOOK");
        assertThat(book.get("house").isNull()).isTrue();
        assertThat(book.get("bids").get(0).get("price").decimalValue()).isEqualByComparingTo("500.00");
    }

    @Test
    void anAllocationIsBoughtFromTheHouseAtTheFeedPriceAndSetsNoPrice() throws Exception {
        AccountResponse maker = createAccount();

        OrderResponse allocation = allocate(maker, "BKC", 20);

        assertThat(allocation.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(allocation.avgFillPrice()).isEqualByComparingTo("100.00");
        PortfolioResponse portfolio = portfolio(maker);
        assertThat(portfolio.cash()).isEqualByComparingTo("8000.00");
        assertThat(portfolio.totalValue()).isEqualByComparingTo("10000.00"); // valued at the feed: no trade yet
        assertThat(json.readTree(get("/api/book/BKC").getResponse().getContentAsString()).get("lastPrice").isNull())
                .isTrue();
        assertThat(post("/api/simulation/allocations?accountId=" + maker.id() + "&symbol=BKC&shares=1000", ""))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT); // 100,000 of shares on 8,000 of cash
    }

    // The buyer can afford what the book offers, not 40 shares at any price, and that
    // is all a market order needs: it takes what is there and the rest is cancelled.
    @Test
    void aMarketOrderTakesWhatTheBookHasAndCancelsTheRest() throws Exception {
        AccountResponse maker = createAccount();
        AccountResponse buyer = createAccount();
        allocate(maker, "BKD", 5);
        place(maker, "{\"symbol\":\"BKD\",\"side\":\"SELL\",\"type\":\"LIMIT\",\"quantity\":3,\"limitPrice\":101.00}");
        place(maker, "{\"symbol\":\"BKD\",\"side\":\"SELL\",\"type\":\"LIMIT\",\"quantity\":2,\"limitPrice\":102.00}");

        assertThat(estimate("BKD", 5)).isEqualByComparingTo("101.4000"); // (3 * 101 + 2 * 102) / 5
        assertThat(estimate("BKD", 6)).isNull(); // more than the book holds

        OrderResponse buy = place(buyer, "{\"symbol\":\"BKD\",\"side\":\"BUY\",\"quantity\":40}");

        assertThat(buy.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(buy.filledQuantity()).isEqualTo(5);
        List<TradeResponse> trades = readList(get("/api/accounts/" + buyer.id() + "/trades"),
                new TypeReference<List<TradeResponse>>() {});
        assertThat(trades).extracting(TradeResponse::price).map(BigDecimal::stripTrailingZeros)
                .containsExactlyInAnyOrder(new BigDecimal("101"), new BigDecimal("102"));
        assertThat(portfolio(buyer).cash()).isEqualByComparingTo("9493.00"); // 10,000 - 303 - 204
        assertThat(events(buyer)).extracting(e -> e.type().name())
                .containsExactly("PLACED", "PARTIALLY_FILLED", "PARTIALLY_FILLED", "CANCELLED");
    }

    // Nothing pulls the price back to the feed: buying through thin asks moves it as far
    // as the book lets it, and every position is valued there.
    @Test
    void tradingMovesThePriceAndPositionsAreValuedAtTheLastTrade() throws Exception {
        AccountResponse maker = createAccount();
        AccountResponse buyer = createAccount();
        allocate(maker, "BKE", 4);
        place(maker, "{\"symbol\":\"BKE\",\"side\":\"SELL\",\"type\":\"LIMIT\",\"quantity\":1,\"limitPrice\":100.00}");
        place(maker, "{\"symbol\":\"BKE\",\"side\":\"SELL\",\"type\":\"LIMIT\",\"quantity\":1,\"limitPrice\":150.00}");

        place(buyer, "{\"symbol\":\"BKE\",\"side\":\"BUY\",\"quantity\":2}");

        JsonNode book = json.readTree(get("/api/book/BKE").getResponse().getContentAsString());
        assertThat(book.get("lastPrice").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(book.get("feed").decimalValue()).isEqualByComparingTo("100.00");
        // the buyer paid 250 for two shares now worth 150 each
        PortfolioResponse buyerBooks = portfolio(buyer);
        assertThat(buyerBooks.marketValue()).isEqualByComparingTo("300.00");
        assertThat(buyerBooks.totalValue()).isEqualByComparingTo("10050.00");
        // the maker spent 400 on four shares at the feed and sold two for 250; two are left
        PortfolioResponse makerBooks = portfolio(maker);
        assertThat(makerBooks.positions().get(0).lastPrice()).isEqualByComparingTo("150.00");
        assertThat(makerBooks.totalValue()).isEqualByComparingTo("10150.00"); // 9,850 cash + 2 * 150
    }

    @Test
    void theClockMovesWithinATickSoEventsGetTheirOwnTimes() throws Exception {
        AccountResponse trader = createAccount();
        Simulation.Status ticked = read(post("/api/simulation/ticks?count=1", ""), Simulation.Status.class);
        Instant next = START.plus(TICK.multipliedBy(ticked.ticks() + 1));
        assertThat(ticked.now()).isEqualTo(START.plus(TICK.multipliedBy(ticked.ticks())));

        OrderResponse bid = place(trader, "{\"symbol\":\"BKF\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":1,"
                + "\"limitPrice\":90.00}");
        post("/api/simulation/advance?millis=250", "");
        assertThat(delete(orders(trader) + "/" + bid.id())).hasStatus(HttpStatus.OK);

        List<OrderEventResponse> events = events(trader);
        assertThat(events).extracting(e -> e.type().name()).containsExactly("PLACED", "CANCELLED");
        assertThat(Duration.between(events.get(0).occurredAt(), events.get(1).occurredAt()))
                .isEqualTo(Duration.ofMillis(250));

        // up to just short of the next tick is fine; reaching it is the tick's job
        Simulation.Status status = read(get("/api/simulation"), Simulation.Status.class);
        long room = Duration.between(status.now(), next).toMillis();
        assertThat(post("/api/simulation/advance?millis=" + room, "")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(post("/api/simulation/advance?millis=" + (room - 1), "")).hasStatus(HttpStatus.OK);
        // and the tick lands on the grid, not a full interval after wherever the clock was
        Simulation.Status after = read(post("/api/simulation/ticks?count=1", ""), Simulation.Status.class);
        assertThat(after.now()).isEqualTo(next);
    }

    private OrderResponse place(AccountResponse account, String body) throws Exception {
        var result = post(orders(account), body);
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return read(result, OrderResponse.class);
    }

    private OrderResponse allocate(AccountResponse account, String symbol, int shares) throws Exception {
        var result = post("/api/simulation/allocations?accountId=" + account.id() + "&symbol=" + symbol
                + "&shares=" + shares, "");
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return read(result, OrderResponse.class);
    }

    private OrderResponse order(AccountResponse account, long id) throws Exception {
        return read(get(orders(account) + "/" + id), OrderResponse.class);
    }

    private BigDecimal estimate(String symbol, int shares) throws Exception {
        JsonNode price = json.readTree(get("/api/book/" + symbol + "/estimate?side=BUY&shares=" + shares)
                .getResponse().getContentAsString()).get("price");
        return price.isNull() ? null : price.decimalValue();
    }

    private List<OrderEventResponse> events(AccountResponse account) throws Exception {
        return readList(get("/api/events?accountId=" + account.id()), new TypeReference<List<OrderEventResponse>>() {});
    }
}
