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
// SpoofTactics changes the sizes, prices and timing, and can swap steps 1 and 2.
//
// It then rests for a while before the next episode. To sell, it fakes demand (bids);
// to buy back, it fakes supply (asks), choosing by whether it holds more or less than
// it started with, so it does not just run out of stock. It works two stocks, chosen
// by its seed, and every order it places is labelled in the answer key.
//
// With zero layers it is the control: the same bot, same timing, same real orders, but
// nothing fake. That one is honest, so the answer key says so, and it is the hardest
// kind of innocent account for a detector to clear. Every market also has lookalikes,
// controls that trade alongside the spoofers, so even a spoofer's starting stake (two
// stocks, a quarter of its cash in each) does not give it away.
final class Spoofer extends Bot {

    static final int DEFAULT_LAYERS = 4;
    private static final double STARTING_STAKE = 0.50; // share of its cash it starts holding as stock

    // REST, then the scheme's two placing steps (fakes then real, or real then fakes), then unwind
    private enum Step { REST, FIRST, SECOND }

    private final int layers;
    private final SpoofTactics tactics;
    private final List<String> works = new ArrayList<>(2);
    private final List<Long> fakes = new ArrayList<>();
    private Step step = Step.REST;
    private OrderSide fakeSide;
    private String symbol;
    private Long real;
    private int episode;

    Spoofer(int ordinal, long runSeed, int layers) {
        this(ordinal, runSeed, layers, SpoofTactics.DEFAULT);
    }

    Spoofer(int ordinal, long runSeed, int layers, SpoofTactics tactics) {
        this(BotKind.SPOOFER, ordinal, runSeed, layers, tactics);
    }

    // a lookalike: the spoofer's twin with nothing fake
    static Spoofer lookalike(int ordinal, long runSeed) {
        return lookalike(ordinal, runSeed, SpoofTactics.DEFAULT);
    }

    static Spoofer lookalike(int ordinal, long runSeed, SpoofTactics tactics) {
        return new Spoofer(BotKind.LOOKALIKE, ordinal, runSeed, 0, tactics);
    }

    private Spoofer(BotKind kind, int ordinal, long runSeed, int layers, SpoofTactics tactics) {
        super(kind, ordinal, runSeed, tactics.withDefaults().restMs());
        this.layers = layers;
        this.tactics = tactics.withDefaults();
    }

    @Override
    boolean cheater() {
        return layers > 0;
    }

    @Override
    long nextGapMs() {
        return step == Step.REST ? gap(tactics.restMs()) : gapBetween(tactics.stepMinMs(), tactics.stepMaxMs());
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
            case FIRST -> {
                if (tactics.realFirst()) {
                    layer(market);
                } else {
                    execute(market);
                }
                step = Step.SECOND;
            }
            case SECOND -> unwind(market);
        }
    }

    // picks the stock and the side, then takes the scheme's first step
    private void begin(BotMarket market) {
        symbol = works.get(random.nextInt(works.size()));
        if (market.bestBid(symbol).isEmpty() || market.bestAsk(symbol).isEmpty()) {
            return; // no book to play with; try again after a rest
        }
        int held = market.shares(accountId, symbol);
        fakeSide = held > stake.get(symbol) ? OrderSide.BUY : OrderSide.SELL;
        episode++;
        if (tactics.realFirst()) {
            execute(market);
            step = Step.FIRST;
        } else {
            layer(market);
            // a spoofer whose fakes all failed has nothing to play with; the control has none to begin with
            step = fakes.isEmpty() && layers > 0 ? Step.REST : Step.FIRST;
        }
    }

    private void layer(BotMarket market) {
        var bid = market.bestBid(symbol);
        var ask = market.bestAsk(symbol);
        if (bid.isEmpty() || ask.isEmpty()) {
            return;
        }
        int held = market.shares(accountId, symbol);
        BigDecimal touch = fakeSide == OrderSide.BUY ? bid.get() : ask.get();
        double price = touch.doubleValue();
        int size = Math.max(1, (int) (market.startingCash.doubleValue() * tactics.fakeSize() / price));
        int free = held; // fake asks need shares behind them, like any sell
        for (int i = 0; i < layers; i++) {
            // by default a cent apart, starting a cent behind the best price, so they sit
            // near the top of the book but are not the first thing a seller would hit
            BigDecimal away = cents(tactics.fakeOffsetCents() + i * tactics.fakeGapCents());
            BigDecimal at = fakeSide == OrderSide.BUY ? touch.subtract(away) : touch.add(away);
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
            int shares = Math.max(1, (int) (market.startingCash.doubleValue() * tactics.realSize() / price.doubleValue()));
            if (realSide == OrderSide.SELL) {
                shares = Math.min(shares, market.shares(accountId, symbol));
            }
            if (shares > 0) {
                market.limit(accountId, symbol, realSide, shares, price).ifPresent(order -> {
                    market.label(this, order, episode, "REAL");
                    if (order.isOpen()) {
                        real = order.getId();
                    }
                });
            }
        }
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
