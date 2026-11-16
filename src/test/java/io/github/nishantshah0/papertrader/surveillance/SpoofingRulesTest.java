package io.github.nishantshah0.papertrader.surveillance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nishantshah0.papertrader.trading.OrderSide;
import io.github.nishantshah0.papertrader.trading.OrderType;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

// The rules on hand-written logs: the pattern they are meant to catch, the honest
// patterns that look like it, and each threshold from just inside to just outside.
class SpoofingRulesTest {

    private static final Instant T0 = Instant.parse("2026-01-05T14:30:00Z");
    private final List<LoggedOrder> orders = new ArrayList<>();
    private long seq;

    @Test
    void catchesLayeringRepeatedOverEpisodes() {
        for (int episode = 0; episode < 3; episode++) {
            spoofEpisode(1, episode * 60_000L, 40, 10, 3_000);
        }
        List<Alert> alerts = new SpoofingRules().scan(log());

        assertThat(alerts).singleElement().satisfies(alert -> {
            assertThat(alert.accountId()).isEqualTo(1);
            assertThat(alert.score()).isEqualTo(3); // three episodes, not nine orders
            assertThat(alert.orders()).hasSize(9);
            assertThat(alert.evidence()).hasSize(12); // each fake's PLACED event, and each real order's
            assertThat(alert.summary()).contains("3 episodes");
        });
    }

    @Test
    void twoEpisodesAreNotEnough() {
        spoofEpisode(1, 0, 40, 10, 3_000);
        spoofEpisode(1, 60_000, 40, 10, 3_000);
        assertThat(new SpoofingRules().scan(log())).isEmpty();
    }

    @Test
    void aMarketMakerRequotingBothSidesAtOnceIsNotSpoofing() {
        for (int i = 0; i < 30; i++) {
            long at = i * 4_000L;
            order(2, OrderSide.BUY, 20, at, at + 3_000, "CANCELLED", 0);
            order(2, OrderSide.SELL, 20, at, at + 3_000, "CANCELLED", 0); // same instant
        }
        MarketLog log = log();
        assertThat(new SpoofingRules().scan(log)).isEmpty();
        // the naive baseline flags it: it cancels everything
        assertThat(new CancelRatioBaseline().scan(log)).extracting(Alert::accountId).containsExactly(2L);
    }

    @Test
    void eachThresholdHolds() {
        // a far bigger order, but resting longer than 10 seconds
        for (int e = 0; e < 3; e++) {
            spoofEpisode(3, e * 60_000L, 40, 10, 10_001);
        }
        // the other-side order is not small enough: 29 is under 3x 10
        for (int e = 0; e < 3; e++) {
            spoofEpisode(4, e * 60_000L, 29, 10, 3_000);
        }
        // a quarter of the "fake" filled before the cancel: it was trading
        for (int e = 0; e < 3; e++) {
            long at = e * 60_000L;
            order(5, OrderSide.BUY, 40, at, at + 3_000, "CANCELLED", 10);
            order(5, OrderSide.SELL, 10, at + 1_000, at + 2_000, "FILLED", 10);
        }
        // and the edges that still count: exactly 10 seconds, exactly 3x, just under a quarter filled
        for (int e = 0; e < 3; e++) {
            long at = e * 60_000L;
            order(6, OrderSide.BUY, 30, at, at + 10_000, "CANCELLED", 7);
            order(6, OrderSide.SELL, 10, at + 1_000, at + 2_000, "FILLED", 10);
        }
        assertThat(new SpoofingRules().scan(log())).extracting(Alert::accountId).containsExactly(6L);
    }

    // The detector sees market data only. If any file in this package mentions the
    // answer key or the bots, a detector could peek at who is cheating.
    @Test
    void theDetectorCodeNeverTouchesTheAnswerKey() throws IOException {
        Path main = Path.of("src/main/java/io/github/nishantshah0/papertrader/surveillance");
        try (Stream<Path> files = Files.list(main)) {
            List<Path> sources = files.filter(p -> p.toString().endsWith(".java")).toList();
            assertThat(sources).hasSizeGreaterThan(5);
            for (Path source : sources) {
                assertThat(Files.readString(source)).as(source.getFileName().toString())
                        .doesNotContainIgnoringCase("answer_key").doesNotContain("AnswerKey")
                        .doesNotContain("papertrader.bots").doesNotContain("papertrader.grading");
            }
        }
    }

    // three fakes on the bid, then a small real offer, then the fakes pulled
    private void spoofEpisode(long account, long at, int fakeSize, int realSize, long fakeLifeMs) {
        for (int i = 0; i < 3; i++) {
            order(account, OrderSide.BUY, fakeSize, at, at + fakeLifeMs, "CANCELLED", 0);
        }
        order(account, OrderSide.SELL, realSize, at + 1_500, at + 2_500, "FILLED", realSize);
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
