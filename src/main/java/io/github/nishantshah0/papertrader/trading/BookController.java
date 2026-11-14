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

    // what the house charges per share for an order of this size, after the size cost
    record HousePrice(String symbol, OrderSide side, int shares, BigDecimal price) {
    }

    @GetMapping("/{symbol}/house-price")
    HousePrice housePrice(@PathVariable String symbol, @RequestParam OrderSide side,
            @RequestParam @Min(1) @Max(1_000_000) int shares) {
        String key = symbol.toUpperCase(Locale.ROOT);
        quotes.require(key);
        return new HousePrice(key, side, shares, engine.housePrice(key, side, shares));
    }

    @GetMapping("/{symbol}")
    MatchingEngine.BookView book(@PathVariable String symbol) {
        String key = symbol.toUpperCase(Locale.ROOT);
        quotes.require(key); // 404 for a symbol we do not quote
        return engine.book(key, LEVELS);
    }
}
