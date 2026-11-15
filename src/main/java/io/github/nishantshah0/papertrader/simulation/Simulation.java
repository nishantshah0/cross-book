package io.github.nishantshah0.papertrader.simulation;

import io.github.nishantshah0.papertrader.quote.MarketDataFeed;
import io.github.nishantshah0.papertrader.quote.Quote;
import io.github.nishantshah0.papertrader.quote.QuoteCache;
import io.github.nishantshah0.papertrader.time.SimulationClock;
import io.github.nishantshah0.papertrader.trading.MatchingEngine;
import io.github.nishantshah0.papertrader.trading.Order;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

// Drives the exchange under a simulated clock. Time stands still between ticks, and a
// tick does the same work in the same order every time:
//
//   1. the clock moves forward one feed interval
//   2. the feed draws every symbol's next price from its seeded walk, in symbol order
//   3. each symbol's matcher fades its lean and fills the resting orders the new quote
//      crosses, one symbol after another, each waiting for the last
//   4. the new quotes go out to browsers
//
// Orders placed between ticks run on their symbol's matcher and finish before the call
// returns. So a run that starts from reset(seed) and makes the same calls in the same
// order writes the same rows, with the same ids, prices and timestamps.
//
// Between ticks, advance() moves the clock on by less than a tick, so things that
// happen within one interval (an order and its cancel 200 ms later) get their own
// times. Ticks stay on a fixed grid, start + n * interval, however far advance() went.

@Service
public class Simulation {

    public record Status(String clock, Instant now, long ticks, long seed, long tickSeconds) {
    }

    private final Clock clock;
    private final MarketDataFeed feed;
    private final MatchingEngine engine;
    private final QuoteCache quotes;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final Duration step;
    private final Instant start;
    private long ticks;

    Simulation(Clock clock, MarketDataFeed feed, MatchingEngine engine, QuoteCache quotes, JdbcTemplate jdbc,
            ApplicationEventPublisher events, @Value("${papertrader.feed.interval-ms:15000}") long intervalMs) {
        this.clock = clock;
        this.feed = feed;
        this.engine = engine;
        this.quotes = quotes;
        this.jdbc = jdbc;
        this.events = events;
        this.step = Duration.ofMillis(intervalMs);
        this.start = clock.instant();
    }

    public synchronized Status status() {
        return new Status(SimulationClock.isSimulated(clock) ? "simulated" : "system", clock.instant(), ticks,
                feed.seed(), step.toSeconds());
    }

    public synchronized Status tick(int count) {
        SimulationClock simulated = requireSimulated();
        for (int i = 0; i < count; i++) {
            simulated.set(nextTick());
            List<Quote> updated = feed.refresh();
            for (String symbol : quotes.symbols().stream().sorted().toList()) {
                engine.tick(symbol);
            }
            updated.forEach(events::publishEvent);
            ticks++;
        }
        return status();
    }

    // Moves the clock forward within the current tick. It must stop short of the next
    // tick's time, which only a tick may reach, so the tick still moves time forward and
    // nothing placed before it shares its timestamp.
    public synchronized Status advance(Duration by) {
        SimulationClock simulated = requireSimulated();
        if (by.isNegative() || by.isZero()) {
            throw new BadStepException("advance by a positive duration");
        }
        Instant target = clock.instant().plus(by);
        if (!target.isBefore(nextTick())) {
            throw new BadStepException("advancing " + by.toMillis() + " ms would reach the next tick at "
                    + nextTick() + "; call tick instead");
        }
        simulated.set(target);
        return status();
    }

    // Gives an account shares before a run begins, bought from the house at the feed
    // price (see OrderExecution.allocate). Only under a simulated clock, where the
    // exchange is a sandbox.
    public Order allocate(Long accountId, String symbol, int shares) {
        requireSimulated();
        quotes.require(symbol); // 404 before an unknown symbol gets a matcher thread
        return engine.allocate(accountId, symbol, shares);
    }

    // when the next tick will land; anything due at or after it has to wait for the tick
    public synchronized Instant nextTick() {
        return start.plus(step.multipliedBy(ticks + 1));
    }

    public Duration step() {
        return step;
    }

    // Starts a fresh run: every account, order, trade and event is deleted, the books and
    // leans are cleared, prices go back to their seeds at the start time, and the demo
    // walk restarts from this seed. Only possible under a simulated clock, where the whole
    // exchange is a sandbox; the caller must not be placing orders at the same time.
    public synchronized void reset(long seed) {
        SimulationClock simulated = requireSimulated();
        engine.reset();
        jdbc.execute("truncate table answer_key_orders, answer_key, order_events, trades, positions, house_positions, orders, accounts, users "
                + "restart identity cascade");
        simulated.set(start);
        quotes.reset();
        feed.reseed(seed);
        ticks = 0;
    }

    private SimulationClock requireSimulated() {
        if (clock instanceof SimulationClock simulated) {
            return simulated;
        }
        throw new SimulationOffException();
    }
}
