package io.github.nishantshah0.papertrader.grading;

import io.github.nishantshah0.papertrader.bots.AnswerKey;
import io.github.nishantshah0.papertrader.bots.BotKind;
import io.github.nishantshah0.papertrader.bots.BotRunReport;
import io.github.nishantshah0.papertrader.bots.BotRunRequest;
import io.github.nishantshah0.papertrader.bots.SpoofTactics;
import io.github.nishantshah0.papertrader.bots.BotRunner;
import io.github.nishantshah0.papertrader.surveillance.Alert;
import io.github.nishantshah0.papertrader.surveillance.Detector;
import io.github.nishantshah0.papertrader.surveillance.MarketLog;
import io.github.nishantshah0.papertrader.surveillance.Surveillance;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

// Scores every detector over many markets, two per seed: the default market with its
// spoofers, and the control market, where the same spoofer bots place no fake orders
// and so are honest. A detector that flags the control is flagging how the spoofer
// trades, not the spoofing. Totals add up the counts across seeds before dividing.
@Service
public class Evaluation {

    public record Request(List<Long> seeds, Integer ticks, SpoofTactics tactics) {
    }

    public record Run(long seed, String market, BotRunReport.KindSummary spoofers, List<Score> scores) {
    }

    public record Total(String detector, String market, int flagged, int cheaters, int caught, int falseAlarms,
            Double precision, Double recall, Map<String, Integer> falseAlarmsByKind, Double orderPrecision,
            Double orderRecall) {
    }

    public record Report(List<Long> seeds, int ticks, List<Total> totals, List<Run> runs, double seconds) {
    }

    // One market and every detector's verdict on it, for a cheater searching for tactics
    // that get past surveillance. spoofers says, per spoofer bot, whether each detector
    // flagged it and what the alert said: what a cheater would learn from being charged.
    public record Trial(BotRunReport report, List<Score> scores, List<SpooferVerdict> spoofers) {
    }

    public record SpooferVerdict(String bot, String detector, boolean flagged, String alert) {
    }

    private final BotRunner runner;
    private final Surveillance surveillance;
    private final Grader grader;
    private final AnswerKey answerKey;

    Evaluation(BotRunner runner, Surveillance surveillance, Grader grader, AnswerKey answerKey) {
        this.runner = runner;
        this.surveillance = surveillance;
        this.grader = grader;
        this.answerKey = answerKey;
    }

    public Trial trial(BotRunRequest request) {
        BotRunReport report = runner.run(request);
        MarketLog log = surveillance.log();
        Map<Long, AnswerKey.Entry> spoofers = new LinkedHashMap<>();
        answerKey.entries().stream().filter(e -> e.kind() == BotKind.SPOOFER)
                .forEach(e -> spoofers.put(e.accountId(), e));
        List<Score> scores = new ArrayList<>();
        List<SpooferVerdict> verdicts = new ArrayList<>();
        for (String name : surveillance.names()) {
            List<Alert> alerts = surveillance.detector(name).scan(log);
            scores.add(grader.grade(name, alerts));
            Map<Long, Alert> byAccount = new LinkedHashMap<>();
            alerts.forEach(a -> byAccount.put(a.accountId(), a));
            spoofers.forEach((id, entry) -> {
                Alert alert = byAccount.get(id);
                verdicts.add(new SpooferVerdict(entry.bot(), name, alert != null, alert == null ? null : alert.summary()));
            });
        }
        return new Trial(report, scores, verdicts);
    }

    public Report evaluate(Request request) {
        long started = System.nanoTime();
        int ticks = request.ticks() == null ? 240 : request.ticks();
        List<Run> runs = new ArrayList<>();
        for (long seed : request.seeds()) {
            for (String market : List.of("spoofers", "control")) {
                int layers = market.equals("spoofers") ? 4 : 0;
                BotRunReport report = runner.run(new BotRunRequest(seed, ticks, null, null, null, null, null, layers,
                        null, null, request.tactics()));
                MarketLog log = surveillance.log();
                List<Score> scores = new ArrayList<>();
                for (String name : surveillance.names()) {
                    Detector detector = surveillance.detector(name);
                    scores.add(grader.grade(name, detector.scan(log)));
                }
                var spoofers = report.kinds().stream()
                        .filter(k -> k.kind() == BotKind.SPOOFER)
                        .findFirst().orElse(null);
                runs.add(new Run(seed, market, spoofers, scores));
            }
        }
        return new Report(request.seeds(), ticks, totals(runs), runs,
                Math.round((System.nanoTime() - started) / 1e7) / 100.0);
    }

    private List<Total> totals(List<Run> runs) {
        Map<String, List<Score>> grouped = new LinkedHashMap<>();
        for (Run run : runs) {
            for (Score score : run.scores()) {
                grouped.computeIfAbsent(score.detector() + "|" + run.market(), k -> new ArrayList<>()).add(score);
            }
        }
        List<Total> totals = new ArrayList<>();
        grouped.forEach((group, scores) -> {
            String[] parts = group.split("\\|");
            int flagged = 0, cheaters = 0, caught = 0, wrong = 0, spoof = 0, judged = 0, hit = 0;
            boolean orders = false;
            Map<String, Integer> byKind = new LinkedHashMap<>();
            for (Score s : scores) {
                flagged += s.flagged();
                cheaters += s.cheaters();
                caught += s.caught();
                wrong += s.falseAlarms();
                s.falseAlarmsByKind().forEach((kind, n) -> byKind.merge(kind.name(), n, Integer::sum));
                if (s.orders() != null) {
                    orders = true;
                    spoof += s.orders().spoofOrders();
                    judged += s.orders().pointedAt();
                    hit += s.orders().pointedAtSpoof();
                }
            }
            totals.add(new Total(parts[0], parts[1], flagged, cheaters, caught, wrong, Grader.ratio(caught, flagged),
                    Grader.ratio(caught, cheaters), byKind, orders ? Grader.ratio(hit, judged) : null,
                    orders ? Grader.ratio(hit, spoof) : null));
        });
        return totals;
    }
}
