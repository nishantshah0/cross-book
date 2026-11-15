package io.github.nishantshah0.papertrader.bots;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

// What a run did. Everything but seconds and actionsPerSecond is fixed by the request,
// so two runs of the same request report the same numbers.
public record BotRunReport(
        long seed,
        int ticks,
        int noiseTraders,
        int marketMakers,
        List<String> symbols,
        Instant startedAt,
        Instant endedAt,
        long actions,
        long ordersPlaced,
        long refused,
        long cancelled,
        long cancelsTooLate,
        long trades,
        List<SymbolSummary> prices,
        boolean ledgerBalanced,
        double seconds,
        double actionsPerSecond) {

    // where trading left a symbol against its feed price; gapBps is null if it never traded
    public record SymbolSummary(String symbol, BigDecimal feed, BigDecimal lastTrade, Double gapBps, long trades,
            long shares) {
    }
}
