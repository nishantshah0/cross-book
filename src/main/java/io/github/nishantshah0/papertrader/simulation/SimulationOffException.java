package io.github.nishantshah0.papertrader.simulation;

public class SimulationOffException extends RuntimeException {

    SimulationOffException() {
        super("the clock is not simulated; start the app with papertrader.clock.mode=simulated");
    }
}
