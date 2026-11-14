package io.github.nishantshah0.papertrader.quote;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DemoWalkTest {

    private static final BigDecimal SEED = new BigDecimal("200.00");

    @Test
    void pricesWanderButStayNearTheSeed() {
        DemoWalk walk = new DemoWalk(new Random(7));
        List<BigDecimal> prices = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            prices.add(walk.next("AAPL", SEED));
        }

        assertThat(prices).allSatisfy(price -> assertThat(price).isBetween(new BigDecimal("188"), new BigDecimal("212")));
        assertThat(prices.stream().distinct().count()).isGreaterThan(4_000);
        assertThat(prices).anyMatch(price -> price.compareTo(SEED) > 0).anyMatch(price -> price.compareTo(SEED) < 0);
        assertThat(prices.get(0).scale()).isEqualTo(4);
    }

    @Test
    void eachSymbolWalksOnItsOwnAndTheSameSeedRepeatsTheSamePath() {
        DemoWalk first = new DemoWalk(new Random(42));
        DemoWalk second = new DemoWalk(new Random(42));

        assertThat(first.next("AAPL", SEED)).isEqualTo(second.next("AAPL", SEED));
        BigDecimal msft = first.next("MSFT", new BigDecimal("400.00"));
        assertThat(msft).isBetween(new BigDecimal("376"), new BigDecimal("424"));
        assertThat(second.next("MSFT", new BigDecimal("400.00"))).isEqualTo(msft);
    }
}
