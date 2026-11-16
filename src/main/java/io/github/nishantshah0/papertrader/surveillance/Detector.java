package io.github.nishantshah0.papertrader.surveillance;

import java.util.List;

// Reads a market's log and says which accounts look like they are manipulating it.
public interface Detector {

    String name();

    List<Alert> scan(MarketLog log);
}
