package io.github.nishantshah0.papertrader.bots;

import io.github.nishantshah0.papertrader.trading.Order;
import io.github.nishantshah0.papertrader.trading.OrderSide;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

// A layerer modelled on Navinder Sarao's "layering algorithm" (CFTC complaint, April
// 2015). As the CFTC described it, it placed four to six exceptionally large sell orders
// in the visible book, kept them at least three or four price levels from the best ask,
// repriced them as the market moved so they stayed visible but avoided execution, and
// was switched on and off several times a day to create order book imbalances.
// https://www.cftc.gov/PressRoom/PressReleases/7156-15
//
// Here it switches on and off, each spell lasting about five minutes on average. While
// it is on, every one to three seconds it:
//   - keeps `layers` large orders (10% of its cash each) on one side, 3, 4, 5... cents
//     behind the best price, replacing any that the market has moved two cents or more
//     from where it should be;
//   - keeps one small real order (2% of cash) a cent inside the spread on the other
//     side, replacing it if it hasn't filled within six seconds.
// When it switches off it cancels everything. Its fakes can live for minutes and are
// cancelled and replaced as the price moves, unlike the built-in spoofer's.
//
// Written after the detectors were frozen (git tag detectors-frozen). With zero layers
// it is the honest twin: same timing and real orders, nothing fake.
final class SaraoLayerer extends Bot {

    private static final double STARTING_STAKE = 0.50;
    private static final double FAKE_SIZE = 0.10, REAL_SIZE = 0.02;
    private static final int AWAY_CENTS = 3, DRIFT_CENTS = 2;
    private static final double MEAN_ON_MS = 300_000, MEAN_OFF_MS = 300_000;
    private static final long REAL_PATIENCE_MS = 6_000;

    private final int layers;
    private final List<String> works = new ArrayList<>(2);
    private final Long[] fakes;
    private final BigDecimal[] fakeAt;
    private final int[] fakeShares;
    private boolean on;
    private long switchAt = -1;
    private String symbol;
    private OrderSide fakeSide;
    private Long real;
    private long realSince;
    private int episode;

    SaraoLayerer(BotKind kind, int ordinal, long runSeed, int layers) {
        super(kind, ordinal, runSeed, MEAN_OFF_MS);
        this.layers = layers;
        this.fakes = new Long[layers];
        this.fakeAt = new BigDecimal[layers];
        this.fakeShares = new int[layers];
    }

    @Override
    boolean cheater() {
        return layers > 0;
    }

    @Override
    long nextGapMs() {
        return on ? gapBetween(1_000, 3_000) : Math.max(1, switchAt - wakeAt);
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
        long now = wakeAt;
        if (switchAt < 0) { // first wake: start off, for a while
            switchAt = now + gap(MEAN_OFF_MS);
            return;
        }
        if (now >= switchAt) {
            if (on) {
                stop(market);
                switchAt = now + gap(MEAN_OFF_MS);
            } else {
                start(market);
                switchAt = now + gap(MEAN_ON_MS);
            }
            return;
        }
        if (on && market.bestBid(symbol).isPresent() && market.bestAsk(symbol).isPresent()) {
            reprice(market);
            keepReal(market, now);
        }
    }

    private void start(BotMarket market) {
        symbol = works.get(random.nextInt(works.size()));
        fakeSide = market.shares(accountId, symbol) > stake.get(symbol) ? OrderSide.BUY : OrderSide.SELL;
        episode++;
        on = true;
    }

    private void stop(BotMarket market) {
        for (int i = 0; i < layers; i++) {
            if (fakes[i] != null) {
                market.cancel(accountId, fakes[i]);
                fakes[i] = null;
            }
        }
        if (real != null) {
            market.cancel(accountId, real);
            real = null;
        }
        on = false;
    }

    // each fake belongs AWAY_CENTS + i behind the best price on its side
    private void reprice(BotMarket market) {
        BigDecimal touch = (fakeSide == OrderSide.BUY ? market.bestBid(symbol) : market.bestAsk(symbol)).orElseThrow();
        int size = Math.max(1, (int) (market.startingCash.doubleValue() * FAKE_SIZE / touch.doubleValue()));
        for (int i = 0; i < layers; i++) {
            BigDecimal away = BigDecimal.valueOf(AWAY_CENTS + i, 2);
            BigDecimal target = fakeSide == OrderSide.BUY ? touch.subtract(away) : touch.add(away);
            if (fakes[i] != null && fakeAt[i].subtract(target).abs().compareTo(BigDecimal.valueOf(DRIFT_CENTS, 2)) < 0) {
                continue; // close enough to where it belongs
            }
            if (fakes[i] != null) {
                market.cancel(accountId, fakes[i]);
                fakes[i] = null;
            }
            int shares = size;
            if (fakeSide == OrderSide.SELL) { // fake asks need shares behind them
                int resting = 0;
                for (int j = 0; j < layers; j++) {
                    resting += fakes[j] == null ? 0 : fakeShares[j];
                }
                shares = Math.min(size, market.shares(accountId, symbol) - resting);
            }
            if (shares <= 0 || target.signum() <= 0) {
                continue;
            }
            int slot = i;
            int placed = shares;
            market.limit(accountId, symbol, fakeSide, shares, target).filter(Order::isOpen).ifPresent(order -> {
                fakes[slot] = order.getId();
                fakeAt[slot] = target;
                fakeShares[slot] = placed;
                market.label(this, order, episode, "SPOOF");
            });
        }
    }

    private void keepReal(BotMarket market, long now) {
        if (real != null && now - realSince < REAL_PATIENCE_MS) {
            return;
        }
        if (real != null) {
            market.cancel(accountId, real);
            real = null;
        }
        BigDecimal bid = market.bestBid(symbol).orElseThrow(), ask = market.bestAsk(symbol).orElseThrow();
        OrderSide realSide = fakeSide == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
        BigDecimal one = BigDecimal.valueOf(1, 2);
        BigDecimal price = realSide == OrderSide.SELL ? ask.subtract(one).max(bid.add(one))
                : bid.add(one).min(ask.subtract(one));
        int shares = Math.max(1, (int) (market.startingCash.doubleValue() * REAL_SIZE / price.doubleValue()));
        if (realSide == OrderSide.SELL) {
            shares = Math.min(shares, market.shares(accountId, symbol));
        }
        if (shares <= 0) {
            return;
        }
        market.limit(accountId, symbol, realSide, shares, price).ifPresent(order -> {
            market.label(this, order, episode, "REAL");
            if (order.isOpen()) {
                real = order.getId();
                realSince = now;
            }
        });
    }
}
