package io.github.nishantshah0.papertrader.surveillance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nishantshah0.papertrader.trading.OrderSide;
import io.github.nishantshah0.papertrader.trading.OrderType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

// An outside detector is a program: the log goes in on stdin, alerts come out on stdout.
// These run a real one (a line of Python) to hold both ends of that contract.
class ExternalDetectorTest {

    private static final Instant T0 = Instant.parse("2026-01-05T14:30:00Z");
    private static final MarketLog LOG = new MarketLog(List.of(
            new LoggedOrder(1, 7, "TEST", OrderSide.BUY, OrderType.LIMIT, 40, new BigDecimal("99.90"), 11, T0,
                    T0.plusSeconds(3), "CANCELLED", 0),
            new LoggedOrder(2, 7, "TEST", OrderSide.SELL, OrderType.LIMIT, 10, new BigDecimal("100.01"), 12,
                    T0.plusSeconds(1), T0.plusSeconds(2), "FILLED", 10),
            new LoggedOrder(3, 8, "TEST", OrderSide.BUY, OrderType.MARKET, 5, null, 13, T0.plusSeconds(4),
                    T0.plusSeconds(4), "FILLED", 5)),
            Map.of(7L, "trader_0007", 8L, "trader_0008"));

    private static ExternalDetector program(String python) {
        return new ExternalDetector(new ExternalDetectorSettings.Spec("outside", List.of("python3", "-c", python),
                Duration.ofSeconds(30)), JsonMapper.builder().build());
    }

    @Test
    void theProgramReadsTheLogAndItsAlertsComeBackAsAlerts() {
        // flags every account with a cancelled order, citing those orders and their log lines
        var detector = program("""
                import json, sys
                log = json.load(sys.stdin)
                cancelled = [o for o in log["orders"] if o["end"] == "CANCELLED"]
                accounts = sorted({o["accountId"] for o in cancelled})
                print(json.dumps([{"accountId": a, "score": 2.5, "summary": "cancelled",
                                   "orders": [o["id"] for o in cancelled if o["accountId"] == a],
                                   "evidence": [o["firstSeq"] for o in cancelled if o["accountId"] == a]}
                                  for a in accounts]))
                """);
        assertThat(detector.scan(LOG)).containsExactly(
                new Alert("outside", 7, "trader_0007", 2.5, "cancelled", List.of(1L), List.of(11L)));
    }

    @Test
    void onlyAnAccountAndAScoreAreRequired() {
        var detector = program("import sys; sys.stdin.read(); print('[{\"accountId\": 8, \"score\": 1}]')");
        assertThat(detector.scan(LOG)).singleElement().satisfies(alert -> {
            assertThat(alert.username()).isEqualTo("trader_0008");
            assertThat(alert.orders()).isEmpty();
            assertThat(alert.summary()).isEmpty();
        });
    }

    @Test
    void aProgramThatFailsSaysWhy() {
        var detector = program("import sys; sys.stdin.read(); sys.exit('no model loaded')");
        assertThatThrownBy(() -> detector.scan(LOG)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside exited with 1").hasMessageContaining("no model loaded");
    }

    @Test
    void anAlertWithoutAnAccountIsRefused() {
        var detector = program("import sys; sys.stdin.read(); print('[{\"score\": 1}]')");
        assertThatThrownBy(() -> detector.scan(LOG)).hasMessageContaining("without an accountId");
    }
}
