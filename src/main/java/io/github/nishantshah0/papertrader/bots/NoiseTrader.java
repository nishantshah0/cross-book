package io.github.nishantshah0.papertrader.bots;

import io.github.nishantshah0.papertrader.trading.Order;
import io.github.nishantshah0.papertrader.trading.OrderSide;
import java.util.ArrayDeque;
import java.util.Deque;

// A retail-style trader with no view: it buys or sells at random, in random sizes,
// sometimes taking what the book offers and sometimes posting a limit order a little
// away from the middle, and it gives up on old limit orders. It watches the screen
// only (the book and the last trade), never the feed. These are the honest traders a
// detector has to leave alone, and the ones a manipulator profits from.
final class NoiseTrader extends Bot {

    static final double MEAN_GAP_MS = 20_000;
    private static final double TAKE = 0.55;          // how often it crosses the spread
    private static final double GIVE_UP = 0.15;       // how often it cancels an old order instead
    private static final int MAX_OPEN = 3;
    private static final double MAX_OFFSET_BPS = 40;  // how far from the middle its limits go
    private static final double STARTING_STAKE = 0.50; // share of its cash it starts holding as stock

    private final Deque<Long> open = new ArrayDeque<>();

    NoiseTrader(int ordinal, long runSeed) {
        super(BotKind.NOISE, ordinal, runSeed, MEAN_GAP_MS);
    }

    @Override
    void allocate(BotMarket market) {
        double each = market.startingCash.doubleValue() * STARTING_STAKE / market.symbols.size();
        for (String symbol : market.symbols) {
            market.allocate(accountId, symbol, (int) (each / market.feed(symbol)));
        }
    }

    @Override
    void act(BotMarket market) {
        if (!open.isEmpty() && (open.size() >= MAX_OPEN || random.nextDouble() < GIVE_UP)) {
            market.cancel(accountId, open.removeFirst());
            return;
        }
        String symbol = market.symbols.get(random.nextInt(market.symbols.size()));
        OrderSide side = random.nextBoolean() ? OrderSide.BUY : OrderSide.SELL;
        double mid = market.mid(symbol);
        // between 0.1% and 1% of its starting cash
        double notional = market.startingCash.doubleValue() * (0.001 + 0.009 * random.nextDouble());
        int shares = Math.max(1, (int) Math.round(notional / mid));
        boolean take = random.nextDouble() < TAKE;
        double offset = random.nextDouble() * MAX_OFFSET_BPS / 10_000;

        if (side == OrderSide.SELL) {
            // it cannot sell what it has not got, and it does not buy instead: that would
            // tilt the whole market towards buying
            int held = market.shares(accountId, symbol);
            if (held == 0) {
                return;
            }
            shares = Math.min(shares, held);
        }
        boolean otherSide = (side == OrderSide.BUY ? market.bestAsk(symbol) : market.bestBid(symbol)).isPresent();
        if (take && otherSide) {
            market.market(accountId, symbol, side, shares);
            return;
        }
        double price = side == OrderSide.BUY ? mid * (1 - offset) : mid * (1 + offset);
        market.limit(accountId, symbol, side, shares, BotMarket.cents(price, side))
                .filter(Order::isOpen)
                .ifPresent(order -> open.addLast(order.getId()));
    }
}
