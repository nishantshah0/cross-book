package io.github.nishantshah0.papertrader.bots;

import io.github.nishantshah0.papertrader.trading.Order;
import io.github.nishantshah0.papertrader.trading.OrderSide;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

// A spoofer modelled on the first Dodd-Frank spoofing case, Panther Energy Trading and
// Michael Coscia (CFTC, July 2013). As the CFTC described it, the algorithm placed a
// small order it wanted filled, then quickly placed several large orders on the other
// side at successive prices, and cancelled them; "these transactions occurred in
// milliseconds." https://www.cftc.gov/PressRoom/PressReleases/6649-13
//
// Here a scheme is: a small real order a cent inside the spread; 20 to 80 ms later,
// `layers` large orders on the other side, at the best price and a cent apart behind it;
// 100 to 500 ms later, everything still open is cancelled. Then it rests about 40
// seconds. Sizes match the built-in spoofer (8% of cash per fake, 2% for the real one),
// so the difference is the pattern, not the size.
//
// Written after the detectors were frozen (git tag detectors-frozen), so none of them was
// built with it in view. With zero layers it is the honest twin: same timing, same real
// orders, nothing fake.
final class CosciaSpoofer extends Bot {

    private static final double STARTING_STAKE = 0.50;
    private static final double FAKE_SIZE = 0.08, REAL_SIZE = 0.02;
    private static final double MEAN_REST_MS = 40_000;

    private enum Step { REST, LAYER, UNWIND }

    private final int layers;
    private final List<String> works = new ArrayList<>(2);
    private final List<Long> fakes = new ArrayList<>();
    private Step step = Step.REST;
    private OrderSide fakeSide;
    private String symbol;
    private Long real;
    private int episode;

    CosciaSpoofer(BotKind kind, int ordinal, long runSeed, int layers) {
        super(kind, ordinal, runSeed, MEAN_REST_MS);
        this.layers = layers;
    }

    @Override
    boolean cheater() {
        return layers > 0;
    }

    @Override
    long nextGapMs() {
        return switch (step) {
            case REST -> gap(MEAN_REST_MS);
            case LAYER -> gapBetween(20, 80);
            case UNWIND -> gapBetween(100, 500);
        };
    }

    @Override
    void allocate(BotMarket market) {
        List<String> pool = new ArrayList<>(market.symbols);
        while (works.size() < Math.min(2, market.symbols.size())) {
            works.add(pool.remove(random.nextInt(pool.size())));
        }
        hold(market, works, STARTING_STAKE);
    }

    @Override
    void act(BotMarket market) {
        switch (step) {
            case REST -> begin(market);
            case LAYER -> {
                layer(market);
                step = Step.UNWIND;
            }
            case UNWIND -> unwind(market);
        }
    }

    // the small order it wants filled comes first
    private void begin(BotMarket market) {
        symbol = works.get(random.nextInt(works.size()));
        var bid = market.bestBid(symbol);
        var ask = market.bestAsk(symbol);
        if (bid.isEmpty() || ask.isEmpty()) {
            return;
        }
        fakeSide = market.shares(accountId, symbol) > stake.get(symbol) ? OrderSide.BUY : OrderSide.SELL;
        OrderSide realSide = fakeSide == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
        episode++;
        BigDecimal one = BigDecimal.valueOf(1, 2);
        BigDecimal price = realSide == OrderSide.SELL
                ? ask.get().subtract(one).max(bid.get().add(one))
                : bid.get().add(one).min(ask.get().subtract(one));
        int shares = Math.max(1, (int) (market.startingCash.doubleValue() * REAL_SIZE / price.doubleValue()));
        if (realSide == OrderSide.SELL) {
            shares = Math.min(shares, market.shares(accountId, symbol));
        }
        if (shares > 0) {
            market.limit(accountId, symbol, realSide, shares, price).ifPresent(order -> {
                market.label(this, order, episode, "REAL");
                real = order.isOpen() ? order.getId() : null;
            });
        }
        step = Step.LAYER;
    }

    // then the large ones on the other side, from the best price back, a cent apart
    private void layer(BotMarket market) {
        var touch = fakeSide == OrderSide.BUY ? market.bestBid(symbol) : market.bestAsk(symbol);
        if (touch.isEmpty()) {
            return;
        }
        int size = Math.max(1, (int) (market.startingCash.doubleValue() * FAKE_SIZE / touch.get().doubleValue()));
        int free = market.shares(accountId, symbol);
        for (int i = 0; i < layers; i++) {
            BigDecimal away = BigDecimal.valueOf(i, 2);
            BigDecimal at = fakeSide == OrderSide.BUY ? touch.get().subtract(away) : touch.get().add(away);
            int shares = fakeSide == OrderSide.SELL ? Math.min(size, free) : size;
            if (shares <= 0 || at.signum() <= 0) {
                break;
            }
            if (fakeSide == OrderSide.SELL) {
                free -= shares;
            }
            market.limit(accountId, symbol, fakeSide, shares, at).filter(Order::isOpen).ifPresent(order -> {
                fakes.add(order.getId());
                market.label(this, order, episode, "SPOOF");
            });
        }
    }

    private void unwind(BotMarket market) {
        fakes.forEach(id -> market.cancel(accountId, id));
        fakes.clear();
        if (real != null) {
            market.cancel(accountId, real);
            real = null;
        }
        step = Step.REST;
    }
}
