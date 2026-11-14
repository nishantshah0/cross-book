package io.github.nishantshah0.papertrader.quote;

import io.github.nishantshah0.papertrader.PaperTraderProperties;
import io.github.nishantshah0.papertrader.trading.OrderRejectedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Latest quote and a bounded history of observed prices for the terminal chart.
@Component
public class QuoteCache {

    private final Map<String, Quote> quotes = new ConcurrentHashMap<>();
    private final Map<String, List<Quote>> history = new ConcurrentHashMap<>();
    private static final int HISTORY_LIMIT = 240;

    private final PaperTraderProperties properties;
    private final String mode;
    private final Clock clock;

    public QuoteCache(PaperTraderProperties properties,
            @Value("${papertrader.feed.mode:demo}") String mode, Clock clock) {
        this.properties = properties;
        this.mode = mode;
        this.clock = clock;
        seed();
    }

    // back to the configured seed prices, stamped now, with the history cleared: how a
    // simulation starts a fresh run
    public void reset() {
        quotes.clear();
        history.clear();
        seed();
    }

    private void seed() {
        // live seeds are stamped at the epoch, so they never count as fresh
        Instant now = mode.equals("finnhub") ? Instant.EPOCH : clock.instant();
        properties.seedQuotes().forEach((symbol, price) -> {
            String key = symbol.toUpperCase(Locale.ROOT);
            Quote seed = new Quote(key, price, now);
            quotes.put(key, seed);
            history.put(key, mode.equals("finnhub") ? List.of() : List.of(seed));
        });
    }

    public Optional<Quote> get(String symbol) {
        return Optional.ofNullable(quotes.get(symbol));
    }

    public Quote require(String symbol) {
        return get(symbol).orElseThrow(() -> new UnknownSymbolException(symbol));
    }

    public void update(Quote quote) {
        boolean badPrice = quote.price() == null || quote.price().signum() <= 0
                || quote.price().compareTo(new BigDecimal("9999999999")) > 0;
        boolean badTime = quote.asOf() == null || quote.asOf().isAfter(clock.instant().plusSeconds(30));
        if (badPrice || badTime) {
            throw new IllegalArgumentException("invalid quote");
        }
        quotes.compute(quote.symbol(), (symbol, previous) -> {
            if (previous != null && quote.asOf().isBefore(previous.asOf())) {
                return previous; // out of order, keep the newer quote
            }
            var samples = new ArrayList<>(history.getOrDefault(symbol, List.of()));
            if (!samples.isEmpty() && samples.getLast().asOf().equals(quote.asOf())) {
                samples.removeLast();
            }
            samples.add(quote);
            int from = Math.max(0, samples.size() - HISTORY_LIMIT);
            history.put(symbol, List.copyOf(samples.subList(from, samples.size())));
            return quote;
        });
    }

    public Map<String, List<Quote>> history() {
        return Map.copyOf(history);
    }

    public boolean isTradable(Quote quote) {
        return !mode.equals("finnhub") || quote.asOf().isAfter(clock.instant().minusSeconds(120));
    }

    public void requireTradable(Quote quote) {
        if (!isTradable(quote)) {
            throw new OrderRejectedException("quote is stale; wait for a fresh market quote");
        }
    }

    public String mode() { return mode; }

    public Set<String> symbols() {
        return Set.copyOf(quotes.keySet());
    }

    public List<Quote> all() {
        return quotes.values().stream().sorted(Comparator.comparing(Quote::symbol)).toList();
    }
}
