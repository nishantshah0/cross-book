package io.github.nishantshah0.papertrader.trading;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

// One entry in the order event log. Rows are only ever inserted: every field is fixed
// when the event is written, and nothing updates or deletes them. An event takes its
// time from the record it describes, so the log and the books always agree on when.
@Entity
@Table(name = "order_events")
public class OrderEvent {

    public enum Type {
        PLACED, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED
    }

    private static final int MAX_REASON = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(updatable = false)
    private Long seq;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private Type type;

    @Column(name = "order_id", updatable = false)
    private Long orderId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Column(nullable = false, updatable = false, length = 10)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 4)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, updatable = false, length = 6)
    private OrderType orderType;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "limit_price", updatable = false, precision = 18, scale = 4)
    private BigDecimal limitPrice;

    @Column(name = "filled_quantity", nullable = false, updatable = false)
    private int filledQuantity;

    @Column(name = "trade_id", updatable = false)
    private Long tradeId;

    @Column(name = "fill_quantity", updatable = false)
    private Integer fillQuantity;

    @Column(name = "fill_price", updatable = false, precision = 18, scale = 4)
    private BigDecimal fillPrice;

    @Column(name = "counterparty_order_id", updatable = false)
    private Long counterpartyOrderId;

    @Column(updatable = false, length = MAX_REASON)
    private String reason;

    protected OrderEvent() {
    }

    private OrderEvent(Type type, Order order, String reason, Instant at) {
        this.occurredAt = at;
        this.type = type;
        this.orderId = order.getId();
        this.accountId = order.getAccountId();
        this.symbol = order.getSymbol();
        this.side = order.getSide();
        this.orderType = order.getType();
        this.quantity = order.getQuantity();
        this.limitPrice = order.getLimitPrice();
        this.filledQuantity = order.getFilledQuantity();
        this.reason = truncate(reason);
    }

    static OrderEvent placed(Order order) {
        return new OrderEvent(Type.PLACED, order, null, order.getCreatedAt());
    }

    // written after the order and trade have both recorded the fill
    static OrderEvent filled(Order order, Trade trade) {
        OrderEvent event = new OrderEvent(order.getStatus() == OrderStatus.FILLED ? Type.FILLED
                : Type.PARTIALLY_FILLED, order, null, trade.getExecutedAt());
        event.tradeId = trade.getId();
        event.fillQuantity = trade.getQuantity();
        event.fillPrice = trade.getPrice();
        event.counterpartyOrderId = trade.getCounterpartyOrderId();
        return event;
    }

    // written after the order has moved to its final state, which stamps updatedAt
    static OrderEvent cancelled(Order order, String reason) {
        return new OrderEvent(Type.CANCELLED, order, reason, order.getUpdatedAt());
    }

    static OrderEvent rejected(Order order, String reason) {
        return new OrderEvent(Type.REJECTED, order, reason, order.getUpdatedAt());
    }

    // an order refused before it was placed, so there is no order row to point at
    static OrderEvent refused(Long accountId, String symbol, PlaceOrderRequest request, String reason, Instant at) {
        OrderEvent event = new OrderEvent();
        event.occurredAt = at;
        event.type = Type.REJECTED;
        event.accountId = accountId;
        event.symbol = symbol;
        event.side = request.side();
        event.orderType = request.typeOrMarket();
        event.quantity = request.quantity();
        event.limitPrice = request.limitPrice();
        event.filledQuantity = 0;
        event.reason = truncate(reason);
        return event;
    }

    private static String truncate(String reason) {
        return reason == null || reason.length() <= MAX_REASON ? reason : reason.substring(0, MAX_REASON);
    }

    public Long getSeq() {
        return seq;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Type getType() {
        return type;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getSymbol() {
        return symbol;
    }

    public OrderSide getSide() {
        return side;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getLimitPrice() {
        return limitPrice;
    }

    public int getFilledQuantity() {
        return filledQuantity;
    }

    public Long getTradeId() {
        return tradeId;
    }

    public Integer getFillQuantity() {
        return fillQuantity;
    }

    public BigDecimal getFillPrice() {
        return fillPrice;
    }

    public Long getCounterpartyOrderId() {
        return counterpartyOrderId;
    }

    public String getReason() {
        return reason;
    }
}
