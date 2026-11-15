package io.github.nishantshah0.papertrader.bots;

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

    // the wait until the next wake, drawn from an exponential distribution, so wakes
    // arrive like independent orders do; StrictMath gives the same bits on any machine
    final long nextGapMs() {
        return Math.max(1, Math.round(-meanGapMs * StrictMath.log1p(-timing.nextDouble())));
    }

    // takes this bot's starting shares; runs once, before the clock starts
    abstract void allocate(BotMarket market);

    abstract void act(BotMarket market);
}
