package io.github.nishantshah0.papertrader.surveillance;

import java.util.List;

// One account a detector thinks is manipulating the market. score ranks alerts (higher
// is more suspicious). orders are the exact orders it holds against the account, and
// evidence gives the event-log sequence numbers behind them, so a case can cite lines
// of the log. A baseline that only judges accounts leaves both empty.
public record Alert(String detector, long accountId, String username, double score, String summary,
        List<Long> orders, List<Long> evidence) {
}
