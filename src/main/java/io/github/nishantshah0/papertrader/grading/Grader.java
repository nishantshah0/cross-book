package io.github.nishantshah0.papertrader.grading;

import io.github.nishantshah0.papertrader.bots.AnswerKey;
import io.github.nishantshah0.papertrader.bots.BotKind;
import io.github.nishantshah0.papertrader.surveillance.Alert;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

// The only code that holds a detector's alerts and the answer key side by side.
@Component
public class Grader {

    private final AnswerKey answerKey;

    Grader(AnswerKey answerKey) {
        this.answerKey = answerKey;
    }

    public Score grade(String detector, List<Alert> alerts) {
        Map<Long, AnswerKey.Entry> key = new HashMap<>();
        answerKey.entries().forEach(e -> key.put(e.accountId(), e));
        Set<Long> cheaters = new HashSet<>();
        key.values().stream().filter(AnswerKey.Entry::cheater).forEach(e -> cheaters.add(e.accountId()));

        int caught = 0;
        Map<BotKind, Integer> falseAlarms = new EnumMap<>(BotKind.class);
        Set<Long> pointedAt = new HashSet<>();
        boolean judgesOrders = false;
        for (Alert alert : alerts) {
            if (cheaters.contains(alert.accountId())) {
                caught++;
            } else {
                AnswerKey.Entry entry = key.get(alert.accountId());
                falseAlarms.merge(entry == null ? BotKind.NOISE : entry.kind(), 1, Integer::sum);
            }
            if (!alert.orders().isEmpty()) {
                judgesOrders = true;
                pointedAt.addAll(alert.orders());
            }
        }
        int wrong = falseAlarms.values().stream().mapToInt(Integer::intValue).sum();

        Set<Long> spoof = new HashSet<>();
        Set<Long> real = new HashSet<>();
        answerKey.orders().forEach(o -> (o.role().equals("SPOOF") ? spoof : real).add(o.orderId()));
        Score.OrderScore orders = null;
        if (judgesOrders || alerts.isEmpty()) {
            int hit = (int) pointedAt.stream().filter(spoof::contains).count();
            int judged = (int) pointedAt.stream().filter(id -> !real.contains(id)).count();
            orders = new Score.OrderScore(spoof.size(), judged, hit, ratio(hit, judged), ratio(hit, spoof.size()));
        }
        return new Score(detector, alerts.size(), cheaters.size(), caught, cheaters.size() - caught, wrong,
                ratio(caught, alerts.size()), ratio(caught, cheaters.size()), falseAlarms, orders);
    }

    // null when there is nothing to divide by: no alerts has no precision, no cheaters no recall
    static Double ratio(int part, int whole) {
        return whole == 0 ? null : Math.round(1000.0 * part / whole) / 1000.0;
    }
}
