package io.github.nishantshah0.papertrader.bots;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

// One bot market. Left out, ticks is 240 (an hour of 15-second ticks), there are 100
// noise traders and 8 market makers, and every symbol the feed quotes is traded.
public record BotRunRequest(
        @NotNull Long seed,
        @Min(1) @Max(5_760) Integer ticks,
        @Min(0) @Max(2_000) Integer noiseTraders,
        @Min(0) @Max(200) Integer marketMakers,
        @Size(max = 50) List<String> symbols) {

    public BotRunRequest withDefaults() {
        return new BotRunRequest(seed, ticks == null ? 240 : ticks, noiseTraders == null ? 100 : noiseTraders,
                marketMakers == null ? 8 : marketMakers, symbols == null ? List.of() : symbols);
    }
}
