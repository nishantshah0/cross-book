package io.github.nishantshah0.papertrader;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nishantshah0.papertrader.account.AccountResponse;
import io.github.nishantshah0.papertrader.ledger.LedgerReport;
import io.github.nishantshah0.papertrader.ledger.LedgerReport.HouseBooks;
import io.github.nishantshah0.papertrader.ledger.LedgerReport.Violation;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

// The house trades at exactly the quote here, so its books move by the notional of each
// fill. Other tests in this context move the quotes, so nothing here assumes a price.
// The tests that tamper with the books put them back before the
// after-each audit in ApiTestSupport runs.
class LedgerAuditTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theHouseTakesTheOtherSideOfEveryFillItMakes() throws Exception {
        var account = createAccount();
        HouseBooks before = house("AAPL");

        post(orders(account), "{\"symbol\":\"AAPL\",\"side\":\"BUY\",\"quantity\":3}");
        HouseBooks afterBuy = house("AAPL");
        assertThat(afterBuy.quantity() - before.quantity()).isEqualTo(-3);
        // whatever the account paid, the house took in
        assertThat(afterBuy.cash().subtract(before.cash())).isEqualByComparingTo(spent(account));

        post(orders(account), "{\"symbol\":\"AAPL\",\"side\":\"SELL\",\"quantity\":1}");
        HouseBooks afterSell = house("AAPL");
        assertThat(afterSell.quantity() - before.quantity()).isEqualTo(-2);
        assertThat(afterSell.cash().subtract(before.cash())).isEqualByComparingTo(spent(account));

        // with no spread the house neither makes nor loses anything at the quote
        assertThat(afterSell.profit()).isEqualByComparingTo(before.profit());
        assertThat(ledger.run().balanced()).isTrue();
    }

    @Test
    void cashThatChangesOutsideATradeIsCaught() throws Exception {
        var account = createAccount();
        post(orders(account), "{\"symbol\":\"MSFT\",\"side\":\"BUY\",\"quantity\":2}");
        // other tests move the MSFT quote, so read what the trade actually left
        BigDecimal cash = portfolio(account).cash();

        jdbc.update("update accounts set cash = cash + 0.01 where id = ?", account.id());
        try {
            LedgerReport report = ledger.run();

            assertThat(report.balanced()).isFalse();
            assertThat(report.violations()).extracting(Violation::check)
                    .containsExactlyInAnyOrder("cash-conserved", "account-cash");
            assertThat(report.violations()).filteredOn(v -> v.check().equals("account-cash"))
                    .singleElement().extracting(Violation::detail).asString()
                    .contains("account " + account.id() + " has " + cash.add(new BigDecimal("0.01"))
                            + " but its trades leave " + cash);
        } finally {
            jdbc.update("update accounts set cash = cash - 0.01 where id = ?", account.id());
        }
    }

    @Test
    void sharesThatAppearWithoutATradeAreCaught() throws Exception {
        var account = createAccount();
        post(orders(account), "{\"symbol\":\"MSFT\",\"side\":\"BUY\",\"quantity\":2}");

        jdbc.update("update positions set quantity = quantity + 5 where account_id = ? and symbol = 'MSFT'",
                account.id());
        try {
            assertThat(ledger.run().violations()).extracting(Violation::check)
                    .containsExactlyInAnyOrder("shares-conserved", "account-shares");
        } finally {
            jdbc.update("update positions set quantity = quantity - 5 where account_id = ? and symbol = 'MSFT'",
                    account.id());
        }
    }

    @Test
    void theEndpointReportsTheTotalsAndTheHousesBooks() throws Exception {
        var account = createAccount();
        post(orders(account), "{\"symbol\":\"MSFT\",\"side\":\"BUY\",\"quantity\":1}");

        var response = get("/api/ledger");

        assertThat(response).hasStatus(HttpStatus.OK);
        LedgerReport report = read(response, LedgerReport.class);
        assertThat(report.balanced()).isTrue();
        assertThat(report.violations()).isEmpty();
        assertThat(report.totals().userCash().add(report.totals().houseCash()))
                .isEqualByComparingTo(report.totals().startingCash());
        assertThat(report.house()).extracting(HouseBooks::symbol).contains("MSFT");
    }

    // what the account has paid out, net, since it opened with 1000
    private BigDecimal spent(AccountResponse account) throws Exception {
        return new BigDecimal("1000.00").subtract(portfolio(account).cash());
    }

    private HouseBooks house(String symbol) {
        return ledger.run().house().stream()
                .filter(books -> books.symbol().equals(symbol))
                .findFirst()
                .orElse(new HouseBooks(symbol, 0, BigDecimal.ZERO, null, BigDecimal.ZERO));
    }
}
