package io.github.nishantshah0.papertrader.time;

import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// The one clock everything that records time reads. "system" is the wall clock;
// "simulated" starts at papertrader.clock.start and moves only when a simulation ticks.
@Configuration
class TimeConfiguration {

    @Bean
    Clock clock(@Value("${papertrader.clock.mode:system}") String mode,
            @Value("${papertrader.clock.start:2026-01-05T14:30:00Z}") String start,
            @Value("${papertrader.feed.mode:demo}") String feedMode) {
        return switch (mode) {
            case "system" -> Clock.systemUTC();
            case "simulated" -> {
                if (feedMode.equals("finnhub")) {
                    throw new IllegalArgumentException("a simulated clock needs the demo or static feed, not live quotes");
                }
                yield new SimulationClock(Instant.parse(start));
            }
            default -> throw new IllegalArgumentException("clock mode must be system or simulated, got " + mode);
        };
    }
}
