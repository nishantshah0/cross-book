package io.github.nishantshah0.papertrader.simulation;

import io.github.nishantshah0.papertrader.trading.OrderResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Reading the clock is always allowed. Moving it is only possible under a simulated
// clock; on the wall clock it answers 409.
@RestController
class SimulationController {

    private final Simulation simulation;

    SimulationController(Simulation simulation) {
        this.simulation = simulation;
    }

    @GetMapping("/api/simulation")
    Simulation.Status status() {
        return simulation.status();
    }

    @PostMapping("/api/simulation/ticks")
    Simulation.Status tick(@RequestParam(defaultValue = "1") @Min(1) @Max(10_000) int count) {
        return simulation.tick(count);
    }

    // moves the clock forward within the current tick; 400 if it would reach the next one
    @PostMapping("/api/simulation/advance")
    Simulation.Status advance(@RequestParam @Min(1) @Max(86_400_000) long millis) {
        return simulation.advance(Duration.ofMillis(millis));
    }

    @PostMapping("/api/simulation/allocations")
    @ResponseStatus(HttpStatus.CREATED)
    OrderResponse allocate(@RequestParam Long accountId, @RequestParam String symbol,
            @RequestParam @Min(1) @Max(1_000_000) int shares) {
        return OrderResponse.from(simulation.allocate(accountId, symbol.toUpperCase(Locale.ROOT), shares));
    }
}
