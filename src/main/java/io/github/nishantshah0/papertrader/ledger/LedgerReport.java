package io.github.nishantshah0.papertrader.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

// balanced is true when every check passed; violations lists what failed, capped at
// LedgerAudit.MAX_VIOLATIONS, with truncated set when there were more
public record LedgerReport(boolean balanced, Instant checkedAt, Totals totals, List<HouseBooks> house,
        List<Violation> violations, boolean truncated) {

    public record Totals(long accounts, long trades, BigDecimal startingCash, BigDecimal userCash,
            BigDecimal houseCash) {
    }

    // markPrice and profit are null for a symbol with no current quote
    public record HouseBooks(String symbol, long quantity, BigDecimal cash, BigDecimal markPrice,
            BigDecimal profit) {
    }

    public record Violation(String check, String detail) {
    }
}
