package io.github.nishantshah0.papertrader.bots;

// What a bot is. The id is part of every bot's seed, so it must never change once a
// kind exists; new kinds take new ids.
public enum BotKind {
    NOISE(1, "noise", false),
    MARKET_MAKER(2, "maker", false),
    BOOK_READER(3, "reader", false),
    SPOOFER(4, "spoofer", true),
    VALUE_TRADER(5, "value", false),
    // trades exactly like a spoofer, with the same starting stake, but places no fake
    // orders: honest, and there so nothing but the spoofing tells a spoofer apart
    LOOKALIKE(6, "lookalike", false);

    final int id;
    final String prefix;
    final boolean cheater;

    BotKind(int id, String prefix, boolean cheater) {
        this.id = id;
        this.prefix = prefix;
        this.cheater = cheater;
    }
}
