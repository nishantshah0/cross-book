package io.github.nishantshah0.papertrader.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;

// The house at one moment: the feed price it quotes around, how far the resting book
// leans it, and the net shares users have bought from it recently. Every price the house
// charges comes from these, so the same state always gives the same prices.
//
// A fill pays the house's mid averaged along the impact curve over the fill, plus half
// the spread, rounded against the trader to four decimals. The touch, what the house
// quotes for the next share, is the same with no size.
public record HouseMarket(BigDecimal feed, double bookBps, double volume, int spreadBps, PriceImpact impact) {

    private static final BigDecimal BASIS_POINTS_BOTH_SIDES = BigDecimal.valueOf(20_000);

    public HouseQuote touch() {
        return HouseQuote.around(mid(impact.pressureBps(volume)), spreadBps);
    }

    // the house's mid now, leaned by the book and the flow, in basis points from the feed
    public double leanBps() {
        return bookBps + impact.pressureBps(volume);
    }

    // the price per share for a fill of this many shares on this side
    public BigDecimal price(OrderSide side, int shares) {
        BigDecimal mid = mid(impact.averageBps(volume, side, shares));
        BigDecimal half = mid.multiply(BigDecimal.valueOf(spreadBps)).divide(BASIS_POINTS_BOTH_SIDES);
        return side == OrderSide.BUY
                ? mid.add(half).setScale(4, RoundingMode.CEILING)
                : mid.subtract(half).setScale(4, RoundingMode.FLOOR);
    }

    // The most shares, up to `wanted`, the house fills without the price passing the
    // limit. A null limit is a market order, which takes them all. Prices only get worse
    // as the size grows, so a binary search finds the exact answer.
    public int fillable(OrderSide side, BigDecimal limit, int wanted) {
        if (limit == null || wanted <= 0) {
            return Math.max(wanted, 0);
        }
        int lo = 0, hi = wanted;
        while (lo < hi) {
            int mid = lo + (hi - lo + 1) / 2;
            if (within(side, price(side, mid), limit)) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private BigDecimal mid(double leanBps) {
        double total = bookBps + leanBps;
        return total == 0 ? feed : feed.multiply(BigDecimal.valueOf(1 + total / 10_000));
    }

    private static boolean within(OrderSide side, BigDecimal price, BigDecimal limit) {
        int cmp = price.compareTo(limit);
        return side == OrderSide.BUY ? cmp <= 0 : cmp >= 0;
    }
}
