package io.github.nishantshah0.papertrader.surveillance;

import io.github.nishantshah0.papertrader.trading.OrderType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

// The naive detector every real rule has to beat: flag anyone who cancels at least 80%
// of the limit orders they place (with at least 20 placed). Spoofers cancel nearly
// everything, so it catches them, but so do honest market makers, who re-quote all day.
@Component
public class CancelRatioBaseline implements Detector {

    static final double MIN_RATIO = 0.8;
    static final int MIN_ORDERS = 20;

    @Override
    public String name() {
        return "cancel-ratio";
    }

    @Override
    public List<Alert> scan(MarketLog log) {
        Map<Long, int[]> counts = new HashMap<>(); // placed, cancelled
        for (LoggedOrder order : log.orders()) {
            if (order.type() == OrderType.LIMIT) {
                int[] c = counts.computeIfAbsent(order.accountId(), key -> new int[2]);
                c[0]++;
                if (order.cancelled()) {
                    c[1]++;
                }
            }
        }
        List<Alert> alerts = new ArrayList<>();
        counts.forEach((account, c) -> {
            double ratio = (double) c[1] / c[0];
            if (c[0] >= MIN_ORDERS && ratio >= MIN_RATIO) {
                alerts.add(new Alert(name(), account, log.usernames().get(account), ratio,
                        "cancelled %d of %d limit orders (%.0f%%)".formatted(c[1], c[0], ratio * 100),
                        List.of(), List.of()));
            }
        });
        alerts.sort(Comparator.comparingDouble(Alert::score).reversed().thenComparingLong(Alert::accountId));
        return alerts;
    }
}
