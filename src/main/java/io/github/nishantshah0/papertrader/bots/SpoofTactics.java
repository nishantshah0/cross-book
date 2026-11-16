package io.github.nishantshah0.papertrader.bots;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

// How a spoofer runs each scheme. Anything left out keeps the built-in spoofer's way:
// fakes of 8% of its cash each, a cent apart from a cent behind the best price, then a
// real order of 2% a cent inside the spread, each step 1 to 3 seconds after the last,
// and about 40 seconds' rest between schemes. The number of fakes is spoofLayers.
//
// These are the knobs an adaptive cheater turns. Whatever it picks, the fakes are still
// fakes: they are labelled SPOOF in the answer key and cancelled when the scheme ends,
// and lookalikes run the same tactics with nothing fake, so they stay honest twins.
public record SpoofTactics(
        // each fake order, as a share of the spoofer's cash
        @DecimalMin("0.005") @DecimalMax("0.15") Double fakeSize,
        // the first fake's distance behind the best price, and the gap between fakes
        @Min(1) @Max(50) Integer fakeOffsetCents,
        @Min(1) @Max(20) Integer fakeGapCents,
        // the real order, as a share of cash
        @DecimalMin("0.005") @DecimalMax("0.05") Double realSize,
        // post the real order before the fakes rather than after them
        Boolean realFirst,
        // the wait between one step of a scheme and the next, drawn uniformly
        @Min(200) @Max(30_000) Integer stepMinMs,
        @Min(200) @Max(30_000) Integer stepMaxMs,
        // the mean rest between schemes
        @Min(5_000) @Max(600_000) Integer restMs) {

    public static final SpoofTactics DEFAULT = new SpoofTactics(0.08, 1, 1, 0.02, false, 1_000, 3_000, 40_000);

    public SpoofTactics withDefaults() {
        SpoofTactics d = DEFAULT;
        int min = stepMinMs == null ? d.stepMinMs : stepMinMs;
        int max = stepMaxMs == null ? Math.max(min, d.stepMaxMs) : stepMaxMs;
        return new SpoofTactics(fakeSize == null ? d.fakeSize : fakeSize,
                fakeOffsetCents == null ? d.fakeOffsetCents : fakeOffsetCents,
                fakeGapCents == null ? d.fakeGapCents : fakeGapCents,
                realSize == null ? d.realSize : realSize,
                realFirst == null ? d.realFirst : realFirst,
                Math.min(min, max), Math.max(min, max), restMs == null ? d.restMs : restMs);
    }
}
