package io.github.nishantshah0.papertrader.portfolio;

import io.github.nishantshah0.papertrader.trading.Marks;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// One query ranks every account, so the cost no longer grows with a round trip per
// account. Prices live in memory, so the marks (see Marks) go in as two arrays; a
// position in a symbol with no mark is valued at its average cost, as the portfolio page does.
@RestController
class LeaderboardController {

    static final int SIZE = 20;

    // each position's value rounds to cents before it is summed, and the return rounds
    // to two places before ranking, so the numbers match PortfolioService exactly
    private static final String SQL = """
            with marks (symbol, price) as (
                select * from unnest(?::varchar[], ?::numeric[])
            ), totals as (
                select a.id, u.username, a.starting_cash,
                       a.cash + coalesce(sum(round(p.quantity * coalesce(m.price, p.avg_cost), 2)), 0) as total_value
                from accounts a
                join users u on u.id = a.user_id
                left join positions p on p.account_id = a.id and p.quantity > 0
                left join marks m on m.symbol = p.symbol
                group by a.id, u.username, a.starting_cash, a.cash
            )
            select id, username, total_value,
                   round((total_value - starting_cash) * 100 / starting_cash, 2) as return_percent
            from totals
            order by return_percent desc, id
            limit ?
            """;

    private final JdbcTemplate jdbc;
    private final Marks marks;

    LeaderboardController(JdbcTemplate jdbc, Marks marks) {
        this.jdbc = jdbc;
        this.marks = marks;
    }

    public record Entry(Long accountId, String username, BigDecimal totalValue, BigDecimal returnPercent) {}

    @GetMapping("/api/leaderboard")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Entry> leaderboard() {
        Map<String, BigDecimal> latest = marks.all();
        String[] symbols = latest.keySet().toArray(String[]::new);
        BigDecimal[] prices = latest.values().toArray(BigDecimal[]::new);
        return jdbc.query(SQL, statement -> {
            statement.setArray(1, statement.getConnection().createArrayOf("varchar", symbols));
            statement.setArray(2, statement.getConnection().createArrayOf("numeric", prices));
            statement.setInt(3, SIZE);
        }, (row, n) -> new Entry(row.getLong("id"), row.getString("username"),
                row.getBigDecimal("total_value"), row.getBigDecimal("return_percent")));
    }
}
