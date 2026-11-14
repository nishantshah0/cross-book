package io.github.nishantshah0.papertrader.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PositionTest {

    private static final Instant T = Instant.parse("2026-01-05T14:30:00Z");

    @Test
    void buysAverageIntoTheCostBasis() {
        Position p = new Position(1L, "AAPL", T);
        p.buy(10, new BigDecimal("100.00"), T);
        p.buy(10, new BigDecimal("110.00"), T);
        assertThat(p.getQuantity()).isEqualTo(20);
        assertThat(p.getAvgCost()).isEqualByComparingTo("105.00");
    }

    @Test
    void sellsRealiseAgainstTheAverageCost() {
        Position p = new Position(1L, "AAPL", T);
        p.buy(10, new BigDecimal("100.00"), T);
        p.sell(4, new BigDecimal("125.50"), T);
        assertThat(p.getQuantity()).isEqualTo(6);
        assertThat(p.getAvgCost()).isEqualByComparingTo("100.00");
        assertThat(p.getRealizedPnl()).isEqualByComparingTo("102.00");
        assertThat(p.marketValue(new BigDecimal("90.00"))).isEqualByComparingTo("540.00");
        assertThat(p.unrealizedPnl(new BigDecimal("90.00"))).isEqualByComparingTo("-60.00");
    }

    @Test
    void averageCostRoundsToFourPlaces() {
        Position p = new Position(1L, "AAPL", T);
        p.buy(3, new BigDecimal("10.00"), T);
        p.buy(4, new BigDecimal("11.00"), T); // (30 + 44) / 7 = 10.571428...
        assertThat(p.getAvgCost()).isEqualByComparingTo("10.5714");
    }

    @Test
    void cannotSellMoreThanHeld() {
        Position p = new Position(1L, "AAPL", T);
        p.buy(3, new BigDecimal("50.00"), T);
        assertThatThrownBy(() -> p.sell(4, new BigDecimal("50.00"), T))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(p.getQuantity()).isEqualTo(3);
    }
}
