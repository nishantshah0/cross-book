package io.github.nishantshah0.papertrader.bots;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

// When a bot wakes depends only on the run's seed and the bot itself, so a rerun
// without one bot (say, the cheater) leaves everyone else's schedule untouched.
class BotScheduleTest {

    @Test
    void aBotsScheduleDependsOnlyOnTheSeedAndTheBot() {
        assertThat(gaps(new NoiseTrader(3, 42))).isEqualTo(gaps(new NoiseTrader(3, 42)));
        assertThat(gaps(new NoiseTrader(3, 42))).isNotEqualTo(gaps(new NoiseTrader(4, 42)));
        assertThat(gaps(new NoiseTrader(3, 42))).isNotEqualTo(gaps(new NoiseTrader(3, 43)));
        // same number, different kind: a different bot
        assertThat(gaps(new MarketMaker(3, 42, List.of("A")))).isNotEqualTo(gaps(new NoiseTrader(3, 42)));
    }

    @Test
    void decisionsDoNotShiftTheSchedule() {
        NoiseTrader quiet = new NoiseTrader(7, 1);
        NoiseTrader busy = new NoiseTrader(7, 1);
        for (int i = 0; i < 500; i++) {
            busy.random.nextDouble(); // what a bot decides draws from its other stream
        }
        assertThat(gaps(busy)).isEqualTo(gaps(quiet));
    }

    @Test
    void wakesArriveAtTheirMeanRate() {
        NoiseTrader bot = new NoiseTrader(0, 9);
        double mean = LongStream.range(0, 20_000).map(i -> bot.nextGapMs()).average().orElseThrow();
        assertThat(mean).isBetween(NoiseTrader.MEAN_GAP_MS * 0.97, NoiseTrader.MEAN_GAP_MS * 1.03);
    }

    private static List<Long> gaps(Bot bot) {
        return LongStream.range(0, 50).map(i -> bot.nextGapMs()).boxed().toList();
    }
}
