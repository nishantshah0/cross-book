package io.github.nishantshah0.papertrader.bots;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// Which account is which bot (see the answer_key migration). Written once when a run
// sets up its accounts; read only for grading, never by a detector.
@Repository
public class AnswerKey {

    public record Entry(long accountId, String username, String bot, BotKind kind, boolean cheater) {
    }

    private final JdbcTemplate jdbc;

    AnswerKey(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void add(Bot bot) {
        jdbc.update("insert into answer_key (account_id, bot, kind, cheater) values (?, ?, ?, ?)", bot.accountId,
                bot.name, bot.kind.name(), bot.kind.cheater);
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
