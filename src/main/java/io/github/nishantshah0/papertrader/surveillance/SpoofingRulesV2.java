package io.github.nishantshah0.papertrader.surveillance;

import io.github.nishantshah0.papertrader.trading.OrderType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

// The spoofing rules, patched after the AI cheater (tools/agent/cheater.py) got past
// the first version. v1 judged one order at a time, and the cheater found where that
// breaks: it kept every fake under 3x the real order (four fakes of 2x each went
// unflagged, though together they were 8x), and it held fakes past v1's 10-second
// window. v1 also only counted a real order placed after the fakes. v2 judges the
// scheme instead. For each order an account places (the real one), it adds up that
// account's orders on the other side of the same stock that:
//
//   1. are limit orders, later cancelled with under a quarter filled
//   2. lived 60 seconds or less
//   3. were resting while the real order was, and were placed within 15 seconds of it,
//      before or after
//
// If those add up to at least 3x the real order, that's an episode. Three or more
// episodes in a run raise an alert; the score is the episode count. v1 stays as it was,
// so the two can be compared on the same markets.
@Component
public class SpoofingRulesV2 implements Detector {

    static final double SIZE_MULTIPLE = 3;
    static final double MAX_FILLED = 0.25;
    static final Duration MAX_LIFE = Duration.ofSeconds(60);
    static final Duration WINDOW = Duration.ofSeconds(15);
    static final int MIN_EPISODES = 3;

    @Override
    public String name() {
        return "spoofing-rules-v2";
    }

    @Override
    public List<Alert> scan(MarketLog log) {
        Map<String, List<LoggedOrder>> byBook = new HashMap<>(); // account and stock
        for (LoggedOrder order : log.orders()) {
            byBook.computeIfAbsent(order.accountId() + "/" + order.symbol(), key -> new ArrayList<>()).add(order);
        }
        Map<Long, int[]> episodes = new HashMap<>();
        Map<Long, Set<Long>> cited = new HashMap<>();
        Map<Long, Set<Long>> evidence = new HashMap<>();
        for (List<LoggedOrder> book : byBook.values()) {
            book.sort(Comparator.comparing(LoggedOrder::placedAt).thenComparingLong(LoggedOrder::firstSeq));
            List<LoggedOrder> fakes = book.stream().filter(SpoofingRulesV2::looksFake).toList();
            if (fakes.isEmpty()) {
                continue;
            }
            for (LoggedOrder real : book) {
                Instant from = real.placedAt().minus(WINDOW);
                Instant to = real.placedAt().plus(WINDOW);
                Instant realEnd = real.endedAt() == null ? Instant.MAX : real.endedAt();
                long weight = 0;
                List<LoggedOrder> outweighing = new ArrayList<>();
                for (LoggedOrder fake : fakes) {
                    if (fake.placedAt().isAfter(to)) {
                        break;
                    }
                    if (fake.side() == real.side() || fake.id() == real.id() || fake.placedAt().isBefore(from)) {
                        continue;
                    }
                    // resting at the same time as the real order
                    boolean overlaps = !fake.placedAt().isAfter(realEnd) && fake.endedAt().isAfter(real.placedAt());
                    if (overlaps) {
                        weight += fake.quantity();
                        outweighing.add(fake);
                    }
                }
                if (!outweighing.isEmpty() && weight >= SIZE_MULTIPLE * real.quantity()) {
                    long account = real.accountId();
                    episodes.computeIfAbsent(account, key -> new int[1])[0]++;
                    Set<Long> ids = cited.computeIfAbsent(account, key -> new LinkedHashSet<>());
                    Set<Long> lines = evidence.computeIfAbsent(account, key -> new LinkedHashSet<>());
                    outweighing.forEach(f -> {
                        ids.add(f.id());
                        lines.add(f.firstSeq());
                    });
                    lines.add(real.firstSeq());
                }
            }
        }
        List<Alert> alerts = new ArrayList<>();
        episodes.forEach((account, count) -> {
            if (count[0] >= MIN_EPISODES) {
                List<Long> orders = new ArrayList<>(cited.get(account));
                alerts.add(new Alert(name(), account, log.usernames().get(account), count[0],
                        "%d episodes: %d cancelled orders together outweighed a same-account order on the other side"
                                .formatted(count[0], orders.size()),
                        orders, evidence.get(account).stream().sorted().toList()));
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
