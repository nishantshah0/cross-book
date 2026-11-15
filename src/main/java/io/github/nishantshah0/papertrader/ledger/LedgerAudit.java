package io.github.nishantshah0.papertrader.ledger;

import io.github.nishantshah0.papertrader.ledger.LedgerReport.HouseBooks;
import io.github.nishantshah0.papertrader.ledger.LedgerReport.Totals;
import io.github.nishantshah0.papertrader.ledger.LedgerReport.Violation;
import io.github.nishantshah0.papertrader.trading.Marks;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// Checks that the books balance: no cash or shares were created or destroyed, every
// balance can be rebuilt from the trades that produced it, and the order event log
// agrees with both. Each check is a query for the rows that break a rule, so an empty
// result means the rule holds.
//
// All checks read one snapshot. Every trade commits atomically, so a snapshot taken in
// the middle of trading is still a consistent one, and the audit can run while the
// matchers keep working.
@Service
public class LedgerAudit {

    static final int MAX_VIOLATIONS = 100;

    // a fill's notional, rounded per trade to the cent the same way settlement rounds it
    private static final String NOTIONAL = "round(price * quantity, 2)";

    private final JdbcTemplate jdbc;
    private final Marks marks;
    private final Clock clock;

    LedgerAudit(JdbcTemplate jdbc, Marks marks, Clock clock) {
        this.jdbc = jdbc;
        this.marks = marks;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LedgerReport run() {
        List<Violation> violations = new ArrayList<>();
        Totals totals = totals();

        // the two conservation laws
        if (totals.userCash().add(totals.houseCash()).compareTo(totals.startingCash()) != 0) {
            violations.add(new Violation("cash-conserved", "users hold " + totals.userCash() + " and the house "
                    + totals.houseCash() + ", but accounts started with " + totals.startingCash()));
        }
        check(violations, "shares-conserved", """
                select coalesce(u.symbol, h.symbol) as symbol, coalesce(u.held, 0) as held,
                       coalesce(h.quantity, 0) as house
                from (select symbol, sum(quantity) as held from positions group by symbol) u
                full join house_positions h on h.symbol = u.symbol
                where coalesce(u.held, 0) + coalesce(h.quantity, 0) <> 0
                """, row -> row.getString("symbol") + ": users hold " + row.getLong("held")
                        + " but the house is at " + row.getLong("house"));

        // every balance agrees with the trades behind it
        check(violations, "account-cash", """
                select a.id, a.cash, a.starting_cash - coalesce(t.spent, 0) + coalesce(t.received, 0) as expected
                from accounts a
                left join (select account_id,
                                  sum(case when side = 'BUY' then %1$s else 0 end) as spent,
                                  sum(case when side = 'SELL' then %1$s else 0 end) as received
                           from trades group by account_id) t on t.account_id = a.id
                where a.cash <> a.starting_cash - coalesce(t.spent, 0) + coalesce(t.received, 0)
                order by a.id
                """.formatted(NOTIONAL), row -> "account " + row.getLong("id") + " has " + row.getBigDecimal("cash")
                        + " but its trades leave " + row.getBigDecimal("expected"));
        check(violations, "account-shares", """
                select coalesce(p.account_id, t.account_id) as account_id, coalesce(p.symbol, t.symbol) as symbol,
                       coalesce(p.quantity, 0) as held, coalesce(t.net, 0) as traded
                from positions p
                full join (select account_id, symbol, sum(case when side = 'BUY' then quantity else -quantity end) as net
                           from trades group by account_id, symbol) t
                       on t.account_id = p.account_id and t.symbol = p.symbol
                where coalesce(p.quantity, 0) <> coalesce(t.net, 0)
                order by 1, 2
                """, row -> "account " + row.getLong("account_id") + " holds " + row.getLong("held") + " "
                        + row.getString("symbol") + " but its trades net to " + row.getLong("traded"));
        check(violations, "house-books", """
                select coalesce(h.symbol, t.symbol) as symbol,
                       coalesce(h.quantity, 0) as quantity, coalesce(t.quantity, 0) as expected_quantity,
                       coalesce(h.cash, 0) as cash, coalesce(t.cash, 0) as expected_cash
                from house_positions h
                full join (select symbol,
                                  sum(case when side = 'BUY' then -quantity else quantity end) as quantity,
                                  sum(case when side = 'BUY' then 1 else -1 end * %s) as cash
                           from trades where counterparty_order_id is null group by symbol) t
                       on t.symbol = h.symbol
                where coalesce(h.quantity, 0) <> coalesce(t.quantity, 0) or coalesce(h.cash, 0) <> coalesce(t.cash, 0)
                order by 1
                """.formatted(NOTIONAL), row -> row.getString("symbol") + ": the house books "
                        + row.getLong("quantity") + " shares and " + row.getBigDecimal("cash")
                        + ", its trades give " + row.getLong("expected_quantity") + " and "
                        + row.getBigDecimal("expected_cash"));

        // every fill belongs to its order and respects it
        check(violations, "order-filled-quantity", """
                select o.id, o.filled_quantity, coalesce(t.filled, 0) as traded
                from orders o
                left join (select order_id, sum(quantity) as filled from trades group by order_id) t on t.order_id = o.id
                where o.filled_quantity <> coalesce(t.filled, 0)
                order by o.id
                """, row -> "order " + row.getLong("id") + " says " + row.getInt("filled_quantity")
                        + " filled but has trades for " + row.getLong("traded"));
        check(violations, "trade-matches-order", """
                select t.id, t.order_id
                from trades t join orders o on o.id = t.order_id
                where t.account_id <> o.account_id or t.symbol <> o.symbol or t.side <> o.side
                   or (o.order_type = 'LIMIT' and o.side = 'BUY' and t.price > o.limit_price)
                   or (o.order_type = 'LIMIT' and o.side = 'SELL' and t.price < o.limit_price)
                order by t.id
                """, row -> "trade " + row.getLong("id") + " does not match order " + row.getLong("order_id")
                        + "'s account, symbol, side or limit");
        check(violations, "trades-paired", """
                select t.id, t.order_id, t.counterparty_order_id
                from trades t
                where t.counterparty_order_id is not null
                  and not exists (select 1 from trades m
                                  where m.order_id = t.counterparty_order_id
                                    and m.counterparty_order_id = t.order_id
                                    and m.symbol = t.symbol and m.side <> t.side
                                    and m.quantity = t.quantity and m.price = t.price)
                order by t.id
                """, row -> "trade " + row.getLong("id") + " on order " + row.getLong("order_id")
                        + " has no matching trade on order " + row.getLong("counterparty_order_id"));
        check(violations, "order-state", """
                select id, status, order_type from orders
                where (status = 'OPEN' and order_type = 'MARKET')
                   or (status = 'OPEN' and filled_quantity >= quantity)
                order by id
                """, row -> "order " + row.getLong("id") + " is a " + row.getString("order_type")
                        + " order left " + row.getString("status"));

        // the event log tells the same story as the orders and trades
        check(violations, "events-match-trades", """
                select t.id, t.order_id, e.seq
                from trades t
                left join order_events e on e.trade_id = t.id
                where e.seq is null or e.order_id <> t.order_id or e.fill_quantity <> t.quantity
                   or e.fill_price <> t.price or e.counterparty_order_id is distinct from t.counterparty_order_id
                order by t.id
                """, row -> "trade " + row.getLong("id") + " on order " + row.getLong("order_id")
                        + (row.getObject("seq") == null ? " has no fill event"
                                : " does not match fill event " + row.getLong("seq")));
        check(violations, "events-match-orders", """
                select o.id, o.status, o.filled_quantity, f.type as first_type, l.type as last_type,
                       l.filled_quantity as last_filled
                from orders o
                left join lateral (select type from order_events
                                   where order_id = o.id order by seq limit 1) f on true
                left join lateral (select type, filled_quantity from order_events
                                   where order_id = o.id order by seq desc limit 1) l on true
                where f.type is distinct from 'PLACED'
                   or l.filled_quantity is distinct from o.filled_quantity
                   or not ((o.status = 'OPEN' and l.type in ('PLACED', 'PARTIALLY_FILLED')) or o.status = l.type)
                order by o.id
                """, row -> "order " + row.getLong("id") + " is " + row.getString("status") + " with "
                        + row.getInt("filled_quantity") + " filled, but its events run from "
                        + row.getString("first_type") + " to " + row.getString("last_type"));

        // open orders are still backed by what they reserved
        check(violations, "reserved-cash", """
                select a.id, a.cash, r.reserved
                from accounts a
                join (select account_id, sum(round(limit_price * (quantity - filled_quantity), 2)) as reserved
                      from orders where status = 'OPEN' and order_type = 'LIMIT' and side = 'BUY'
                      group by account_id) r on r.account_id = a.id
                where r.reserved > a.cash
                order by a.id
                """, row -> "account " + row.getLong("id") + " reserves " + row.getBigDecimal("reserved")
                        + " for open buys but holds " + row.getBigDecimal("cash"));
        check(violations, "reserved-shares", """
                select r.account_id, r.symbol, coalesce(p.quantity, 0) as held, r.reserved
                from (select account_id, symbol, sum(quantity - filled_quantity) as reserved
                      from orders where status = 'OPEN' and order_type = 'LIMIT' and side = 'SELL'
                      group by account_id, symbol) r
                left join positions p on p.account_id = r.account_id and p.symbol = r.symbol
                where r.reserved > coalesce(p.quantity, 0)
                order by 1, 2
                """, row -> "account " + row.getLong("account_id") + " has open sells for " + row.getLong("reserved")
                        + " " + row.getString("symbol") + " but holds " + row.getLong("held"));

        boolean truncated = violations.size() > MAX_VIOLATIONS;
        List<Violation> reported = truncated ? List.copyOf(violations.subList(0, MAX_VIOLATIONS)) : violations;
        return new LedgerReport(violations.isEmpty(), clock.instant(), totals, house(), reported, truncated);
    }

    private Totals totals() {
        return jdbc.queryForObject("""
                select (select count(*) from accounts) as accounts,
                       (select count(*) from trades) as trades,
                       (select coalesce(sum(starting_cash), 0) from accounts) as starting_cash,
                       (select coalesce(sum(cash), 0) from accounts) as user_cash,
                       (select coalesce(sum(cash), 0) from house_positions) as house_cash
                """, (row, n) -> new Totals(row.getLong("accounts"), row.getLong("trades"),
                        row.getBigDecimal("starting_cash"), row.getBigDecimal("user_cash"),
                        row.getBigDecimal("house_cash")));
    }

    // the house's position in each symbol, at its mark (see Marks): its cash plus what
    // its shares are worth is what it has made from the spread so far
    private List<HouseBooks> house() {
        return jdbc.query("select symbol, quantity, cash from house_positions order by symbol", (row, n) -> {
            String symbol = row.getString("symbol");
            long quantity = row.getLong("quantity");
            BigDecimal cash = row.getBigDecimal("cash");
            BigDecimal mark = marks.mark(symbol).orElse(null);
            BigDecimal profit = mark == null ? null
                    : cash.add(mark.multiply(BigDecimal.valueOf(quantity))).setScale(2, RoundingMode.HALF_UP);
            return new HouseBooks(symbol, quantity, cash, mark, profit);
        });
    }

    private void check(List<Violation> violations, String name, String sql, Describer describe) {
        jdbc.query(sql + " limit " + (MAX_VIOLATIONS + 1), (ResultSet row) -> {
            violations.add(new Violation(name, describe.describe(row)));
        });
    }

    @FunctionalInterface
    private interface Describer {
        String describe(ResultSet row) throws SQLException;
    }
}
