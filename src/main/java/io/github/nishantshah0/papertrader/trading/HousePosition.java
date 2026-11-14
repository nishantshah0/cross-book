package io.github.nishantshah0.papertrader.trading;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

// The house's inventory and cash in one symbol. Unlike a user, the house may be short
// and may be out of pocket: it starts at zero and takes the other side of every fill
// that no user takes. Only the symbol's matcher thread writes this row.
@Entity
@Table(name = "house_positions")
public class HousePosition {

    @Id
    @Column(length = 10)
    private String symbol;

    @Column(nullable = false)
    private long quantity;

    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal cash;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected HousePosition() {
    }

    HousePosition(String symbol) {
        this.symbol = symbol;
        this.quantity = 0;
        this.cash = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.updatedAt = Instant.now();
    }

    // the house takes the opposite side of a user's fill: it sells what a user buys
    void takeOtherSide(OrderSide userSide, int shares, BigDecimal notional) {
        if (userSide == OrderSide.BUY) {
            quantity -= shares;
            cash = cash.add(notional);
        } else {
            quantity += shares;
            cash = cash.subtract(notional);
        }
        updatedAt = Instant.now();
    }

    public String getSymbol() {
        return symbol;
    }

    public long getQuantity() {
        return quantity;
    }

    public BigDecimal getCash() {
        return cash;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
