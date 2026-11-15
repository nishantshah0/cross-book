package io.github.nishantshah0.papertrader.bots;

import io.github.nishantshah0.papertrader.trading.Order;
import io.github.nishantshah0.papertrader.trading.OrderSide;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

// A cheater that layers: it fills one side of a book with large orders it never means
// to trade, so the book looks lopsided, posts a small real order on the other side,
// waits for traders who read the book to hit it, then pulls everything. One scheme
// (an episode) runs in three quick wakes:
//
//   1. layer:   large orders (four by default) stacked behind the best price on the fake side
//   2. execute: a small real order on the other side, one cent inside the spread
//   3. unwind:  cancel the fakes, and the real order too if it is still open
//
// It then rests for a while before the next episode. To sell, it fakes demand (bids);
// to buy back, it fakes supply (asks), choosing by whether it holds more or less than
// it started with, so it does not just run out of stock. It works two stocks, chosen
// by its seed, and every order it places is labelled in the answer key.
//
// With zero layers it is the control: the same bot, same timing, same real orders, but
// nothing fake. That one is honest, so the answer key says so, and it is the hardest
// kind of innocent account for a detector to clear. Measured over three seeds, the
// fakes doubled how often the real order filled and turned a small loss into a profit.
final class Spoofer extends Bot {

    static final double REST_GAP_MS = 40_000;
    // between the steps of a scheme, so each fake order lives 2 to 6 seconds
    static final long STEP_MIN_MS = 1_000, STEP_MAX_MS = 3_000;
    static final int DEFAULT_LAYERS = 4;
    private static final double LAYER_SIZE = 0.08;     // each fake order, as a share of its cash
    private static final double REAL_SIZE = 0.02;      // the real order
    private static final double STARTING_STAKE = 0.50; // share of its cash it starts holding as stock

    private enum Step { REST, LAYERED, EXECUTING }

    private final int layers;
    private final List<String> works = new ArrayList<>(2);
    private final List<Long> fakes = new ArrayList<>();
    private Step step = Step.REST;
    private OrderSide fakeSide;
    private String symbol;
    private Long real;
    private int episode;

    Spoofer(int ordinal, long runSeed, int layers) {
        super(BotKind.SPOOFER, ordinal, runSeed, REST_GAP_MS);
        this.layers = layers;
    }

    @Override
    boolean cheater() {
        return layers > 0;
    }

    @Override
    long nextGapMs() {
        return step == Step.REST ? gap(REST_GAP_MS) : gapBetween(STEP_MIN_MS, STEP_MAX_MS);
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
            case REST -> layer(market);
            case LAYERED -> execute(market);
            case EXECUTING -> unwind(market);
        }
    }

    private void layer(BotMarket market) {
        symbol = works.get(random.nextInt(works.size()));
        var bid = market.bestBid(symbol);
        var ask = market.bestAsk(symbol);
        if (bid.isEmpty() || ask.isEmpty()) {
            return; // no book to play with; try again after a rest
        }
        int held = market.shares(accountId, symbol);
        fakeSide = held > stake.get(symbol) ? OrderSide.BUY : OrderSide.SELL;
        BigDecimal touch = fakeSide == OrderSide.BUY ? bid.get() : ask.get();
        double price = touch.doubleValue();
        int size = Math.max(1, (int) (market.startingCash.doubleValue() * LAYER_SIZE / price));
        episode++;
        int free = held; // fake asks need shares behind them, like any sell
        for (int i = 1; i <= layers; i++) {
            // a cent apart, starting a cent behind the best price, so they sit near the
            // top of the book but are not the first thing a seller would hit
            BigDecimal at = fakeSide == OrderSide.BUY ? touch.subtract(cents(i)) : touch.add(cents(i));
            int shares = fakeSide == OrderSide.SELL ? Math.min(size, free) : size;
            if (shares <= 0 || at.signum() <= 0) {
                break;
            }
            if (fakeSide == OrderSide.SELL) {
                free -= shares;
            }
            market.limit(accountId, symbol, fakeSide, shares, at).filter(Order::isOpen).ifPresent(order -> {
                fakes.add(order.getId());
                market.label(order, episode, "SPOOF");
            });
        }
        step = fakes.isEmpty() && layers > 0 ? Step.REST : Step.LAYERED;
    }

    private void execute(BotMarket market) {
        var bid = market.bestBid(symbol);
        var ask = market.bestAsk(symbol);
        if (bid.isPresent() && ask.isPresent()) {
            OrderSide realSide = fakeSide == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
            BigDecimal one = cents(1);
            // one cent inside the spread, so it is first in line, without crossing it
            BigDecimal price = realSide == OrderSide.SELL
                    ? ask.get().subtract(one).max(bid.get().add(one))
                    : bid.get().add(one).min(ask.get().subtract(one));
            int shares = Math.max(1, (int) (market.startingCash.doubleValue() * REAL_SIZE / price.doubleValue()));
            if (realSide == OrderSide.SELL) {
                shares = Math.min(shares, market.shares(accountId, symbol));
            }
            if (shares > 0) {
                market.limit(accountId, symbol, realSide, shares, price).ifPresent(order -> {
                    market.label(order, episode, "REAL");
                    if (order.isOpen()) {
                        real = order.getId();
                    }
                });
            }
        }
        step = Step.EXECUTING;
    }

    private void unwind(BotMarket market) {
        for (Long fake : fakes) {
            market.cancel(accountId, fake);
        }
        fakes.clear();
        if (real != null) {
            market.cancel(accountId, real);
            real = null;
        }
        step = Step.REST;
    }

    private static BigDecimal cents(int n) {
        return BigDecimal.valueOf(n, 2);
    }
}
