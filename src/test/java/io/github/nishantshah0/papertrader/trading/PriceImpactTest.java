package io.github.nishantshah0.papertrader.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PriceImpactTest {

    // net buying of 100 shares leans the house 20 bps; half the volume fades each tick; up to
    // 4 bps of lean from the book. The house quotes 10 bps wide around a feed price of 100.
    private final PriceImpact impact = new PriceImpact(20, 100, 0.5, 4);

    @Test
    void theLeanFollowsTheSquareRootOfNetVolume() {
        assertThat(impact.pressureBps(100)).isEqualTo(20.0);
        assertThat(impact.pressureBps(400)).isEqualTo(40.0);
        assertThat(impact.pressureBps(25)).isEqualTo(10.0);
        assertThat(impact.pressureBps(-100)).isEqualTo(-20.0);
        assertThat(impact.pressureBps(0)).isZero();
        assertThat(impact.pressureBps(1e12)).isEqualTo(PriceImpact.MAX_PRESSURE_BPS);
    }

    // a fill pays the average of the curve it walks: two thirds of where it ends up
    @Test
    void aFillPaysTheAverageOfTheCurveItWalks() {
        assertThat(impact.averageBps(0, OrderSide.BUY, 100)).isCloseTo(40.0 / 3, within(1e-9));
        assertThat(impact.averageBps(0, OrderSide.SELL, 100)).isCloseTo(-40.0 / 3, within(1e-9));
        // past the cap the curve is flat, so every further share pays the cap
        double capped = 100 * Math.pow(PriceImpact.MAX_PRESSURE_BPS / 20, 2);
        assertThat(impact.averageBps(capped + 1_000, OrderSide.BUY, 1_000))
                .isCloseTo(PriceImpact.MAX_PRESSURE_BPS, within(1e-6));
    }

    @Test
    void theHousePricesSizeAlongTheCurveAndRoundsAgainstTheTaker() {
        HouseMarket house = market(0);
        assertThat(house.touch().ask()).isEqualByComparingTo("100.05");
        assertThat(house.touch().bid()).isEqualByComparingTo("99.95");
        // 100 * (1 + 13.33 bps) * 1.0005
        assertThat(house.price(OrderSide.BUY, 100).doubleValue()).isCloseTo(100.1834, within(0.0001));
        assertThat(house.price(OrderSide.SELL, 100).doubleValue()).isCloseTo(99.8166, within(0.0001));
        assertThat(house.price(OrderSide.BUY, 1)).isGreaterThanOrEqualTo(house.touch().ask());
        assertThat(house.price(OrderSide.SELL, 1)).isLessThanOrEqualTo(house.touch().bid());
        // once users have bought 100, the house's touch has moved 20 bps
        assertThat(market(100).touch().ask()).isEqualByComparingTo("100.2501");
    }

    // The reviewer's finding: separate orders used to push the price further than one
    // order of the same total size. Now the cost and the push depend only on the total.
    @Test
    void splittingAnOrderChangesNothing() {
        double[] whole = {0}, pieces = {0};
        double wholeCash = trade(whole, OrderSide.BUY, 1_000);
        double pieceCash = 0;
        for (int i = 0; i < 1_000; i++) {
            pieceCash += trade(pieces, OrderSide.BUY, 1);
        }
        assertThat(pieces[0]).isEqualTo(whole[0]);
        // Every fill rounds its price per share up to the next hundredth of a cent, so the
        // two can differ by at most that much a share, in either direction: about the
        // thousandth part of the spread a round trip pays.
        assertThat(pieceCash).isCloseTo(wholeCash, within(1_000 * 0.0001));
    }

    @Test
    void theReviewersRoundTripsNowLose() {
        assertThat(roundTrip(0, split(1_000, 20), split(1_000, 1))).as("20 buys, 1 sell").isNegative();
        assertThat(roundTrip(0, split(1_000, 1_000), split(1_000, 1))).as("1,000 one-share buys, 1 sell").isNegative();
        assertThat(roundTrip(0, split(1_000, 1), split(1_000, 1_000))).as("1 buy, 1,000 one-share sells").isNegative();
        assertThat(roundTrip(0, split(1_000, 20), split(1_000, 20))).as("20 buys, 20 sells").isNegative();
    }

    // With no time passing, any sequence of trades that ends holding what it started with
    // loses money, from wherever other traders left the house: in random orders, sizes and
    // splits, buying or selling first. Prices depend only on the volume, so walking out
    // and back is free except for the spread and rounding, which both go against you.
    @Test
    void noSequenceThatEndsFlatMakesMoneyWithoutTimePassing() {
        Random random = new Random(20261114);
        for (int trial = 0; trial < 500; trial++) {
            double start = random.nextInt(4_001) - 2_000;
            assertThat(flatSequence(random, start, false)).as("trial %d", trial).isNegative();
        }
    }

    // Starting from a quiet market, letting the price fade part way through does not help
    // either: the fade pulls the price back towards where it started, against whatever
    // your own trades did to it.
    @Test
    void noSequenceThatEndsFlatMakesMoneyFromAQuietMarketEvenAsPricesFade() {
        Random random = new Random(1114);
        for (int trial = 0; trial < 500; trial++) {
            assertThat(flatSequence(random, 0, true)).as("trial %d", trial).isNegative();
        }
    }

    // What fading does allow, deliberately: when other traders have pushed the price up,
    // selling into it and buying back after it fades can pay. That money comes from those
    // earlier buyers, through the house; it is trading, not a hole in the house's prices.
    @Test
    void tradingAgainstSomeoneElsesPushAsItFadesCanPay() {
        double[] volume = {1_000}; // others bought 1,000 shares and lifted the price
        double cash = trade(volume, OrderSide.SELL, 200);
        for (int i = 0; i < 10; i++) {
            volume[0] = impact.decayed(volume[0]);
        }
        cash += trade(volume, OrderSide.BUY, 200);
        assertThat(cash).isPositive();
    }

    // random buys and sells that net to zero, from `start`, with quote ticks if `fading`
    private double flatSequence(Random random, double start, boolean fading) {
        double[] volume = {start};
        List<Integer> moves = new ArrayList<>();
        int total = 1 + random.nextInt(5_000);
        for (int left = total; left > 0; ) {
            int size = Math.min(left, 1 + random.nextInt(Math.max(1, total / (1 + random.nextInt(50)))));
            moves.add(size);
            moves.add(-size);
            left -= size;
        }
        Collections.shuffle(moves, random);
        if (random.nextBoolean()) {
            moves.replaceAll(m -> -m);
        }
        double cash = 0;
        for (int move : moves) {
            cash += trade(volume, move > 0 ? OrderSide.BUY : OrderSide.SELL, Math.abs(move));
            if (fading && random.nextInt(4) == 0) {
                volume[0] = impact.decayed(volume[0]);
            }
        }
        return cash;
    }

    @Test
    void aLimitTakesExactlyTheSharesItsPriceAllows() {
        HouseMarket house = market(0);
        BigDecimal limit = new BigDecimal("100.10");

        int shares = house.fillable(OrderSide.BUY, limit, 400);

        assertThat(shares).isEqualTo(14);
        assertThat(house.price(OrderSide.BUY, shares)).isLessThanOrEqualTo(limit);
        assertThat(house.price(OrderSide.BUY, shares + 1)).isGreaterThan(limit);
        assertThat(house.fillable(OrderSide.BUY, limit, 4)).isEqualTo(4);
        assertThat(house.fillable(OrderSide.BUY, new BigDecimal("100.04"), 10)).isZero();
        assertThat(house.fillable(OrderSide.BUY, null, 1_000)).isEqualTo(1_000);
    }

    // The reviewer's second finding: a sell resting exactly at the house's bid used to be
    // filled in full at the bid by the next sweep, skipping the size cost. Priced on the
    // curve, not even one share fits at the touch.
    @Test
    void anOrderRestingAtTheTouchGetsNoFreeFill() {
        HouseMarket house = market(0);
        assertThat(house.fillable(OrderSide.SELL, house.touch().bid(), 1_000)).isZero();
        assertThat(house.fillable(OrderSide.BUY, house.touch().ask(), 1_000)).isZero();
    }

    @Test
    void withNoImpactTheHouseTradesAnySizeAtItsQuote() {
        HouseMarket house = new HouseMarket(new BigDecimal("100"), 0, 0, 10, PriceImpact.none());
        assertThat(house.price(OrderSide.BUY, 1_000_000)).isEqualByComparingTo(house.touch().ask());
        assertThat(house.fillable(OrderSide.SELL, house.touch().bid(), 1_000_000)).isEqualTo(1_000_000);
    }

    @Test
    void theBookLeansTheHouseTowardsTheHeavierSide() {
        // 4 bps * (300 - 100) / (300 + 100 + 100 depth)
        assertThat(impact.imbalanceSkew(300, 100)).isCloseTo(1.6, within(1e-12));
        assertThat(impact.imbalanceSkew(0, 50)).isCloseTo(-4.0 / 3, within(1e-12));
        assertThat(impact.imbalanceSkew(0, 0)).isZero();
        assertThat(impact.imbalanceSkew(50, 50)).isZero();
    }

    // Size matters: one share is noise next to the house's depth, a block of shares is
    // not, and no imbalance can lean the house more than its maximum.
    @Test
    void aTinyOrderBarelyLeansTheHouseAndOnlyBigOnesApproachTheMaximum() {
        assertThat(impact.imbalanceSkew(1, 0)).isLessThan(0.04); // 4 / 101
        assertThat(impact.imbalanceSkew(100, 0)).isEqualTo(2.0);
        assertThat(impact.imbalanceSkew(900, 0)).isCloseTo(3.6, within(1e-12));
        assertThat(impact.imbalanceSkew(1_000_000, 0)).isLessThan(4.0).isGreaterThan(3.99);
    }

    @Test
    void theVolumeFadesOnEachTick() {
        assertThat(impact.decayed(100)).isEqualTo(50.0);
        assertThat(impact.volumeAfter(10, OrderSide.SELL, 30)).isEqualTo(-20.0);
    }

    @Test
    void refusesSettingsOutsideTheSupportedRange() {
        assertThatThrownBy(() -> new PriceImpact(-1, 100, 0.5, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PriceImpact(20, 0, 0.5, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PriceImpact(20, 100, 1.5, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PriceImpact(20, 100, 0.5, 101)).isInstanceOf(IllegalArgumentException.class);
    }

    private HouseMarket market(double volume) {
        return new HouseMarket(new BigDecimal("100"), 0, volume, 10, impact);
    }

    // one fill against the house as the engine prices it; returns the cash it moves
    private double trade(double[] volume, OrderSide side, int shares) {
        BigDecimal price = market(volume[0]).price(side, shares);
        volume[0] = impact.volumeAfter(volume[0], side, shares);
        return (side == OrderSide.BUY ? -1 : 1) * price.doubleValue() * shares;
    }

    private double roundTrip(double startVolume, List<Integer> buys, List<Integer> sells) {
        double[] volume = {startVolume};
        double cash = 0;
        for (int size : buys) {
            cash += trade(volume, OrderSide.BUY, size);
        }
        for (int size : sells) {
            cash += trade(volume, OrderSide.SELL, size);
        }
        return cash;
    }

    private static List<Integer> split(int shares, int pieces) {
        List<Integer> sizes = new ArrayList<>();
        for (int i = 0; i < pieces; i++) {
            sizes.add(shares / pieces + (i < shares % pieces ? 1 : 0));
        }
        return sizes;
    }
}
