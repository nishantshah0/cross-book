package io.github.nishantshah0.papertrader.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nishantshah0.papertrader.PaperTraderProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarketDataFeedTest {

    private static final PaperTraderProperties PROPERTIES = new PaperTraderProperties(new BigDecimal("1000"),
            Map.of("AAPL", new BigDecimal("232.15"), "MSFT", new BigDecimal("428.60"),
                    "NVDA", new BigDecimal("118.90")));

    @Test
    void theSameSeedReplaysTheSamePrices() {
        assertThat(prices("42", 20)).isEqualTo(prices("42", 20));
    }

    @Test
    void aDifferentSeedGivesDifferentPrices() {
        assertThat(prices("42", 20)).isNotEqualTo(prices("43", 20));
    }

    @Test
    void anUnseededFeedStillWalks() {
        List<String> walk = prices("", 5);
        assertThat(walk).hasSize(5).doesNotHaveDuplicates();
    }

    @Test
    void refusesASeedThatIsNotANumber() {
        assertThatThrownBy(() -> feed(new QuoteCache(PROPERTIES, "demo", Clock.systemUTC()), "forty-two"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole number");
    }

    // every symbol's price after each of `ticks` polls
    private static List<String> prices(String seed, int ticks) {
        QuoteCache quotes = new QuoteCache(PROPERTIES, "demo", Clock.systemUTC());
        MarketDataFeed feed = feed(quotes, seed);
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            feed.poll();
            seen.add(quotes.all().stream().map(q -> q.symbol() + "=" + q.price().toPlainString()).toList().toString());
        }
        return seen;
    }

    private static MarketDataFeed feed(QuoteCache quotes, String seed) {
        return new MarketDataFeed(quotes, PROPERTIES, event -> { }, "", seed, Clock.systemUTC());
    }
}
