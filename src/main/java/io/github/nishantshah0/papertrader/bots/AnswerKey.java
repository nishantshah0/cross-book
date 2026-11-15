package io.github.nishantshah0.papertrader.bots;

import io.github.nishantshah0.papertrader.trading.Order;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// Which account is which bot (see the answer_key migration). Written once when a run
// sets up its accounts; read only for grading, never by a detector.
@Repository
public class AnswerKey {

    public record Entry(long accountId, String username, String bot, BotKind kind, boolean cheater) {
    }

    // one of a cheater's orders: SPOOF for an order it never meant to trade, REAL for the
    // one it did; episode numbers the cheater's schemes, from 1
    public record LabelledOrder(long orderId, long accountId, String bot, int episode, String role) {
    }

    private final JdbcTemplate jdbc;

    AnswerKey(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void add(Bot bot) {
        jdbc.update("insert into answer_key (account_id, bot, kind, cheater) values (?, ?, ?, ?)", bot.accountId,
                bot.name, bot.kind.name(), bot.cheater());
    }

    void addOrder(Order order, int episode, String role) {
        jdbc.update("insert into answer_key_orders (order_id, account_id, episode, role) values (?, ?, ?, ?)",
                order.getId(), order.getAccountId(), episode, role);
    }

    public List<LabelledOrder> orders() {
        return jdbc.query("""
                select o.order_id, o.account_id, k.bot, o.episode, o.role
                from answer_key_orders o
                join answer_key k on k.account_id = o.account_id
                order by o.order_id
                """, (row, n) -> new LabelledOrder(row.getLong("order_id"), row.getLong("account_id"),
                row.getString("bot"), row.getInt("episode"), row.getString("role")));
    }

    public List<Entry> entries() {
        return jdbc.query("""
                select k.account_id, u.username, k.bot, k.kind, k.cheater
                from answer_key k
                join accounts a on a.id = k.account_id
                join users u on u.id = a.user_id
                order by k.account_id
                """, (row, n) -> new Entry(row.getLong("account_id"), row.getString("username"),
                row.getString("bot"), BotKind.valueOf(row.getString("kind")), row.getBoolean("cheater")));
    }
}
