package io.github.nishantshah0.papertrader.trading;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// How trading moves the house's prices. Everything is in basis points of the price.
//
// Flow. The house's mid leans by a curve of one number: the net shares users have
// bought from it recently (negative when they have sold). The lean is
// impact * sqrt(|volume| / depth), the square-root law real market impact follows, so
// net buying of `depth` shares lifts the price `impact` basis points and four times
// that lifts it twice as far. Volume fades towards zero by `decay` on every quote tick.
//
// A fill pays the average price along the curve between the volume it starts at and
// the volume it leaves. That makes prices path-independent: buying 1,000 shares in one
// order or in a thousand costs the same, and selling them back walks the same curve the
// other way. A round trip can only lose the spread, however it is split.
//
// Book. The house also leans its mid towards the side with more resting shares near the
// price, by up to `imbalance` basis points, scaled by its own depth so it takes real size
// to move it. Real market makers read the book the same way, which is why posting large
// orders you never mean to fill (spoofing) can pay.
@Component
public class PriceImpact {

    // the most the flow can lean the house's mid from the feed price
    static final double MAX_PRESSURE_BPS = 500;
    // resting orders count towards the book's lean within this distance of the feed price
    static final double IMBALANCE_WINDOW_BPS = 25;

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

    // no impact at all: the house trades any size at its quote
    public static PriceImpact none() {
        return new PriceImpact(0, 1, 0, 0);
    }

    // the lean, in basis points, once users have bought `volume` shares from the house net
    public double pressureBps(double volume) {
        if (impactBps == 0 || volume == 0) {
            return 0;
        }
        return Math.signum(volume) * Math.min(MAX_PRESSURE_BPS, impactBps * Math.sqrt(Math.abs(volume) / depthShares));
    }

    // the average lean over a fill of `shares` on this side, starting from `volume`: the
    // area under the curve between the two volumes, divided by the shares
    public double averageBps(double volume, OrderSide side, int shares) {
        if (shares <= 0) {
            return pressureBps(volume);
        }
        double end = side == OrderSide.BUY ? volume + shares : volume - shares;
        return (area(end) - area(volume)) / (end - volume);
    }

    // the volume after users buy (positive) or sell (negative) this many shares
    public double volumeAfter(double volume, OrderSide userSide, int shares) {
        return userSide == OrderSide.BUY ? volume + shares : volume - shares;
    }

    // what is left of the volume after one quote tick
    public double decayed(double volume) {
        return volume * (1 - decay);
    }

    // How far the book's resting orders lean the house's mid: up when bids outweigh asks.
    // Adding the house's depth to the denominator means the lean only approaches its
    // maximum once the imbalance is large next to the depth the house itself quotes.
    public double imbalanceSkew(long bidShares, long askShares) {
        return imbalanceBps * (bidShares - askShares) / (bidShares + askShares + depthShares);
    }

    // The integral of pressureBps from zero to `volume`, including the flat part past the
    // cap. The lean is symmetric, so this is the same for -v as for v: walking down from
    // zero to -v covers the same area as walking up to v, just below the axis.
    private double area(double volume) {
        if (impactBps == 0 || volume == 0) {
            return 0;
        }
        double v = Math.abs(volume);
        double cap = depthShares * Math.pow(MAX_PRESSURE_BPS / impactBps, 2);
        double curved = 2.0 / 3 * impactBps * Math.pow(Math.min(v, cap), 1.5) / Math.sqrt(depthShares);
        return curved + MAX_PRESSURE_BPS * Math.max(0, v - cap);
    }
}
