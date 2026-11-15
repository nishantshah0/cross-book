package io.github.nishantshah0.papertrader.bots;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// The bots profile's one-command run: once the app is up, run a market and log the
// report, then leave the app running so the result can be looked at in the browser.
@Component
@ConditionalOnBooleanProperty("papertrader.bots.run-on-start")
class BotRunOnStart {

    private static final Logger log = LoggerFactory.getLogger(BotRunOnStart.class);

    private final BotRunner runner;
    private final BotRunRequest request;

    BotRunOnStart(BotRunner runner, @Value("${papertrader.bots.seed:1}") long seed,
            @Value("${papertrader.bots.ticks:240}") int ticks,
            @Value("${papertrader.bots.noise-traders:100}") int noiseTraders,
            @Value("${papertrader.bots.market-makers:8}") int marketMakers) {
        this.runner = runner;
        this.request = new BotRunRequest(seed, ticks, noiseTraders, marketMakers, List.of());
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        Thread.ofPlatform().name("bot-run").start(() -> {
            log.info("Bot market: seed {}, {} ticks, {} noise traders, {} market makers", request.seed(),
                    request.ticks(), request.noiseTraders(), request.marketMakers());
            try {
                BotRunReport report = runner.run(request);
                log.info("Bot market done in {}s ({} actions/s): {} actions, {} orders, {} refused, {} cancels, "
                        + "{} trades between bots, ledger {}", report.seconds(), report.actionsPerSecond(),
                        report.actions(), report.ordersPlaced(), report.refused(), report.cancelled(),
                        report.trades(), report.ledgerBalanced() ? "balanced" : "NOT BALANCED");
                report.prices().forEach(p -> log.info("  {}: last trade {} vs feed {} ({} bps), {} trades",
                        p.symbol(), p.lastTrade(), p.feed(), p.gapBps(), p.trades()));
            } catch (RuntimeException e) {
                log.error("Bot market failed", e);
            }
        });
    }
}
