package io.github.nishantshah0.papertrader;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nishantshah0.papertrader.bots.AnswerKey;
import io.github.nishantshah0.papertrader.bots.BotKind;
import io.github.nishantshah0.papertrader.bots.BotRunReport;
import io.github.nishantshah0.papertrader.bots.BotRunRequest;
import io.github.nishantshah0.papertrader.bots.BotRunner;
import io.github.nishantshah0.papertrader.bots.SpoofTactics;
import io.github.nishantshah0.papertrader.grading.Grader;
import io.github.nishantshah0.papertrader.grading.Score;
import io.github.nishantshah0.papertrader.surveillance.Surveillance;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

// A whole bot market in book mode, on the simulated clock, with a walking demo feed.
// The same request must write the same rows, the honest market must actually work
// (bots trade with each other near the feed price), nothing about an account outside
// the answer key may say what kind of bot it is, and the spoofer must do what its
// labels say it did.
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
    private static final int NOISE = 30, MAKERS = 4, READERS = 6, VALUE = 4, SPOOFERS = 1, LOOKALIKES = 1, TICKS = 40;
    private static final int BOTS = NOISE + MAKERS + READERS + VALUE + SPOOFERS + LOOKALIKES;

    @Autowired
    BotRunner runner;

    @Autowired
    AnswerKey answerKey;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Surveillance surveillance;

    @Autowired
    Grader grader;

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
                {"seed": 5, "ticks": %d, "noiseTraders": %d, "marketMakers": %d, "bookReaders": 0, "valueTraders": 0, "spoofers": 0,
                 "lookalikes": 0, "symbols": ["bta", "btb", "btc", "btd"]}""".formatted(TICKS, NOISE, MAKERS));
        assertThat(result).hasStatus(HttpStatus.ACCEPTED);
        assertThat(result.getResponse().getHeader("Location")).startsWith("/api/jobs/");
        BotRunReport report = json.treeToValue(finished(result), BotRunReport.class);

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

        assertThat(key).hasSize(BOTS);
        assertThat(key).filteredOn(e -> e.kind() == BotKind.MARKET_MAKER).hasSize(MAKERS);
        assertThat(key).filteredOn(AnswerKey.Entry::cheater).extracting(AnswerKey.Entry::kind)
                .containsExactly(BotKind.SPOOFER);
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
        assertThat(jdbc.queryForObject("select count(*) from accounts", Long.class)).isEqualTo(BOTS);
        // value traders take what is mispriced and leave nothing resting at a stale price
        assertThat(jdbc.queryForObject("""
                select count(*) from orders o join answer_key k on k.account_id = o.account_id
                where k.kind = 'VALUE_TRADER' and o.status = 'OPEN'
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from orders o join answer_key k on k.account_id = o.account_id
                where k.kind = 'VALUE_TRADER' and o.order_type = 'LIMIT'
                """, Long.class)).isGreaterThan(10);
    }

    // Every order a spoofer places is labelled, and the labels describe layering: in each
    // episode, large fake orders on one side, at most one smaller real order on the
    // other, and the fakes pulled within seconds.
    @Test
    void theSpooferLayersFakeOrdersAndTheAnswerKeyLabelsEachOne() {
        BotRunReport report = runner.run(new BotRunRequest(21L, 80, NOISE, MAKERS, 10, VALUE, 2, 4, 2, SYMBOLS));
        var spoofers = answerKey.entries().stream().filter(e -> e.kind() == BotKind.SPOOFER)
                .map(AnswerKey.Entry::accountId).toList();
        assertThat(spoofers).hasSize(2);
        assertThat(answerKey.entries()).filteredOn(e -> e.kind() == BotKind.SPOOFER).allMatch(AnswerKey.Entry::cheater);

        record Labelled(long account, int episode, String role, String side, int quantity, String status,
                double lifeSeconds) {
        }
        List<Labelled> orders = jdbc.query("""
                select l.account_id, l.episode, l.role, o.side, o.quantity, o.status,
                       extract(epoch from o.updated_at - o.created_at)::float8 as life
                from answer_key_orders l join orders o on o.id = l.order_id
                """, (row, n) -> new Labelled(row.getLong(1), row.getInt(2), row.getString(3), row.getString(4),
                row.getInt(5), row.getString(6), row.getDouble(7)));
        assertThat(orders).extracting(Labelled::account).allMatch(spoofers::contains);
        // and nothing a spoofer placed after the start is unlabelled
        assertThat(jdbc.queryForObject("""
                select count(*) from orders o
                where o.account_id in (select account_id from answer_key where kind = 'SPOOFER')
                  and o.created_at > ?::timestamptz
                  and o.id not in (select order_id from answer_key_orders)
                """, Long.class, report.startedAt().toString())).isZero();

        var episodes = orders.stream().collect(java.util.stream.Collectors.groupingBy(
                o -> o.account() + "/" + o.episode()));
        assertThat(episodes).hasSizeGreaterThan(10);
        episodes.values().forEach(episode -> {
            var fakes = episode.stream().filter(o -> o.role().equals("SPOOF")).toList();
            var real = episode.stream().filter(o -> o.role().equals("REAL")).toList();
            assertThat(fakes).hasSizeBetween(1, 4);
            assertThat(real).hasSizeLessThanOrEqualTo(1);
            assertThat(fakes).extracting(Labelled::side).containsOnly(fakes.getFirst().side());
            // the fakes together dwarf the real order (the last fake ask can be cut short by
            // the shares the spoofer has left, so not every one is bigger on its own)
            real.forEach(r -> {
                assertThat(r.side()).isNotEqualTo(fakes.getFirst().side());
                assertThat(fakes.stream().mapToInt(Labelled::quantity).sum()).isGreaterThan(3 * r.quantity());
            });
        });
        var fakes = orders.stream().filter(o -> o.role().equals("SPOOF")).toList();
        var pulled = fakes.stream().filter(o -> o.status().equals("CANCELLED")).toList();
        assertThat((double) pulled.size() / fakes.size()).as("share of fakes cancelled").isGreaterThan(0.85);
        assertThat(pulled).allMatch(o -> o.lifeSeconds() >= 2 && o.lifeSeconds() <= 6.01);
        assertThat(report.kinds()).extracting(BotRunReport.KindSummary::kind).contains(BotKind.SPOOFER);
        // the report's spoofing summary agrees with the labels
        assertThat(report.spoofing().fakeOrders()).isEqualTo(fakes.size());
        assertThat(report.spoofing().episodes()).isEqualTo(episodes.size());
        assertThat(report.activity()).extracting(BotRunReport.KindActivity::kind)
                .contains(BotKind.SPOOFER, BotKind.LOOKALIKE, BotKind.NOISE);
        // every trade is between two bots at one price and one fair value, so what one kind
        // makes against fair value, others give up: the edges add up to zero, to the cent
        assertThat(report.activity().stream().map(BotRunReport.KindActivity::edge).reduce(BigDecimal.ZERO, BigDecimal::add)
                .abs()).isLessThanOrEqualTo(new BigDecimal("0.05"));
        assertThat(report.activity()).filteredOn(a -> a.kind() == BotKind.SPOOFER).singleElement()
                .satisfies(a -> assertThat(a.edgeBps()).isNotNull());
    }

    // Tactics change how a scheme runs, not what it is. Here the real order goes first,
    // then three fakes of 4% of cash each, 3 cents behind the best price and 2 cents
    // apart, and every step is 4 to 5 seconds, so each fake lives one step. They are
    // still labelled, and still pulled.
    @Test
    void tacticsChangeHowASpooferRunsAScheme() {
        var tactics = new SpoofTactics(0.04, 3, 2, 0.01, true, 4_000, 5_000, 20_000);
        BotRunReport report = runner.run(new BotRunRequest(21L, 40, NOISE, MAKERS, 10, VALUE, 2, 3, 2, SYMBOLS,
                tactics));
        assertThat(report.tactics()).isEqualTo(tactics.withDefaults()); // the style left out is filled in
        record Labelled(long account, int episode, String role, long id, double value, String status,
                double lifeSeconds) {
        }
        List<Labelled> orders = jdbc.query("""
                select l.account_id, l.episode, l.role, o.id, o.quantity * o.limit_price, o.status,
                       extract(epoch from o.updated_at - o.created_at)::float8
                from answer_key_orders l join orders o on o.id = l.order_id
                """, (row, n) -> new Labelled(row.getLong(1), row.getInt(2), row.getString(3), row.getLong(4),
                row.getDouble(5), row.getString(6), row.getDouble(7)));
        var episodes = orders.stream().collect(java.util.stream.Collectors.groupingBy(
                o -> o.account() + "/" + o.episode()));
        assertThat(episodes).hasSizeGreaterThan(5);
        int realFirst = 0;
        for (var episode : episodes.values()) {
            var fakes = episode.stream().filter(o -> o.role().equals("SPOOF")).toList();
            var real = episode.stream().filter(o -> o.role().equals("REAL")).toList();
            assertThat(fakes).hasSizeLessThanOrEqualTo(3);
            for (Labelled r : real) {
                assertThat(fakes).allMatch(f -> f.id() > r.id());
                realFirst++;
            }
        }
        assertThat(realFirst).isGreaterThan(5);
        var fakes = orders.stream().filter(o -> o.role().equals("SPOOF")).toList();
        // 4% of $100,000, less where a fake ask ran short of shares
        assertThat(fakes).allMatch(f -> f.value() <= 4_000 + 1_000);
        assertThat(fakes).anyMatch(f -> f.value() > 3_000);
        assertThat(fakes).filteredOn(f -> f.status().equals("CANCELLED"))
                .allMatch(f -> f.lifeSeconds() >= 4 && f.lifeSeconds() <= 5.01);
    }

    // A trial runs one market and says, per spoofer, whether each detector flagged it and
    // what the alert said, which is what a cheater searching for tactics gets back.
    @Test
    void aTrialReportsEachDetectorsVerdictOnEachSpoofer() throws Exception {
        var result = post("/api/surveillance/trials", """
                {"seed": 21, "ticks": 40, "noiseTraders": %d, "marketMakers": %d, "bookReaders": 10,
                 "valueTraders": %d, "symbols": %s, "tactics": {"fakeSize": 0.06}}
                """.formatted(NOISE, MAKERS, VALUE, json.writeValueAsString(SYMBOLS)));
        assertThat(result).hasStatus(HttpStatus.ACCEPTED);
        var trial = finished(result);
        assertThat(trial.get("report").get("tactics").get("fakeSize").asDouble()).isEqualTo(0.06);
        assertThat(trial.get("report").get("tactics").get("restMs").asInt()).isEqualTo(40_000);
        assertThat(trial.get("scores").size()).isEqualTo(4);
        var verdicts = trial.get("spoofers");
        assertThat(verdicts.size()).isEqualTo(8); // two spoofers, four detectors
        for (var verdict : verdicts) {
            assertThat(verdict.get("bot").asString()).startsWith("spoofer-");
            if (verdict.get("flagged").asBoolean()) {
                assertThat(verdict.get("alert").asString()).isNotBlank();
            }
        }
        assertThat(post("/api/surveillance/trials", "{\"seed\": 1, \"tactics\": {\"fakeSize\": 0.9}}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    // A spoofer's starting stake (two stocks, a quarter of its cash in each) is a shape no
    // other honest kind has, and starting stakes are in the trade log. The lookalikes
    // share it, so an account's stake can at best halve the field: of the accounts that
    // start the way the spoofers do, no more than half are cheaters.
    @Test
    void aSpoofersStartingStakeDoesNotGiveItAway() {
        runner.run(new BotRunRequest(21L, 10, NOISE, MAKERS, 10, VALUE, 2, 4, 2, SYMBOLS));
        record Stake(long account, int stocks, boolean cheater) {
        }
        List<Stake> stakes = jdbc.query("""
                select t.account_id, count(distinct t.symbol) as stocks, k.cheater
                from trades t join answer_key k on k.account_id = t.account_id
                where t.counterparty_order_id is null
                group by t.account_id, k.cheater
                """, (row, n) -> new Stake(row.getLong(1), row.getInt(2), row.getBoolean(3)));
        int spoofersStake = stakes.stream().filter(Stake::cheater).findFirst().orElseThrow().stocks();
        var same = stakes.stream().filter(s -> s.stocks() == spoofersStake).toList();
        assertThat(same.stream().filter(Stake::cheater).count()).isEqualTo(2);
        assertThat(same).hasSizeGreaterThanOrEqualTo(4);
        assertThat(answerKey.entries()).filteredOn(e -> e.kind() == BotKind.LOOKALIKE).hasSize(2)
                .noneMatch(AnswerKey.Entry::cheater);
    }

    // With no fake layers the spoofer is the control: same timing and real orders, and
    // honest, so the answer key must not call it a cheater.
    @Test
    void aSpooferWithNoFakeLayersIsTheHonestControl() {
        runner.run(new BotRunRequest(21L, 40, NOISE, MAKERS, 10, VALUE, 2, 0, 2, SYMBOLS));
        assertThat(answerKey.entries()).filteredOn(e -> e.kind() == BotKind.SPOOFER).hasSize(2)
                .noneMatch(AnswerKey.Entry::cheater);
        assertThat(answerKey.orders()).isEmpty(); // only cheaters' orders carry labels
        assertThat(jdbc.queryForObject("""
                select count(*) from orders o join answer_key k on k.account_id = o.account_id
                where k.kind = 'SPOOFER' and o.order_type = 'LIMIT'
                """, Long.class)).isGreaterThan(10); // but they traded
    }

    // The rules catch both spoofers and flag nobody honest, and the orders they point
    // at are the fakes. The naive baseline catches them too, but also the market makers.
    @Test
    void theRulesCatchTheSpoofersAndTheBaselineAlsoFlagsTheMakers() throws Exception {
        runner.run(new BotRunRequest(21L, 80, NOISE, MAKERS, 10, VALUE, 2, 4, 2, SYMBOLS));

        Score rules = grader.grade("spoofing-rules", surveillance.scan("spoofing-rules"));
        assertThat(rules.caught()).isEqualTo(2);
        assertThat(rules.falseAlarms()).isZero();
        assertThat(rules.orders().precision()).isGreaterThanOrEqualTo(0.95);
        assertThat(rules.orders().recall()).isGreaterThan(0.6);

        Score baseline = grader.grade("cancel-ratio", surveillance.scan("cancel-ratio"));
        assertThat(baseline.caught()).isEqualTo(2);
        assertThat(baseline.falseAlarmsByKind()).containsKeys(BotKind.MARKET_MAKER, BotKind.LOOKALIKE);
        assertThat(baseline.orders()).isNull(); // it judges accounts, not orders

        // and the same over HTTP
        assertThat(readList(get("/api/surveillance/detectors"), new tools.jackson.core.type.TypeReference<
                List<String>>() { })).containsExactly("cancel-ratio", "spoofing-rules", "spoofing-rules-v2", "spoofing-rules-v3");
        var alerts = json.readTree(get("/api/surveillance/alerts").getResponse().getContentAsString());
        assertThat(alerts.size()).isEqualTo(2);
        assertThat(alerts.get(0).get("username").asString()).startsWith("trader_");
        assertThat(alerts.get(0).get("evidence").size()).isGreaterThan(10);
        var score = json.readTree(get("/api/surveillance/score?detector=spoofing-rules").getResponse()
                .getContentAsString());
        assertThat(score.get("caught").asInt()).isEqualTo(2);
        assertThat(get("/api/surveillance/alerts?detector=nope")).hasStatus(HttpStatus.NOT_FOUND);
    }

    // The control spoofer trades exactly like the spoofer minus the fakes. The rules
    // must leave it alone; the baseline cannot tell the difference.
    @Test
    void theRulesLeaveTheControlAloneAndTheBaselineDoesNot() {
        runner.run(new BotRunRequest(21L, 80, NOISE, MAKERS, 10, VALUE, 2, 0, 2, SYMBOLS));
        assertThat(surveillance.scan("spoofing-rules")).isEmpty();
        assertThat(grader.grade("cancel-ratio", surveillance.scan("cancel-ratio")).falseAlarmsByKind())
                .containsKey(BotKind.SPOOFER);
    }

    @Test
    void anEvaluationScoresEveryDetectorOnBothMarkets() throws Exception {
        var result = post("/api/surveillance/evaluations", "{\"seeds\": [21], \"ticks\": 20}");
        assertThat(result).hasStatus(HttpStatus.ACCEPTED);
        var report = finished(result);
        assertThat(report.get("runs").size()).isEqualTo(2);
        assertThat(report.get("totals").size()).isEqualTo(8); // four detectors, two markets
        assertThat(post("/api/surveillance/evaluations", "{\"seeds\": []}")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    // A run's mistakes that only show once it starts (here, a stock the feed does not
    // quote) come back through its job, not as a lost request.
    @Test
    void aJobThatFailsSaysWhy() throws Exception {
        var result = post("/api/bots/runs", "{\"seed\": 1, \"ticks\": 2, \"symbols\": [\"NOPE\"]}");
        assertThat(result).hasStatus(HttpStatus.ACCEPTED);
        var job = await(json.readTree(result.getResponse().getContentAsString()).get("id").asLong());
        assertThat(job.get("status").asString()).isEqualTo("FAILED");
        assertThat(job.get("error").asString()).contains("NOPE");
        assertThat(get("/api/jobs/999999")).hasStatus(HttpStatus.NOT_FOUND);
    }

    // the result of the job a 202 started, once it is done
    private tools.jackson.databind.JsonNode finished(org.springframework.test.web.servlet.assertj.MvcTestResult started)
            throws Exception {
        var job = await(json.readTree(started.getResponse().getContentAsString()).get("id").asLong());
        assertThat(job.get("status").asString()).as(job.toString()).isEqualTo("DONE");
        return job.get("result");
    }

    private tools.jackson.databind.JsonNode await(long id) throws Exception {
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (true) {
            var job = json.readTree(get("/api/jobs/" + id).getResponse().getContentAsString());
            String status = job.get("status").asString();
            if (status.equals("DONE") || status.equals("FAILED") || System.nanoTime() > deadline) {
                return job;
            }
            Thread.sleep(100);
        }
    }

    private static BotRunRequest request(long seed) {
        return new BotRunRequest(seed, TICKS, NOISE, MAKERS, READERS, VALUE, SPOOFERS, null, LOOKALIKES, SYMBOLS);
    }

    private static BotRunReport withoutTiming(BotRunReport r) {
        return new BotRunReport(r.seed(), r.ticks(), r.noiseTraders(), r.marketMakers(), r.bookReaders(), r.valueTraders(), r.spoofers(), r.spoofLayers(), r.lookalikes(), r.tactics(), r.symbols(), r.startedAt(),
                r.endedAt(), r.actions(), r.ordersPlaced(), r.refused(), r.cancelled(), r.cancelsTooLate(),
                r.trades(), r.prices(), r.kinds(), r.activity(), r.spoofing(), r.ledgerBalanced(), 0, 0);
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
                          from answer_key)
                  || coalesce((select string_agg(concat_ws('|', order_id, episode, role), E'\\n' order by order_id)
                          from answer_key_orders), ''))
                """, String.class);
    }
}
