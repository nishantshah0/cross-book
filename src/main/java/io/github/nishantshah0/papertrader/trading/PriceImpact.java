package io.github.nishantshah0.papertrader.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// How trading moves prices. Three effects, all in basis points of the price:
//
// Size. The house prices a fill of q shares at its touch price moved by
// impact * sqrt(q / depth): the square-root law that market impact follows in real
// markets. Taking `depth` shares costs `impact` basis points; four times that costs
// twice as much.
//
// Flow. After an order trades, the house's mid moves the same amount in the direction
// the taker traded, then fades back towards the feed price by `decay` on every quote
// tick. The taker pays the impact before the price moves, so a buy followed by an
// immediate sell back always loses money.
//
// Book. The house leans its mid towards the side with more resting shares near the
// price, by up to `imbalance` basis points. The lean is scaled by the house's own depth,
// so it takes orders of a size that matters to move it: a lone 1-share bid barely
// registers. Real market makers read the book the same way, which is why posting large
// orders you never mean to fill (spoofing) can pay.
@Component
public class PriceImpact {

    // the most the flow can push the house's mid from the feed price
    static final double MAX_PRESSURE_BPS = 500;
    // the most a single fill pays for its size
    static final double MAX_FILL_BPS = 5_000;
    // resting orders count towards the book's lean within this distance of the feed price
    static final double IMBALANCE_WINDOW_BPS = 25;

    private static final double BPS = 10_000;

    private final double impactBps;
    private final int depthShares;
    private final double decay;
    private final double imbalanceBps;

    PriceImpact(@Value("${papertrader.house.impact-bps:10}") double impactBps,
            @Value("${papertrader.house.depth-shares:500}") int depthShares,
            @Value("${papertrader.house.impact-decay:0.25}") double decay,
            @Value("${papertrader.house.imbalance-bps:4}") double imbalanceBps) {
        if (impactBps < 0 || impactBps > 1_000) {
            throw new IllegalArgumentException("house impact must be between 0 and 1000 basis points");
        }
        if (depthShares < 1) {
            throw new IllegalArgumentException("house depth must be at least one share");
        }
        if (decay < 0 || decay > 1) {
            throw new IllegalArgumentException("impact decay must be between 0 and 1");
        }
        if (imbalanceBps < 0 || imbalanceBps > 100) {
            throw new IllegalArgumentException("book imbalance must be between 0 and 100 basis points");
        }
        this.impactBps = impactBps;
        this.depthShares = depthShares;
        this.decay = decay;
        this.imbalanceBps = imbalanceBps;
    }

    // no impact at all: the house trades any size at its quote, as it did before
    public static PriceImpact none() {
        return new PriceImpact(0, 1, 0, 0);
    }

    // what trading this many shares costs, and then moves the price, in basis points
    public double bps(int shares) {
        if (impactBps == 0 || shares <= 0) {
            return 0;
        }
        return Math.min(MAX_FILL_BPS, impactBps * Math.sqrt((double) shares / depthShares));
    }

    // the price the house fills this many shares at: its touch price, moved against the
    // taker by the impact of the size, rounded against the taker to four decimals
    public BigDecimal fillPrice(HouseQuote house, OrderSide side, int shares) {
        BigDecimal touch = house.priceFor(side);
        BigDecimal move = BigDecimal.valueOf(bps(shares) / BPS);
        return side == OrderSide.BUY
                ? touch.multiply(BigDecimal.ONE.add(move)).setScale(4, RoundingMode.CEILING)
                : touch.multiply(BigDecimal.ONE.subtract(move)).setScale(4, RoundingMode.FLOOR);
    }

    // The most shares, up to `wanted`, the house fills without passing the limit. A null
    // limit is a market order, which takes them all.
    public int fillable(HouseQuote house, OrderSide side, BigDecimal limit, int wanted) {
        if (limit == null || wanted <= 0) {
            return Math.max(wanted, 0);
        }
        BigDecimal touch = house.priceFor(side);
        if (!within(side, touch, limit)) {
            return 0;
        }
        if (impactBps == 0) {
            return wanted;
        }
        double room = side == OrderSide.BUY
                ? limit.doubleValue() / touch.doubleValue() - 1
                : 1 - limit.doubleValue() / touch.doubleValue();
        double estimate = depthShares * Math.pow(room * BPS / impactBps, 2);
        int shares = (int) Math.min(wanted, Math.floor(estimate) + 1);
        while (shares > 0 && !within(side, fillPrice(house, side, shares), limit)) {
            shares--; // the estimate ignores rounding, so step back to the exact answer
        }
        return shares;
    }

    // the house's lean after a taker trades this many shares
    public double pressureAfter(double pressureBps, OrderSide takerSide, int shares) {
        double moved = pressureBps + (takerSide == OrderSide.BUY ? bps(shares) : -bps(shares));
        return Math.max(-MAX_PRESSURE_BPS, Math.min(MAX_PRESSURE_BPS, moved));
    }

    // what is left of the lean after one quote tick
    public double decayed(double pressureBps) {
        return pressureBps * (1 - decay);
    }

    // How far the book's resting orders lean the house's mid: up when bids outweigh asks.
    // Adding the house's depth to the denominator means the lean only approaches its
    // maximum once the imbalance is large next to the depth the house itself quotes.
    public double imbalanceSkew(long bidShares, long askShares) {
        return imbalanceBps * (bidShares - askShares) / (bidShares + askShares + depthShares);
    }

    private static boolean within(OrderSide side, BigDecimal price, BigDecimal limit) {
        int cmp = price.compareTo(limit);
        return side == OrderSide.BUY ? cmp <= 0 : cmp >= 0;
    }
}
