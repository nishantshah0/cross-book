package io.github.nishantshah0.papertrader.bots;

import io.github.nishantshah0.papertrader.trading.OrderSide;
import java.util.List;

// A trader with a view on value: it knows the feed price, and when one of its stocks has
// drifted more than 15 bps away from it, it trades the gap back, selling what is dear
// and buying what is cheap. It stands in for the arbitrageurs and fundamental investors
// who stop a real price wandering off; without them a market of noise and book-chasing
// traders walks away from value once its market makers run out of stock (a measured
// run had a stock end 4.3% above its feed). Like a market maker it follows a few stocks
// and holds a real stake in each, so it has stock to sell when a price runs high.
final class ValueTrader extends Bot {

    static final double MEAN_GAP_MS = 8_000;
    static final double THRESHOLD_BPS = 15;
    private static final double STARTING_STAKE = 0.50; // share of its cash it starts holding as stock

    private final List<String> covers;

    ValueTrader(int ordinal, long runSeed, List<String> covers) {
        super(BotKind.VALUE_TRADER, ordinal, runSeed, MEAN_GAP_MS);
        this.covers = covers;
    }

    @Override
    void allocate(BotMarket market) {
        hold(market, covers, STARTING_STAKE);
    }

    @Override
    void act(BotMarket market) {
        String pick = null;
        double widest = 0;
        for (String symbol : covers) {
            if (market.bestBid(symbol).isEmpty() || market.bestAsk(symbol).isEmpty()) {
                continue;
            }
            double gapBps = (market.mid(symbol) / market.feed(symbol) - 1) * 10_000;
            if (Math.abs(gapBps) > Math.abs(widest)) {
                pick = symbol;
                widest = gapBps;
            }
        }
        if (pick == null || Math.abs(widest) < THRESHOLD_BPS) {
            return;
        }
        OrderSide side = widest > 0 ? OrderSide.SELL : OrderSide.BUY;
        double notional = market.startingCash.doubleValue() * (0.005 + 0.010 * random.nextDouble());
        int shares = Math.max(1, (int) Math.round(notional / market.feed(pick)));
        shares = room(market, pick, side, shares);
        if (shares == 0) {
            return;
        }
        // a limit at the feed price, so it takes the mispriced orders and no further
        market.limit(accountId, pick, side, shares, BotMarket.cents(market.feed(pick),
                side == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY));
    }
}
