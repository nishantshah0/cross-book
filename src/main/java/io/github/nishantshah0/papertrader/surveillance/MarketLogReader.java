package io.github.nishantshah0.papertrader.surveillance;

import io.github.nishantshah0.papertrader.trading.OrderSide;
import io.github.nishantshah0.papertrader.trading.OrderType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// Rebuilds every order's story from the event log, in sequence order.
@Component
public class MarketLogReader {

    private final JdbcTemplate jdbc;

    MarketLogReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MarketLog read() {
        Map<Long, LoggedOrder> orders = new LinkedHashMap<>(); // keeps placing order
        jdbc.query("""
                select seq, occurred_at, type, order_id, account_id, symbol, side, order_type, quantity,
                       limit_price, filled_quantity
                from order_events
                where order_id is not null
                order by seq
                """, row -> {
            long id = row.getLong("order_id");
            String type = row.getString("type");
            var at = row.getTimestamp("occurred_at").toInstant();
            if (type.equals("PLACED")) {
                orders.put(id, new LoggedOrder(id, row.getLong("account_id"), row.getString("symbol"),
                        OrderSide.valueOf(row.getString("side")), OrderType.valueOf(row.getString("order_type")),
                        row.getInt("quantity"), row.getBigDecimal("limit_price"), row.getLong("seq"), at, null, null,
                        0));
            } else {
                int filled = row.getInt("filled_quantity");
                orders.computeIfPresent(id, (key, order) -> order.fill(filled, at, type));
            }
        });
        Map<Long, String> usernames = new HashMap<>();
        jdbc.query("select a.id, u.username from accounts a join users u on u.id = a.user_id",
                row -> { usernames.put(row.getLong(1), row.getString(2)); });
        return new MarketLog(new ArrayList<>(orders.values()), usernames);
    }
}
