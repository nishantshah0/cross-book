package io.github.nishantshah0.papertrader.simulation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
}
