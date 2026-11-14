package io.github.nishantshah0.papertrader.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PriceImpactTest {

    // 20 basis points for 100 shares, half the lean gone each tick, up to 4 bps of lean
    // from the book; the house quotes 10 bps wide around 100
    private final PriceImpact impact = new PriceImpact(20, 100, 0.5, 4);
    private final HouseQuote house = HouseQuote.around(new BigDecimal("100"), 10);

    @Test
    void sizeCostsGrowWithTheSquareRootOfTheShares() {
        assertThat(impact.bps(100)).isEqualTo(20.0);
        assertThat(impact.bps(400)).isEqualTo(40.0);
        assertThat(impact.bps(25)).isEqualTo(10.0);
        assertThat(impact.bps(0)).isZero();
        assertThat(impact.bps(100_000_000)).isEqualTo(PriceImpact.MAX_FILL_BPS);
    }

    @Test
    void theHouseChargesForSizeAndRoundsAgainstTheTaker() {
        assertThat(house.ask()).isEqualByComparingTo("100.05");
        // 100.05 * 1.002 = 100.2501
        assertThat(impact.fillPrice(house, OrderSide.BUY, 100)).isEqualByComparingTo("100.2501");
        // 99.95 * 0.998 = 99.7501
        assertThat(impact.fillPrice(house, OrderSide.SELL, 100)).isEqualByComparingTo("99.7501");
        // 25 shares: 99.95 * 0.999 = 99.85005, rounded down for the seller
        assertThat(impact.fillPrice(house, OrderSide.SELL, 25)).isEqualByComparingTo("99.8500");
    }

    @Test
    void aLimitTakesExactlyTheSharesItsPriceAllows() {
        BigDecimal limit = new BigDecimal("100.10");

        int shares = impact.fillable(house, OrderSide.BUY, limit, 400);

        assertThat(shares).isEqualTo(6);
        assertThat(impact.fillPrice(house, OrderSide.BUY, shares)).isLessThanOrEqualTo(limit);
        assertThat(impact.fillPrice(house, OrderSide.BUY, shares + 1)).isGreaterThan(limit);
        assertThat(impact.fillable(house, OrderSide.BUY, limit, 4)).isEqualTo(4);
        assertThat(impact.fillable(house, OrderSide.BUY, new BigDecimal("100.04"), 10)).isZero();
        assertThat(impact.fillable(house, OrderSide.SELL, new BigDecimal("99.80"), 1_000))
                .isEqualTo(56); // 99.95 * (1 - 20 * sqrt(56 / 100) / 10000) = 99.8004
        assertThat(impact.fillable(house, OrderSide.BUY, null, 1_000)).isEqualTo(1_000);
        assertThat(PriceImpact.none().fillable(house, OrderSide.BUY, new BigDecimal("100.05"), 1_000_000))
                .isEqualTo(1_000_000);
    }

    @Test
    void trafficPushesTheHouseAndTheLeanFades() {
        double pushed = impact.pressureAfter(0, OrderSide.BUY, 100);
        assertThat(pushed).isEqualTo(20.0);
        assertThat(impact.pressureAfter(pushed, OrderSide.SELL, 400)).isEqualTo(-20.0);
        assertThat(impact.decayed(pushed)).isEqualTo(10.0);
        assertThat(impact.pressureAfter(0, OrderSide.BUY, 100_000_000)).isEqualTo(PriceImpact.MAX_PRESSURE_BPS);
    }

    @Test
    void theBookLeansTheHouseTowardsTheHeavierSide() {
        assertThat(impact.imbalanceSkew(300, 100)).isEqualTo(2.0);
        assertThat(impact.imbalanceSkew(0, 50)).isEqualTo(-4.0);
        assertThat(impact.imbalanceSkew(0, 0)).isZero();
    }

    // The property that keeps the market honest: nobody can make money by trading with
    // the house and immediately trading back, however they size or split the orders.
    @Test
    void aRoundTripAgainstTheHouseNeverPays() {
        for (int shares : new int[] {1, 7, 50, 100, 333, 1_000, 25_000, 1_000_000}) {
            for (int pieces : new int[] {1, 2, 5, 20}) {
                assertThat(roundTrip(shares, pieces)).as("%d shares in %d pieces", shares, pieces).isNegative();
            }
        }
    }

    @Test
    void refusesSettingsOutsideTheSupportedRange() {
        assertThatThrownBy(() -> new PriceImpact(-1, 100, 0.5, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PriceImpact(20, 0, 0.5, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PriceImpact(20, 100, 1.5, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PriceImpact(20, 100, 0.5, 101)).isInstanceOf(IllegalArgumentException.class);
    }

    // buy `shares` from the house in `pieces` orders, then sell them all back the same
    // way, with the house leaning after every order as the engine does. Returns the cash
    // made, which should never be positive.
    private double roundTrip(int shares, int pieces) {
        double cash = 0, pressure = 0;
        int each = Math.max(1, shares / pieces);
        int bought = 0;
        while (bought < shares) {
            int size = Math.min(each, shares - bought);
            cash -= impact.fillPrice(quote(pressure), OrderSide.BUY, size).doubleValue() * size;
            pressure = impact.pressureAfter(pressure, OrderSide.BUY, size);
            bought += size;
        }
        int sold = 0;
        while (sold < shares) {
            int size = Math.min(each, shares - sold);
            cash += impact.fillPrice(quote(pressure), OrderSide.SELL, size).doubleValue() * size;
            pressure = impact.pressureAfter(pressure, OrderSide.SELL, size);
            sold += size;
        }
        return cash;
    }

    private static HouseQuote quote(double pressureBps) {
        return HouseQuote.around(new BigDecimal("100").multiply(BigDecimal.valueOf(1 + pressureBps / 10_000)), 10);
    }
}
