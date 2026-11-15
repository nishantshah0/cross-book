package io.github.nishantshah0.papertrader;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nishantshah0.papertrader.bots.AnswerKey;
import io.github.nishantshah0.papertrader.bots.BotKind;
import io.github.nishantshah0.papertrader.bots.BotRunReport;
import io.github.nishantshah0.papertrader.bots.BotRunRequest;
import io.github.nishantshah0.papertrader.bots.BotRunner;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

// A whole bot market: noise traders and market makers in book mode, on the simulated
// clock, with a walking demo feed. The same request must write the same rows, the
// market must actually work (bots trade with each other near the feed price), and
// nothing about an account outside the answer key may say what kind of bot it is.
@SpringBootTest(properties = {
        "papertrader.market.mode=book",
        "papertrader.clock.mode=simulated",
        "papertrader.clock.start=2026-01-05T14:30:00Z",
        "papertrader.feed.mode=demo",
        "papertrader.feed.interval-ms=15000",
        "papertrader.starting-cash=100000.00",
        "papertrader.seed-quotes.BTA=100.00",
        "papertrader.seed-quotes.BTB=40.00",
        "papertrader.seed-quotes.BTC=250.00",
        "papertrader.seed-quotes.BTD=12.50"
})
class BotMarketTest extends ApiTestSupport {

    private static final List<String> SYMBOLS = List.of("BTA", "BTB", "BTC", "BTD");
    private static final int NOISE = 30, MAKERS = 4, TICKS = 40;

    @Autowired
    BotRunner runner;

    @Autowired
    AnswerKey answerKey;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theSameRequestReplaysExactly() {
        BotRunReport first = runner.run(request(11));
        String firstRows = digest();
        BotRunReport second = runner.run(request(11));

        assertThat(digest()).isEqualTo(firstRows);
        assertThat(withoutTiming(second)).isEqualTo(withoutTiming(first));
        assertThat(first.actions()).isGreaterThan(1_000); // enough happened for that to mean something
    }

    @Test
    void aDifferentSeedGivesADifferentMarket() {
        runner.run(request(11));
        String eleven = digest();
        runner.run(request(12));
        assertThat(digest()).isNotEqualTo(eleven);
    }

    @Test
    void theBotsMakeAMarketAroundTheFeedPrice() throws Exception {
        var result = post("/api/bots/runs", """
                {"seed": 5, "ticks": %d, "noiseTraders": %d, "marketMakers": %d,
                 "symbols": ["bta", "btb", "btc", "btd"]}""".formatted(TICKS, NOISE, MAKERS));
        assertThat(result).hasStatus(HttpStatus.OK);
        BotRunReport report = read(result, BotRunReport.class);

        assertThat(report.ledgerBalanced()).isTrue();
        assertThat(report.endedAt()).isEqualTo(report.startedAt().plusSeconds(15L * TICKS));
        assertThat(report.trades()).as("fills between bots").isGreaterThan(300);
        assertThat((double) report.refused() / (report.ordersPlaced() + report.refused()))
                .as("share of orders refused").isLessThan(0.05);
        for (var symbol : report.prices()) {
            assertThat(symbol.trades()).as(symbol.symbol() + " trades").isGreaterThan(30);
            assertThat(Math.abs(symbol.gapBps())).as(symbol.symbol() + " last trade vs feed, bps").isLessThan(60);
        }
        // the house only ever sold the starting shares, all before the clock started
        assertThat(jdbc.queryForObject("""
                select count(*) from trades
                where counterparty_order_id is null and executed_at <> ?::timestamptz
                """, Long.class, report.startedAt().toString())).isZero();
        // and bots acted all through each tick, not bunched on the tick times: of the
        // events after the start, hardly any fall exactly on the 15-second grid
        assertThat(jdbc.queryForObject("""
                select avg(case when extract(epoch from occurred_at - ?::timestamptz)::numeric % 15 = 0
                                then 1.0 else 0.0 end)
                from order_events where occurred_at > ?::timestamptz
                """, Double.class, report.startedAt().toString(), report.startedAt().toString())).isLessThan(0.02);
    }

    @Test
    void onlyTheAnswerKeySaysWhoIsWho() {
        runner.run(request(3));
        List<AnswerKey.Entry> key = answerKey.entries();

        assertThat(key).hasSize(NOISE + MAKERS);
        assertThat(key).filteredOn(e -> e.kind() == BotKind.MARKET_MAKER).hasSize(MAKERS);
        assertThat(key).noneMatch(AnswerKey.Entry::cheater);
        assertThat(key).extracting(AnswerKey.Entry::bot).doesNotHaveDuplicates();
        // names say nothing and run in account order, so they carry no more than the id
        for (int i = 0; i < key.size(); i++) {
            assertThat(key.get(i).username()).isEqualTo("trader_%04d".formatted(i + 1));
        }
        // and the makers did not join as one block, at the start or the end
        List<Integer> makers = java.util.stream.IntStream.range(0, key.size())
                .filter(i -> key.get(i).kind() == BotKind.MARKET_MAKER).boxed().toList();
        assertThat(makers).isNotEqualTo(List.of(0, 1, 2, 3))
                .isNotEqualTo(List.of(key.size() - 4, key.size() - 3, key.size() - 2, key.size() - 1));
        assertThat(jdbc.queryForObject("select count(*) from accounts", Long.class)).isEqualTo(NOISE + MAKERS);
    }

    private static BotRunRequest request(long seed) {
        return new BotRunRequest(seed, TICKS, NOISE, MAKERS, SYMBOLS);
    }

    private static BotRunReport withoutTiming(BotRunReport r) {
        return new BotRunReport(r.seed(), r.ticks(), r.noiseTraders(), r.marketMakers(), r.symbols(), r.startedAt(),
                r.endedAt(), r.actions(), r.ordersPlaced(), r.refused(), r.cancelled(), r.cancelsTooLate(),
                r.trades(), r.prices(), r.ledgerBalanced(), 0, 0);
    }

    // every row a run writes, hashed
    private String digest() {
        return jdbc.queryForObject("""
                select md5(
                  (select string_agg(concat_ws('|', seq, occurred_at, type, order_id, account_id, symbol, side,
                          order_type, quantity, limit_price, filled_quantity, trade_id, fill_quantity, fill_price,
                          counterparty_order_id, reason), E'\\n' order by seq) from order_events)
                  || (select string_agg(concat_ws('|', id, order_id, account_id, symbol, side, quantity, price,
                          counterparty_order_id, executed_at), E'\\n' order by id) from trades)
                  || (select string_agg(concat_ws('|', id, cash, version), E'\\n' order by id) from accounts)
                  || (select string_agg(concat_ws('|', account_id, bot, kind), E'\\n' order by account_id)
                          from answer_key))
                """, String.class);
    }
}
