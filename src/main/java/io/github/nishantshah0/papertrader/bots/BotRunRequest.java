package io.github.nishantshah0.papertrader.bots;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

// One bot market. Anything left out takes its default: 240 ticks (an hour of 15-second
// ticks), 100 noise traders, 8 market makers, 20 book readers, 10 value traders, 2
// spoofers with 4 fake layers each and 2 lookalikes, on every symbol the feed quotes.
// Set spoofers to 0 for a market with no cheaters, or spoofLayers to 0 for the control:
// spoofers that place no fake orders, and so are honest.
public record BotRunRequest(
        @NotNull Long seed,
        @Min(1) @Max(5_760) Integer ticks,
        @Min(0) @Max(2_000) Integer noiseTraders,
        @Min(0) @Max(200) Integer marketMakers,
        @Min(0) @Max(500) Integer bookReaders,
        @Min(0) @Max(500) Integer valueTraders,
        @Min(0) @Max(50) Integer spoofers,
        @Min(0) @Max(8) Integer spoofLayers,
        @Min(0) @Max(50) Integer lookalikes,
        @Size(max = 50) List<String> symbols) {

    public BotRunRequest withDefaults() {
        return new BotRunRequest(seed, or(ticks, 240), or(noiseTraders, 100), or(marketMakers, 8),
                or(bookReaders, 20), or(valueTraders, 10), or(spoofers, 2),
                or(spoofLayers, Spoofer.DEFAULT_LAYERS), or(lookalikes, 2), symbols == null ? List.of() : symbols);
    }

    private static int or(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
