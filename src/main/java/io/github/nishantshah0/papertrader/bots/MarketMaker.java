package io.github.nishantshah0.papertrader.bots;

import io.github.nishantshah0.papertrader.trading.Order;
import io.github.nishantshah0.papertrader.trading.OrderSide;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Keeps a bid and an ask in a few symbols, centred on the feed price (it is the one
// bot that knows the "true value", which is what keeps trading anchored to it). Each
// time it wakes it re-quotes one of its symbols: it pulls its old quotes and posts new
// ones, shifted against its inventory by up to two half-spreads, so a maker that has
// sold a lot quotes higher and buys back. Its spread is its own, drawn once, between 3 and 10 bps either side.
final class MarketMaker extends Bot {

    static final double MEAN_GAP_MS = 4_000;
    private static final double STARTING_STAKE = 0.40; // share of its cash it starts holding as stock
    private static final double QUOTE = 0.04;          // each quote's size, as a share of its cash
    private static final double SKEW = 2.0;            // most it shifts its quotes, in half-spreads

    private final List<String> covers;
    private final double halfSpreadBps;
    private final Map<String, Integer> target = new HashMap<>();
    private final Map<String, Long> bids = new HashMap<>();
    private final Map<String, Long> asks = new HashMap<>();
    private int next;

    MarketMaker(int ordinal, long runSeed, List<String> covers) {
        super(BotKind.MARKET_MAKER, ordinal, runSeed, MEAN_GAP_MS);
        this.covers = covers;
        this.halfSpreadBps = 3 + 7 * random.nextDouble();
        this.next = random.nextInt(covers.size());
    }

    List<String> covers() {
        return covers;
    }

    @Override
    void allocate(BotMarket market) {
        double each = market.startingCash.doubleValue() * STARTING_STAKE / covers.size();
        for (String symbol : covers) {
            int shares = (int) (each / market.feed(symbol));
            market.allocate(accountId, symbol, shares);
            target.put(symbol, Math.max(1, shares));
        }
    }

    @Override
    void act(BotMarket market) {
        String symbol = covers.get(next);
        next = (next + 1) % covers.size();
        pull(market, bids.remove(symbol));
        pull(market, asks.remove(symbol));

        int held = market.shares(accountId, symbol);
        int goal = target.get(symbol);
        double fair = market.feed(symbol);
        double inventory = Math.max(-1, Math.min(1, (double) (held - goal) / goal));
        double skew = -SKEW * halfSpreadBps * inventory;
        int size = Math.max(1, (int) (market.startingCash.doubleValue() * QUOTE / fair));

        var bid = BotMarket.cents(fair * (1 + (skew - halfSpreadBps) / 10_000), OrderSide.BUY);
        market.limit(accountId, symbol, OrderSide.BUY, size, bid)
                .filter(Order::isOpen).ifPresent(order -> bids.put(symbol, order.getId()));
        int offered = Math.min(size, held);
        if (offered > 0) {
            var ask = BotMarket.cents(fair * (1 + (skew + halfSpreadBps) / 10_000), OrderSide.SELL);
            market.limit(accountId, symbol, OrderSide.SELL, offered, ask)
                    .filter(Order::isOpen).ifPresent(order -> asks.put(symbol, order.getId()));
        }
    }

    private void pull(BotMarket market, Long orderId) {
        if (orderId != null) {
            market.cancel(accountId, orderId);
        }
    }
}
