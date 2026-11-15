package io.github.nishantshah0.papertrader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.nishantshah0.papertrader.account.AccountResponse;
import io.github.nishantshah0.papertrader.quote.QuoteCache;
import io.github.nishantshah0.papertrader.trading.MatchingEngine;
import io.github.nishantshah0.papertrader.trading.Order;
import io.github.nishantshah0.papertrader.trading.OrderService;
import io.github.nishantshah0.papertrader.trading.OrderSide;
import io.github.nishantshah0.papertrader.trading.OrderStatus;
import io.github.nishantshah0.papertrader.trading.OrderType;
import io.github.nishantshah0.papertrader.trading.PlaceOrderRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

// Every symbol is quoted at 100.00 with a 10 bps spread, so the house bids 99.95 and
// asks 100.05. Net buying of 100 shares leans the house 20 bps, and a fill pays the
// average lean along the way; half the volume fades on each quote tick; the book can
// lean the house up to 4 bps, scaled by how big the imbalance is next to those 100
// shares of depth. Each test has
// its own symbol, because the lean a test leaves behind would change the next one's prices.
@SpringBootTest(properties = {
        "papertrader.feed.mode=static",
        "papertrader.house.spread-bps=10",
        "papertrader.house.impact-bps=20",
        "papertrader.house.depth-shares=100",
        "papertrader.house.impact-decay=0.5",
        "papertrader.house.imbalance-bps=4",
        "papertrader.starting-cash=100000.00",
        "papertrader.seed-quotes.IMA=100.00",
        "papertrader.seed-quotes.IMB=100.00",
        "papertrader.seed-quotes.IMC=100.00",
        "papertrader.seed-quotes.IMD=100.00",
        "papertrader.seed-quotes.IME=100.00",
        "papertrader.seed-quotes.IMF=100.00",
        "papertrader.seed-quotes.IMG=100.00"
})
class PriceImpactApiTest extends ApiTestSupport {

    @Autowired
    OrderService service;

    @Autowired
    MatchingEngine engine;

    @Autowired
    QuoteCache quotes;

    @Test
    void aBigBuyPaysForItsSizeAndMovesThePriceForTheNextBuyer() throws Exception {
        var first = createAccount();
        var second = createAccount();

        Order big = service.place(first.id(), market(OrderSide.BUY, "IMA", 100));

        // the average lean over the first 100 shares is two thirds of 20 bps:
        // 100 * (1 + 13.33 bps) * (1 + 5 bps)
        assertThat(big.getAvgFillPrice().doubleValue()).isCloseTo(100.1834, within(0.0001));
        assertThat(engine.leanBps("IMA")).isCloseTo(20.0, within(1e-9));
        // the house now quotes around 100.20: 100.20 * (1 +/- 5 bps)
        var house = engine.book("IMA", 5).house();
        assertThat(house.bid()).isEqualByComparingTo("100.1499");
        assertThat(house.ask()).isEqualByComparingTo("100.2501");

        // the terminal asks the same question before you order
        var quote = get("/api/book/IMA/house-price?side=BUY&shares=1");
        assertThat(quote).hasStatus(HttpStatus.OK);
        assertThat(quote).bodyJson().extractingPath("$.price").asNumber().satisfies(
                price -> assertThat(price.doubleValue()).isCloseTo(100.2506, within(0.0001)));
        assertThat(get("/api/book/IMA/house-price?side=BUY&shares=0"))
                .hasStatus(HttpStatus.BAD_REQUEST);

        Order next = service.place(second.id(), market(OrderSide.BUY, "IMA", 1));
        // the 101st share pays the lean between 100 and 101 shares: about 20.05 bps
        assertThat(next.getAvgFillPrice().doubleValue()).isCloseTo(100.2506, within(0.0001));
    }

    @Test
    void aLimitBuyTakesOnlyTheSharesItsPriceAllowsAndRestsTheRest() throws Exception {
        var buyer = createAccount();

        Order bid = service.place(buyer.id(), limit(OrderSide.BUY, "IMB", 400, "100.10"));

        // 14 shares cost just under 100.10 each; a 15th would push the price past the limit
        assertThat(bid.getStatus()).isEqualTo(OrderStatus.OPEN);
        assertThat(bid.getFilledQuantity()).isEqualTo(14);
        assertThat(bid.getAvgFillPrice()).isLessThanOrEqualTo(new BigDecimal("100.10"));
        assertThat(portfolio(buyer).reservedCash()).isEqualByComparingTo("38638.60"); // 386 at 100.10
        assertThat(engine.restingOrders("IMB")).isEqualTo(1);
    }

