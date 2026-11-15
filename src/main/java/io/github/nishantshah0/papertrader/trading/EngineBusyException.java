package io.github.nishantshah0.papertrader.trading;

public class EngineBusyException extends RuntimeException {
    public EngineBusyException(String symbol) {
        super("the " + symbol + " matcher was too busy to start this request, so nothing was done; try again");
    }
}
