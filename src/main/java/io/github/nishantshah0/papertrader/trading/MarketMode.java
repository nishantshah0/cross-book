package io.github.nishantshah0.papertrader.trading;

import java.util.Locale;

// Who sets prices.
//
// HOUSE: a simulated market maker quotes around the feed price and takes the other side
// of whatever users leave. Prices stay near the feed, which suits one person practising.
//
// BOOK: there is no house. Orders only trade with other users' resting orders, so the
// book alone sets the price and the feed is just a reference value that traders may
// watch. A bot market needs this: a pump has to be able to move the price, and every
// dollar a manipulator makes has to come from another trader.
public enum MarketMode {
    HOUSE, BOOK;

    public static MarketMode parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("papertrader.market.mode must be house or book, not " + value);
        }
    }
}