    @Test
    void theMoveFadesOnEveryQuoteTick() throws Exception {
        var buyer = createAccount();
        service.place(buyer.id(), market(OrderSide.BUY, "IMC", 100));
        assertThat(engine.leanBps("IMC")).isCloseTo(20.0, within(1e-9));

        tick("IMC"); // 50 shares of volume left: 20 * sqrt(0.5)
        assertThat(engine.leanBps("IMC")).isCloseTo(20 * Math.sqrt(0.5), within(1e-9));
        tick("IMC"); // 25 left
        assertThat(engine.leanBps("IMC")).isCloseTo(10.0, within(1e-9));
    }

    // Spoofing, the reason the book matters: a large bid the trader never means to fill
    // leans the house's quotes up, the trader sells into the higher bid, then cancels.
    // The same sale without the fake bid gets a worse price.
    @Test
    void aBidThatIsNeverMeantToFillLiftsThePriceTheSpooferSellsAt() throws Exception {
        var honest = holder("IMD");
        var spoofer = holder("IME");

        Order plain = service.place(honest.id(), market(OrderSide.SELL, "IMD", 20));

        Order fake = service.place(spoofer.id(), limit(OrderSide.BUY, "IME", 900, "99.90"));
        // 4 bps * 900 / (900 + 100 depth)
        assertThat(engine.leanBps("IME")).isCloseTo(3.6, within(0.01));
        Order spoofed = service.place(spoofer.id(), market(OrderSide.SELL, "IME", 20));
        service.cancel(spoofer.id(), fake.getId());

        assertThat(fake.getFilledQuantity()).isZero();
        assertThat(spoofed.getAvgFillPrice()).isGreaterThan(plain.getAvgFillPrice());
        // 3.6 bps on a 100 dollar stock
        assertThat(spoofed.getAvgFillPrice().subtract(plain.getAvgFillPrice()).doubleValue())
                .isCloseTo(0.036, within(0.001));
        assertThat(engine.leanBps("IME")).isLessThan(0.01); // and the lean leaves with the bid
    }

    // The same trick with a token order does next to nothing: one share is noise next to
    // the house's depth, so spoofing has to put real size at risk.
    @Test
    void aOneShareBidBarelyMovesThePrice() throws Exception {
        var trader = createAccount();
        var before = engine.book("IMF", 5).house();

        service.place(trader.id(), limit(OrderSide.BUY, "IMF", 1, "99.90"));

        assertThat(engine.leanBps("IMF")).isCloseTo(4.0 / 101, within(1e-6));
        var after = engine.book("IMF", 5).house();
        assertThat(after.bid().subtract(before.bid()).doubleValue()).isLessThan(0.001);
    }

    // The reviewer's second finding: a sell resting exactly at the house's bid used to be
    // filled in full at the bid by the sweep after it, skipping the size cost. On the
    // curve, not one share fits at the touch, so it stays in the book until the price
    // really reaches it.
    @Test
    void anOrderRestingAtTheHousesTouchGetsNoFreeFill() throws Exception {
        var holder = holder("IMG");
        var bid = engine.book("IMG", 1).house().bid();

        Order sell = service.place(holder.id(), limit(OrderSide.SELL, "IMG", 50, bid.toPlainString()));
        tick("IMG");

        assertThat(service.get(holder.id(), sell.getId()).getFilledQuantity()).isZero();
        assertThat(engine.restingOrders("IMG")).isEqualTo(1);
    }

    // an account holding 50 shares, with the price settled back where it started
    private AccountResponse holder(String symbol) throws Exception {
        var account = createAccount();
        service.place(account.id(), market(OrderSide.BUY, symbol, 50));
        for (int i = 0; i < 40; i++) {
            tick(symbol);
        }
        assertThat(engine.leanBps(symbol)).isCloseTo(0.0, within(1e-3));
        return account;
    }

    // one quote tick, waited for, so the snapshot the test reads afterwards includes it
    private void tick(String symbol) {
        engine.tick(symbol);
    }

    private static PlaceOrderRequest market(OrderSide side, String symbol, int quantity) {
        return new PlaceOrderRequest(symbol, side, null, quantity, null);
    }

    private static PlaceOrderRequest limit(OrderSide side, String symbol, int quantity, String price) {
        return new PlaceOrderRequest(symbol, side, OrderType.LIMIT, quantity, new BigDecimal(price));
    }
}
