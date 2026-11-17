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
        int bookReaders,
        int valueTraders,
        int spoofers,
        int spoofLayers,
        int lookalikes,
        SpoofTactics tactics,
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
        List<KindSummary> kinds,
        List<KindActivity> activity,
        Spoofing spoofing,
        boolean ledgerBalanced,
        double seconds,
        double actionsPerSecond) {

    // How each kind of bot did from trading: what its accounts are worth at the end, at
    // the feed price, minus what they would be worth had they only held their starting
    // shares. So a stock drifting up or down does not count as anyone's skill or loss.
    public record KindSummary(BotKind kind, int bots, BigDecimal tradingPnl, BigDecimal pnlPerBot) {
    }

    // What each kind did once the clock started: limit and market orders placed, how many
    // traded at all, and the shares they traded. edge is what its trades made against fair
    // value at the time: for each fill, how far above the feed price it sold or below it
    // bought, times the shares. Unlike trading P&L, it leaves out what holding the shares
    // did afterwards, which is where most of an hour's P&L noise comes from. edgeBps is that
    // per dollar traded, in basis points; null if it never traded.
    public record KindActivity(BotKind kind, long orders, long ordersFilled, long sharesFilled, BigDecimal edge,
            Double edgeBps) {
    }

    // What the cheaters' labelled orders came to: their episodes, the fakes and how many
    // got hit, and the real orders and how much they traded. Null in a market with no
    // labelled orders (no cheaters, or spoofers with no fake layers).
    public record Spoofing(long episodes, long fakeOrders, long fakesFilled, long realOrders, long realFilled,
            long realShares) {
    }

    // where trading left a symbol against its feed price; gapBps is null if it never traded
    public record SymbolSummary(String symbol, BigDecimal feed, BigDecimal lastTrade, Double gapBps, long trades,
            long shares) {
    }
}
