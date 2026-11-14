package io.github.nishantshah0.papertrader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.nishantshah0.papertrader.account.AccountResponse;
import io.github.nishantshah0.papertrader.quote.Quote;
import io.github.nishantshah0.papertrader.quote.QuoteCache;
import io.github.nishantshah0.papertrader.trading.OrderEvent.Type;
import io.github.nishantshah0.papertrader.trading.OrderEventResponse;
import io.github.nishantshah0.papertrader.trading.OrderResponse;
import io.github.nishantshah0.papertrader.trading.OrderService;
import io.github.nishantshah0.papertrader.trading.OrderStatus;
import io.github.nishantshah0.papertrader.trading.TradeResponse.Counterparty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import tools.jackson.core.type.TypeReference;

// Quotes at 50.00 with a 1% house spread: the house bids 49.75 and asks 50.25, so
// orders inside that gap trade between users. Each test has its own symbol.
@SpringBootTest(properties = {
        "papertrader.feed.mode=static",
        "papertrader.house.spread-bps=100",
        "papertrader.starting-cash=10000.00",
        "papertrader.seed-quotes.EVT=50.00",
        "papertrader.seed-quotes.EVP=50.00",
        "papertrader.seed-quotes.EVQ=50.00"
})
class OrderEventLogTest extends ApiTestSupport {

    @Autowired
    OrderService service;

    @Autowired
    QuoteCache quotes;

    @Test
    void everyOrderEventIsLoggedInTheOrderItHappened() throws Exception {
        var seller = createAccount();
        var buyer = createAccount();

        long stock = place(seller, "{\"symbol\":\"EVT\",\"side\":\"BUY\",\"quantity\":10}");
        long ask = place(seller,
                "{\"symbol\":\"EVT\",\"side\":\"SELL\",\"type\":\"LIMIT\",\"quantity\":10,\"limitPrice\":50.10}");
        long firstBuy = place(buyer, "{\"symbol\":\"EVT\",\"side\":\"BUY\",\"quantity\":4}");
        long lowBid = place(buyer,
                "{\"symbol\":\"EVT\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":3,\"limitPrice\":49.00}");
        assertThat(delete(orders(buyer) + "/" + lowBid)).hasStatus(HttpStatus.OK);
        long secondBuy = place(buyer, "{\"symbol\":\"EVT\",\"side\":\"BUY\",\"quantity\":8}");
        var refused = post(orders(buyer), "{\"symbol\":\"EVT\",\"side\":\"SELL\",\"quantity\":1000}");
        assertThat(refused).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);

        List<OrderEventResponse> log = events("symbol=EVT");

        assertThat(log).extracting(OrderEventResponse::type, OrderEventResponse::orderId,
                OrderEventResponse::fillQuantity, OrderEventResponse::fillPrice, OrderEventResponse::counterparty,
                OrderEventResponse::filledQuantity).containsExactly(
                        // the seller buys stock from the house
                        tuple(Type.PLACED, stock, null, null, null, 0),
                        tuple(Type.FILLED, stock, 10, price("50.25"), Counterparty.HOUSE, 10),
                        // and offers it inside the spread, where it rests
                        tuple(Type.PLACED, ask, null, null, null, 0),
                        // the buyer takes 4 from the seller: the taker's fill, then the maker's
                        tuple(Type.PLACED, firstBuy, null, null, null, 0),
                        tuple(Type.FILLED, firstBuy, 4, price("50.10"), Counterparty.USER, 4),
                        tuple(Type.PARTIALLY_FILLED, ask, 4, price("50.10"), Counterparty.USER, 4),
                        // a bid below the house rests, then is cancelled
                        tuple(Type.PLACED, lowBid, null, null, null, 0),
                        tuple(Type.CANCELLED, lowBid, null, null, null, 0),
                        // 8 more: the seller's last 6, then 2 from the house
                        tuple(Type.PLACED, secondBuy, null, null, null, 0),
                        tuple(Type.PARTIALLY_FILLED, secondBuy, 6, price("50.10"), Counterparty.USER, 6),
                        tuple(Type.FILLED, ask, 6, price("50.10"), Counterparty.USER, 10),
                        tuple(Type.FILLED, secondBuy, 2, price("50.25"), Counterparty.HOUSE, 8),
                        // refused before it was placed, so there is no order to point at
                        tuple(Type.REJECTED, null, null, null, null, 0));

        assertThat(log.get(4).counterpartyOrderId()).isEqualTo(ask);
        assertThat(log.get(5).counterpartyOrderId()).isEqualTo(firstBuy);
        assertThat(log.get(7).reason()).isEqualTo("cancelled by the account");
        assertThat(log.get(12).reason()).startsWith("insufficient shares");
        assertThat(log.get(12).accountId()).isEqualTo(buyer.id());
        assertThat(log).extracting(OrderEventResponse::seq).isSorted().doesNotHaveDuplicates();
        assertThat(log).extracting(OrderEventResponse::occurredAt).isSorted();
    }

    @Test
    void theLogPagesBySequenceNumberAndFiltersByAccount() throws Exception {
        var seller = createAccount();
        var buyer = createAccount();
        place(seller, "{\"symbol\":\"EVP\",\"side\":\"BUY\",\"quantity\":2}");
        place(buyer, "{\"symbol\":\"EVP\",\"side\":\"BUY\",\"quantity\":3}");

        List<OrderEventResponse> all = events("symbol=EVP");
        long from = all.get(all.size() - 4).seq();
        assertThat(events("symbol=EVP&after=" + from + "&limit=2")).containsExactlyElementsOf(
                all.subList(all.size() - 3, all.size() - 1));

        assertThat(events("accountId=" + buyer.id())).hasSize(2)
                .allSatisfy(event -> assertThat(event.accountId()).isEqualTo(buyer.id()));
        assertThat(get("/api/events?limit=5000")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aRestingOrderThatAQuoteFillsLogsTheFill() throws Exception {
        var buyer = createAccount();
        long bid = place(buyer,
                "{\"symbol\":\"EVQ\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":2,\"limitPrice\":49.90}");
        try {
            // the house now asks 49.7475, inside the bid's limit
            quotes.update(new Quote("EVQ", new BigDecimal("49.50"), Instant.now()));
            assertThat(service.match(buyer.id(), bid)).isEqualTo(OrderStatus.FILLED);
        } finally {
            quotes.update(new Quote("EVQ", new BigDecimal("50.00"), Instant.now()));
        }

        assertThat(events("symbol=EVQ")).extracting(OrderEventResponse::type, OrderEventResponse::orderId,
                OrderEventResponse::fillPrice, OrderEventResponse::counterparty).containsExactly(
                        tuple(Type.PLACED, bid, null, null),
                        tuple(Type.FILLED, bid, price("49.7475"), Counterparty.HOUSE));
    }

    private long place(AccountResponse account, String body) throws Exception {
        var result = post(orders(account), body);
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return read(result, OrderResponse.class).id();
    }

    private List<OrderEventResponse> events(String query) throws Exception {
        var result = get("/api/events?" + query);
        assertThat(result).hasStatus(HttpStatus.OK);
        return readList(result, new TypeReference<List<OrderEventResponse>>() {});
    }

    // prices come back at four decimal places
    private static BigDecimal price(String value) {
        return new BigDecimal(value).setScale(4);
    }
}
