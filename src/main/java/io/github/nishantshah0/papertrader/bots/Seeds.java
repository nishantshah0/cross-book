package io.github.nishantshah0.papertrader.bots;

import java.util.SplittableRandom;

// Every random number in a bot run comes from a stream seeded by the run's seed plus
// a fixed label, never from a stream shared between bots. So adding, removing or
// reordering one bot leaves every other bot's numbers exactly as they were: the same
// market can be run again without the cheater and the difference is the cheater's.
final class Seeds {

    // what a stream is for; each bot has one of each
    static final long SETUP = 0, TIMING = 1, DECISIONS = 2;

    private Seeds() {
    }

    static SplittableRandom stream(long runSeed, long botKey, long purpose) {
        return new SplittableRandom(mix(mix(mix(runSeed) ^ botKey) ^ purpose));
    }

    // SplitMix64's finalizer: a fixed, well-spread hash, the same on every JVM
    static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
