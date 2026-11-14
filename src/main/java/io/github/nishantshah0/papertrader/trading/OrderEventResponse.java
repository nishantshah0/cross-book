package io.github.nishantshah0.papertrader.trading;

import io.github.nishantshah0.papertrader.trading.TradeResponse.Counterparty;
import java.math.BigDecimal;
import java.time.Instant;

// orderId is null only for an order refused before it was placed; the fill fields and
// counterparty are set only on PARTIALLY_FILLED and FILLED events
public record OrderEventResponse(long seq, Instant occurredAt, OrderEvent.Type type, Long orderId, long accountId,
        String symbol, OrderSide side, OrderType orderType, int quantity, BigDecimal limitPrice, int filledQuantity,
        Long tradeId, Integer fillQuantity, BigDecimal fillPrice, Counterparty counterparty,
        Long counterpartyOrderId, String reason) {

    public static OrderEventResponse from(OrderEvent event) {
        Counterparty counterparty = event.getTradeId() == null ? null
                : event.getCounterpartyOrderId() == null ? Counterparty.HOUSE : Counterparty.USER;
        return new OrderEventResponse(event.getSeq(), event.getOccurredAt(), event.getType(), event.getOrderId(),
                event.getAccountId(), event.getSymbol(), event.getSide(), event.getOrderType(), event.getQuantity(),
                event.getLimitPrice(), event.getFilledQuantity(), event.getTradeId(), event.getFillQuantity(),
                event.getFillPrice(), counterparty, event.getCounterpartyOrderId(), event.getReason());
    }
}
