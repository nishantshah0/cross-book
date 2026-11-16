package io.github.nishantshah0.papertrader.surveillance;

import io.github.nishantshah0.papertrader.trading.OrderSide;
import io.github.nishantshah0.papertrader.trading.OrderType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

// One order as the event log tells it: placed, maybe filled in parts, and how it ended
// (FILLED, CANCELLED or REJECTED; null while it is still open). firstSeq is its PLACED
// event, so an alert can point at the exact line of the log.
public record LoggedOrder(long id, long accountId, String symbol, OrderSide side, OrderType type, int quantity,
        BigDecimal limitPrice, long firstSeq, Instant placedAt, Instant endedAt, String end, int filled) {

    public boolean cancelled() {
        return "CANCELLED".equals(end);
    }

    public Duration life() {
        return endedAt == null ? null : Duration.between(placedAt, endedAt);
    }

    LoggedOrder fill(int filledNow, Instant at, String type) {
        boolean done = !"PARTIALLY_FILLED".equals(type);
        return new LoggedOrder(id, accountId, symbol, side, this.type, quantity, limitPrice, firstSeq, placedAt,
                done ? at : null, done ? type : null, filledNow);
    }
}
