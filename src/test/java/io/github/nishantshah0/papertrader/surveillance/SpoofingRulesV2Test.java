package io.github.nishantshah0.papertrader.surveillance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nishantshah0.papertrader.trading.OrderSide;
import io.github.nishantshah0.papertrader.trading.OrderType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

// v2 on the holes the AI cheater found in v1, and on the honest patterns v1 left alone.
class SpoofingRulesV2Test {

    private static final Instant T0 = Instant.parse("2026-01-05T14:30:00Z");
    private final List<LoggedOrder> orders = new ArrayList<>();
    private long seq;

    // the cheater's tactic against v1: four fakes, each only 2x the real order
    @Test
    void fakesThatEachLookSmallAreJudgedTogether() {
        for (int e = 0; e < 3; e++) {
            long at = e * 60_000L;
            for (int i = 0; i < 4; i++) {
                order(1, OrderSide.BUY, 20, at, at + 3_000, "CANCELLED", 0);
            }
            order(1, OrderSide.SELL, 10, at + 1_500, at + 2_500, "FILLED", 10);
        }
        assertThat(new SpoofingRules().scan(log())).isEmpty(); // v1 misses it
        assertThat(new SpoofingRulesV2().scan(log())).singleElement().satisfies(alert -> {
            assertThat(alert.accountId()).isEqualTo(1);
            assertThat(alert.score()).isEqualTo(3);
            assertThat(alert.orders()).hasSize(12);
            assertThat(alert.evidence()).hasSize(15);
        });
    }

    @Test
    void fakesHeldPastTenSecondsOrPlacedAfterTheRealOrderStillCount() {
        for (int e = 0; e < 3; e++) {
            long at = e * 60_000L;
            order(2, OrderSide.BUY, 40, at, at + 25_000, "CANCELLED", 0); // 25 s
            order(2, OrderSide.SELL, 10, at + 1_000, at + 20_000, "CANCELLED", 0);
            order(3, OrderSide.SELL, 10, at, at + 6_000, "FILLED", 10); // real first
            order(3, OrderSide.BUY, 40, at + 2_000, at + 5_000, "CANCELLED", 0);
        }
        assertThat(new SpoofingRules().scan(log())).isEmpty();
        assertThat(new SpoofingRulesV2().scan(log())).extracting(Alert::accountId).containsExactly(2L, 3L);
    }

    @Test
    void honestPatternsStayClear() {
        for (int i = 0; i < 30; i++) {
            long at = i * 4_000L;
            order(4, OrderSide.BUY, 20, at, at + 3_000, "CANCELLED", 0); // a market maker requoting
            order(4, OrderSide.SELL, 20, at, at + 3_000, "CANCELLED", 0);
        }
        for (int e = 0; e < 3; e++) {
            long at = e * 60_000L;
            // under 3x in total
            order(5, OrderSide.BUY, 14, at, at + 3_000, "CANCELLED", 0);
            order(5, OrderSide.BUY, 15, at, at + 3_000, "CANCELLED", 0);
            order(5, OrderSide.SELL, 10, at + 1_000, at + 2_000, "FILLED", 10);
            // resting longer than a minute
            order(6, OrderSide.BUY, 40, at, at + 61_000, "CANCELLED", 0);
            order(6, OrderSide.SELL, 10, at + 1_000, at + 2_000, "FILLED", 10);
            // over 15 seconds apart, so not one scheme
            order(7, OrderSide.SELL, 10, at, at + 40_000, "FILLED", 10);
            order(7, OrderSide.BUY, 40, at + 16_000, at + 20_000, "CANCELLED", 0);
        }
        for (int e = 0; e < 3; e++) {
            long at = e * 60_000L;
            // a market maker near its limit: a small bid that trades, a full-size ask, posted together
            order(9, OrderSide.BUY, 10, at, at + 3_000, "FILLED", 10);
            order(9, OrderSide.SELL, 40, at, at + 3_000, "CANCELLED", 0);
            // a bid that traded, then re-quoted: the next ask starts the instant the bid ends
            order(10, OrderSide.BUY, 10, at, at + 3_000, "CANCELLED", 3);
            order(10, OrderSide.SELL, 20, at, at + 3_000, "CANCELLED", 0);
            order(10, OrderSide.SELL, 20, at + 3_000, at + 6_000, "CANCELLED", 0);
        }
        for (int e = 0; e < 2; e++) { // only two episodes
            long at = e * 60_000L;
            order(8, OrderSide.BUY, 40, at, at + 3_000, "CANCELLED", 0);
            order(8, OrderSide.SELL, 10, at + 1_000, at + 2_000, "FILLED", 10);
        }
        assertThat(new SpoofingRulesV2().scan(log())).isEmpty();
    }

    private void order(long account, OrderSide side, int quantity, long placedMs, long endedMs, String end,
            int filled) {
        orders.add(new LoggedOrder(orders.size() + 1, account, "TEST", side, OrderType.LIMIT, quantity,
                new BigDecimal("100.00"), ++seq, T0.plusMillis(placedMs), T0.plusMillis(endedMs), end, filled));
    }

    private MarketLog log() {
        return new MarketLog(orders, Map.of());
    }
}
