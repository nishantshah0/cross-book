package io.github.nishantshah0.papertrader;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nishantshah0.papertrader.account.AccountRepository;
import io.github.nishantshah0.papertrader.account.AccountResponse;
import io.github.nishantshah0.papertrader.portfolio.PortfolioResponse;
import io.github.nishantshah0.papertrader.portfolio.PortfolioService;
import io.github.nishantshah0.papertrader.quote.Quote;
import io.github.nishantshah0.papertrader.quote.QuoteCache;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import tools.jackson.core.type.TypeReference;

class LeaderboardApiTest extends ApiTestSupport {

    record Entry(Long accountId, String username, BigDecimal totalValue, BigDecimal returnPercent) {}

    @Autowired
    private QuoteCache quotes;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private PortfolioService portfolios;

    // the single query must rank exactly as valuing every portfolio one by one would
    @Test
    void theQueryAgreesWithValuingEveryPortfolio() throws Exception {
        AccountResponse winner = createAccount();
        AccountResponse loser = createAccount();
        AccountResponse mixed = createAccount();
        createAccount();
        quotes.update(new Quote("AAPL", new BigDecimal("100.00"), Instant.now()));
        quotes.update(new Quote("MSFT", new BigDecimal("50.00"), Instant.now()));
        try {
            buy(winner, "AAPL", 7);
            buy(loser, "MSFT", 9);
            buy(mixed, "AAPL", 3);
            buy(mixed, "MSFT", 5);
            // prices with a third decimal make the per-position rounding matter
            quotes.update(new Quote("AAPL", new BigDecimal("131.3337"), Instant.now()));
            quotes.update(new Quote("MSFT", new BigDecimal("41.0051"), Instant.now()));

            List<Entry> board = readList(get("/api/leaderboard"), new TypeReference<List<Entry>>() {});

            assertThat(board).hasSize((int) Math.min(20, accounts.count()));
            assertThat(board).usingRecursiveFieldByFieldElementComparatorIgnoringFields()
                    .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                    .containsExactlyElementsOf(oneByOne());
            assertThat(board).isSortedAccordingTo(
                    Comparator.comparing(Entry::returnPercent).reversed().thenComparing(Entry::accountId));
        } finally {
            quotes.update(new Quote("AAPL", new BigDecimal("100.00"), Instant.now()));
            quotes.update(new Quote("MSFT", new BigDecimal("50.00"), Instant.now()));
        }
    }

    @Test
    void anAccountThatGainedShowsItsReturn() throws Exception {
        AccountResponse account = createAccount();
        quotes.update(new Quote("AAPL", new BigDecimal("100.00"), Instant.now()));
        try {
            buy(account, "AAPL", 10);
            // 10 shares bought at 100 and now worth 1,000 each: the top of any board
            quotes.update(new Quote("AAPL", new BigDecimal("1000.00"), Instant.now()));
            List<Entry> board = readList(get("/api/leaderboard"), new TypeReference<List<Entry>>() {});
            Entry mine = board.stream().filter(e -> e.accountId().equals(account.id())).findFirst().orElseThrow();
            assertThat(mine.username()).isEqualTo(account.username());
            assertThat(mine.totalValue()).isEqualByComparingTo("10000.00");
            assertThat(mine.returnPercent()).isEqualByComparingTo("900.00");
        } finally {
            quotes.update(new Quote("AAPL", new BigDecimal("100.00"), Instant.now()));
        }
    }

    private List<Entry> oneByOne() {
        return accounts.findAll().stream().map(a -> portfolios.portfolio(a.getId()))
                .map(LeaderboardApiTest::entry)
                .sorted(Comparator.comparing(Entry::returnPercent).reversed().thenComparing(Entry::accountId))
                .limit(20).toList();
    }

    private static Entry entry(PortfolioResponse p) {
        BigDecimal percent = p.totalPnl().multiply(new BigDecimal("100"))
                .divide(p.startingCash(), 2, RoundingMode.HALF_UP);
        return new Entry(p.accountId(), p.username(), p.totalValue(), percent);
    }

    private void buy(AccountResponse account, String symbol, int quantity) {
        assertThat(post(orders(account), "{\"symbol\":\"" + symbol + "\",\"side\":\"BUY\",\"quantity\":" + quantity + "}"))
                .hasStatus(HttpStatus.CREATED);
    }
}
