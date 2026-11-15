package io.github.nishantshah0.papertrader.bots;

import io.github.nishantshah0.papertrader.trading.OrderSide;

// A short-term trader that reads the order book. Each time it wakes it scans every
// stock's top five levels, and if the most lopsided book is lopsided enough, it trades
// that way at market: lots of resting bids means buyers are waiting, so the price
// should rise. Real traders and algorithms use this signal, and it is honest. It is
// also exactly what a spoofer's fake orders are built to trigger.
final class BookReader extends Bot {

    static final double MEAN_GAP_MS = 10_000;
    static final int LEVELS = 5;
    static final double THRESHOLD = 0.4;               // how lopsided a book has to be
    private static final double STARTING_STAKE = 0.50; // share of its cash it starts holding as stock

    BookReader(int ordinal, long runSeed) {
        super(BotKind.BOOK_READER, ordinal, runSeed, MEAN_GAP_MS);
    }

    @Override
    void allocate(BotMarket market) {
        hold(market, market.symbols, STARTING_STAKE);
    }

    @Override
    void act(BotMarket market) {
        String pick = null;
        double strongest = 0;
        for (String symbol : market.symbols) { // in symbol order, so ties go the same way every run
            double imbalance = market.imbalance(symbol, LEVELS);
            if (Math.abs(imbalance) > Math.abs(strongest)) {
                pick = symbol;
                strongest = imbalance;
            }
        }
        if (pick == null || Math.abs(strongest) < THRESHOLD) {
            return; // nothing worth trading on
        }
        OrderSide side = strongest > 0 ? OrderSide.BUY : OrderSide.SELL;
        if ((side == OrderSide.BUY ? market.bestAsk(pick) : market.bestBid(pick)).isEmpty()) {
            return;
        }
        double notional = market.startingCash.doubleValue() * (0.002 + 0.008 * random.nextDouble());
        int shares = Math.max(1, (int) Math.round(notional / market.mid(pick)));
        shares = room(market, pick, side, shares);
        if (shares == 0) {
            return;
        }
        market.market(accountId, pick, side, shares);
    }
}
