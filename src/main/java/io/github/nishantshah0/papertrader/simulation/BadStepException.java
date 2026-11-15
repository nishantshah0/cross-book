package io.github.nishantshah0.papertrader.simulation;

// a clock step the simulation cannot take; answered with 400
public class BadStepException extends RuntimeException {

    BadStepException(String message) {
        super(message);
    }
}
