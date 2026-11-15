package io.github.nishantshah0.papertrader.trading;

import io.github.nishantshah0.papertrader.quote.Quote;
import io.github.nishantshah0.papertrader.quote.QuoteCache;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

// The price a position is valued at. In house mode that is the feed price, which the
// house quotes around. In book mode the feed is only a reference, so a position is worth
// what the stock last traded at; before its first trade, the feed price stands in.
//
// Portfolios, the leaderboard and the ledger all value positions through here, so they
// never disagree about what a share is worth.
@Component
public class Marks {

    private final MatchingEngine engine;
    private final QuoteCache quotes;

    Marks(MatchingEngine engine, QuoteCache quotes) {
        this.engine = engine;
        this.quotes = quotes;
    }

    public Optional<BigDecimal> mark(String symbol) {
        if (engine.mode() == MarketMode.BOOK) {
            Optional<BigDecimal> last = engine.lastPrice(symbol);
            if (last.isPresent()) {
                return last;
            }
        }
        return quotes.get(symbol).map(Quote::price);
    }

    // every quoted symbol's mark, by symbol
    public Map<String, BigDecimal> all() {
        Map<String, BigDecimal> marks = new TreeMap<>();
        for (String symbol : quotes.symbols()) {
            mark(symbol).ifPresent(price -> marks.put(symbol, price));
        }
        return marks;
    }
}
