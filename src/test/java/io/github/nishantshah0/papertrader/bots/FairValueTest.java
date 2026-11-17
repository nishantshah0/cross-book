package io.github.nishantshah0.papertrader.bots;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nishantshah0.papertrader.quote.Quote;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

// A fill is priced against the feed as it stood at the moment of the fill.
class FairValueTest {

    private static final Instant T0 = Instant.parse("2026-01-05T14:30:00Z");
    private static final List<Quote> FEED = List.of(quote(0, "100.00"), quote(15, "101.00"), quote(30, "99.50"));

    @Test
    void aFillIsPricedAtTheLatestFeedPriceAtOrBeforeIt() {
        assertThat(BotRunner.feedAt(FEED, T0.plusSeconds(14))).isEqualTo(100.00);
        assertThat(BotRunner.feedAt(FEED, T0.plusSeconds(15))).isEqualTo(101.00);
        assertThat(BotRunner.feedAt(FEED, T0.plusSeconds(3600))).isEqualTo(99.50);
    }

    @Test
    void aFillBeforeTheFirstPriceUsesTheFirst() {
        assertThat(BotRunner.feedAt(FEED, T0.minusSeconds(1))).isEqualTo(100.00);
    }

    private static Quote quote(int seconds, String price) {
        return new Quote("TEST", new BigDecimal(price), T0.plusSeconds(seconds));
    }
}
