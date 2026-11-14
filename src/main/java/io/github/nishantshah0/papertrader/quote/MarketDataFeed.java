package io.github.nishantshah0.papertrader.quote;

import io.github.nishantshah0.papertrader.PaperTraderProperties;
import io.github.nishantshah0.papertrader.time.SimulationClock;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class MarketDataFeed {

    private static final Logger log = LoggerFactory.getLogger(MarketDataFeed.class);

    private final QuoteCache quotes;
    private final PaperTraderProperties properties;
    private final ApplicationEventPublisher events;
    private final String apiKey;
    private final RestClient client;
    private final Clock clock;
    private volatile DemoWalk walk;
    private volatile long seed;

    // seed fixes the demo feed's random walk, so a run can be replayed with the same
    // prices. Left blank, the feed picks one and logs it, so any run can be replayed.
    MarketDataFeed(QuoteCache quotes, PaperTraderProperties properties, ApplicationEventPublisher events,
            @Value("${papertrader.feed.api-key:}") String apiKey,
            @Value("${papertrader.feed.seed:}") String seed, Clock clock) {
        this.clock = clock;
        reseed(seed == null || seed.isBlank() ? new SecureRandom().nextLong() : parseSeed(seed));
        if (quotes.mode().equals("demo")) {
            log.info("Demo feed seed {} (set FEED_SEED to replay these prices)", this.seed);
        }
        this.quotes = quotes;
        this.properties = properties;
        this.events = events;
        this.apiKey = apiKey;
        if (!Set.of("demo", "static", "finnhub").contains(quotes.mode())) {
            throw new IllegalArgumentException("feed mode must be demo, static, or finnhub");
        }
        if (quotes.mode().equals("finnhub") && apiKey.isBlank()) {
            throw new IllegalArgumentException("FINNHUB_API_KEY is required for finnhub mode");
        }
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder().baseUrl("https://finnhub.io/api/v1").requestFactory(factory).build();
    }

    // On the wall clock, the feed refreshes on a schedule and announces each new quote.
    // Under a simulated clock the simulation calls refresh() itself, once per tick.
    @Scheduled(fixedDelayString = "${papertrader.feed.interval-ms:15000}",
            initialDelayString = "${papertrader.feed.initial-delay-ms:3000}")
    public void poll() {
        if (SimulationClock.isSimulated(clock)) {
            return;
        }
        refresh().forEach(events::publishEvent);
    }

    // Fetches the next quote for every symbol, in symbol order, and stores it. Returns
    // the quotes that changed; announcing them is the caller's job.
    public List<Quote> refresh() {
        if (quotes.mode().equals("static")) {
            return List.of();
        }
        List<Quote> updated = new ArrayList<>();
        for (String symbol : quotes.symbols().stream().sorted().toList()) {
            try {
                Quote quote = quotes.mode().equals("demo") ? demoQuote(symbol) : finnhubQuote(symbol);
                if (quote == null) {
                    continue;
                }
                quotes.update(quote);
                updated.add(quotes.require(symbol));
            } catch (RuntimeException e) {
                // never log provider response bodies or credentials. the last quote stays,
                // and execution refuses stale prices rather than falling back to seed prices
                log.warn("Quote refresh failed for {} ({})", symbol, e.getClass().getSimpleName());
            }
        }
        return updated;
    }

    // starts the demo walk again from this seed and from every symbol's seed price
    public void reseed(long seed) {
        this.seed = seed;
        this.walk = new DemoWalk(new Random(seed));
    }

    public long seed() {
        return seed;
    }

    private static long parseSeed(String seed) {
        try {
            return Long.parseLong(seed.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("feed seed must be a whole number, got " + seed);
        }
    }

    private Quote demoQuote(String symbol) {
        return new Quote(symbol, walk.next(symbol, properties.seedQuotes().get(symbol)), clock.instant());
    }

    private Quote finnhubQuote(String symbol) {
        Map<?, ?> response = client.get()
                .uri(uri -> uri.path("/quote").queryParam("symbol", symbol).build())
                .header("X-Finnhub-Token", apiKey)
                .retrieve()
                .body(Map.class);
        if (response == null || response.get("c") == null || response.get("t") == null) {
            return null;
        }
        return new Quote(symbol, new BigDecimal(response.get("c").toString()),
                Instant.ofEpochSecond(((Number) response.get("t")).longValue()));
    }
}
