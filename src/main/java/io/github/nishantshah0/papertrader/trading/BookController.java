package io.github.nishantshah0.papertrader.trading;

import io.github.nishantshah0.papertrader.quote.QuoteCache;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// top of the book for one symbol: users' resting orders by price level, and the house
// when there is one
@RestController
@RequestMapping("/api/book")
class BookController {

    private static final int LEVELS = 10;

    private final MatchingEngine engine;
    private final QuoteCache quotes;

    BookController(MatchingEngine engine, QuoteCache quotes) {
        this.engine = engine;
        this.quotes = quotes;
    }

    // What a market order of this size would pay per share now: the house's price after
    // its size cost, or in book mode the average over the resting orders it would take.
    // price is null when it cannot be priced (a stale quote, or too thin a book).
    record Estimate(String symbol, MarketMode market, OrderSide side, int shares, BigDecimal price) {
    }

    @GetMapping("/{symbol}/estimate")
    Estimate estimate(@PathVariable String symbol, @RequestParam OrderSide side,
            @RequestParam @Min(1) @Max(1_000_000) int shares) {
        String key = symbol.toUpperCase(Locale.ROOT);
        quotes.require(key);
        return new Estimate(key, engine.mode(), side, shares, engine.estimatePrice(key, side, shares));
    }

    @GetMapping("/{symbol}")
    MatchingEngine.BookView book(@PathVariable String symbol) {
        String key = symbol.toUpperCase(Locale.ROOT);
        quotes.require(key); // 404 for a symbol we do not quote
        return engine.book(key, LEVELS);
    }
}
