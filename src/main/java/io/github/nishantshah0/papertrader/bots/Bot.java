package io.github.nishantshah0.papertrader.bots;

import io.github.nishantshah0.papertrader.trading.OrderSide;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

// One simulated trader. It wakes at random times, does one thing each time, and goes
// back to sleep. When it wakes comes from its own timing stream and what it does from
// its own decision stream, so its schedule never depends on what the market did: in a
// rerun without some other bot, this one still wakes at exactly the same moments.
abstract class Bot {

    final BotKind kind;
    final int ordinal;
    final String name;
    final SplittableRandom random;
    private final SplittableRandom timing;
    private final double meanGapMs;
    long accountId;
    long wakeAt; // epoch millis on the simulated clock
    // the shares it was given in each stock, which also set its position limits
    final Map<String, Integer> stake = new HashMap<>();

    Bot(BotKind kind, int ordinal, long runSeed, double meanGapMs) {
        this.kind = kind;
        this.ordinal = ordinal;
        this.name = "%s-%02d".formatted(kind.prefix, ordinal);
        this.timing = Seeds.stream(runSeed, key(), Seeds.TIMING);
        this.random = Seeds.stream(runSeed, key(), Seeds.DECISIONS);
        this.meanGapMs = meanGapMs;
    }

    // unique per bot and stable across runs; also the tie-break when two bots wake at once
    final long key() {
        return ((long) kind.id << 32) | ordinal;
    }

    // the wait until the next wake; a bot whose pace changes (the spoofer, mid-scheme)
    // overrides this, still drawing from its own timing stream
    long nextGapMs() {
        return gap(meanGapMs);
    }

    // an exponential wait with this mean, so wakes arrive like independent orders do;
    // StrictMath gives the same bits on any machine
    final long gap(double meanMs) {
        return Math.max(1, Math.round(-meanMs * StrictMath.log1p(-timing.nextDouble())));
    }

    // a wait spread evenly between these bounds, for a step that runs to a schedule
    final long gapBetween(long minMs, long maxMs) {
        return minMs + Math.round(timing.nextDouble() * (maxMs - minMs));
    }

    // whether the answer key marks it as a cheater; normally its kind decides
    boolean cheater() {
        return kind.cheater;
    }

    // takes this bot's starting shares; runs once, before the clock starts
    abstract void allocate(BotMarket market);

    // puts this share of its starting cash into each of these stocks, evenly
    final void hold(BotMarket market, List<String> symbols, double share) {
        double each = market.startingCash.doubleValue() * share / symbols.size();
        for (String symbol : symbols) {
            int shares = (int) (each / market.feed(symbol));
            market.allocate(accountId, symbol, shares);
            stake.put(symbol, shares);
        }
    }

    // How many of these shares it may trade, under a limit that is the same both ways:
    // it can sell down to nothing or buy up to twice its stake. Without the buying half,
    // a trader with plenty of cash but a few shares of an expensive stock soon can only
    // buy it, and the price drifts up (a measured run had one stock 5.5% over its feed).
    final int room(BotMarket market, String symbol, OrderSide side, int shares) {
        int held = market.shares(accountId, symbol);
        int limit = side == OrderSide.SELL ? held : 2 * stake.getOrDefault(symbol, 0) - held;
        return Math.max(0, Math.min(shares, limit));
    }

    abstract void act(BotMarket market);
}
