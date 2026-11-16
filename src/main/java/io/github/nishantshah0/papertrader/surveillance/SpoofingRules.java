package io.github.nishantshah0.papertrader.surveillance;

import io.github.nishantshah0.papertrader.trading.OrderType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

// Rule-based spoofing surveillance, written the way exchange rules describe spoofing:
// large orders placed with no intent to trade, to move others, while the same trader
// trades a smaller order the other way. An order is suspect when all of these hold:
//
//   1. it is a limit order, cancelled with under a quarter of it filled
//   2. it lived 10 seconds or less
//   3. while it rested, the same account placed an order on the other side of the
//      same stock, strictly later (so a market maker posting its bid and ask together
//      does not count)
//   4. it is at least 3x the size of that other-side order
//
// Suspect orders by one account in one stock placed within a second of each other are
// one episode. Three or more episodes in a run raise an alert; the score is the episode
// count. The thresholds were fixed on seeds 1 to 3 and then frozen before the held-out
// seeds were scored. A first version compared size with the stock's median order
// instead; market makers place most limit orders, so their quote size set the median,
// and it missed every spoofer.
@Component
public class SpoofingRules implements Detector {

    static final double SIZE_MULTIPLE = 3;
    static final double MAX_FILLED = 0.25;
    static final Duration MAX_LIFE = Duration.ofSeconds(10);
    static final Duration EPISODE_GAP = Duration.ofSeconds(1);
    static final int MIN_EPISODES = 3;

    @Override
    public String name() {
        return "spoofing-rules";
    }

    @Override
    public List<Alert> scan(MarketLog log) {
        Map<String, List<LoggedOrder>> byBook = new HashMap<>(); // account and stock
        for (LoggedOrder order : log.orders()) {
            byBook.computeIfAbsent(order.accountId() + "/" + order.symbol(), key -> new ArrayList<>()).add(order);
        }
        Map<Long, List<LoggedOrder[]>> suspects = new HashMap<>(); // account -> (suspect, other side)
        for (List<LoggedOrder> book : byBook.values()) {
            book.sort(Comparator.comparing(LoggedOrder::placedAt).thenComparingLong(LoggedOrder::firstSeq));
            for (int i = 0; i < book.size(); i++) {
                LoggedOrder order = book.get(i);
                if (!looksFake(order)) {
                    continue;
                }
                for (int j = i + 1; j < book.size() && book.get(j).placedAt().isBefore(order.endedAt()); j++) {
                    LoggedOrder other = book.get(j);
                    if (other.side() != order.side() && other.placedAt().isAfter(order.placedAt())
                            && order.quantity() >= SIZE_MULTIPLE * other.quantity()) {
                        suspects.computeIfAbsent(order.accountId(), key -> new ArrayList<>())
                                .add(new LoggedOrder[] {order, other});
                        break;
                    }
                }
            }
        }
        List<Alert> alerts = new ArrayList<>();
        suspects.forEach((account, pairs) -> {
            pairs.sort(Comparator.comparing((LoggedOrder[] p) -> p[0].placedAt()).thenComparingLong(p -> p[0].id()));
            int episodes = 0;
            String symbol = null;
            Instant last = null;
            List<Long> orders = new ArrayList<>();
            List<Long> evidence = new ArrayList<>();
            for (LoggedOrder[] pair : pairs) {
                LoggedOrder fake = pair[0];
                if (last == null || !fake.symbol().equals(symbol)
                        || Duration.between(last, fake.placedAt()).compareTo(EPISODE_GAP) > 0) {
                    episodes++;
                }
                symbol = fake.symbol();
                last = fake.placedAt();
                orders.add(fake.id());
                evidence.add(fake.firstSeq());
                evidence.add(pair[1].firstSeq());
            }
            if (episodes >= MIN_EPISODES) {
                alerts.add(new Alert(name(), account, log.usernames().get(account), episodes,
                        "%d episodes: %d orders cancelled within %ds while a far smaller order traded the other side"
                                .formatted(episodes, orders.size(), MAX_LIFE.toSeconds()),
                        orders, evidence.stream().distinct().sorted().toList()));
            }
        });
        alerts.sort(Comparator.comparingDouble(Alert::score).reversed().thenComparingLong(Alert::accountId));
        return alerts;
    }

    private static boolean looksFake(LoggedOrder order) {
        return order.type() == OrderType.LIMIT
                && order.cancelled()
                && order.filled() < MAX_FILLED * order.quantity()
                && order.life().compareTo(MAX_LIFE) <= 0;
    }
}
