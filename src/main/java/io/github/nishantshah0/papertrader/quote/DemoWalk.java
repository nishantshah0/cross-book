package io.github.nishantshah0.papertrader.quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

// The demo feed's prices: a random walk that is pulled gently back towards each
// symbol's seed price, so it wanders like a real quote but never drifts far from it.
// Each step moves about 0.15%, the pull is 2% of the gap, and the price stays within
// 6% of the seed.
final class DemoWalk {

    private static final double STEP = 0.0015;
    private static final double PULL = 0.02;
    private static final double BAND = 0.06;

    private final Random random;
    private final Map<String, Double> last = new HashMap<>();

    DemoWalk(Random random) {
        this.random = random;
    }

    BigDecimal next(String symbol, BigDecimal seed) {
        double anchor = seed.doubleValue();
        double previous = last.getOrDefault(symbol, anchor);
        double moved = previous * (1 + random.nextGaussian() * STEP) + (anchor - previous) * PULL;
        double price = Math.max(anchor * (1 - BAND), Math.min(anchor * (1 + BAND), moved));
        last.put(symbol, price);
        return BigDecimal.valueOf(price).setScale(4, RoundingMode.HALF_UP);
    }
}
