package io.github.nishantshah0.papertrader.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

// A clock that stands still until it is moved. In a simulation, time only passes when a
// tick advances it, so a run goes as fast as the machine allows and every timestamp it
// writes is the same on a replay.
public final class SimulationClock extends Clock {

    private final AtomicReference<Instant> now;

    public SimulationClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    public Instant advance(Duration step) {
        if (step.isNegative()) {
            throw new IllegalArgumentException("simulated time only moves forward");
        }
        return now.updateAndGet(current -> current.plus(step));
    }

    public void set(Instant instant) {
        now.set(instant);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("the simulated clock runs in UTC");
    }

    public static boolean isSimulated(Clock clock) {
        return clock instanceof SimulationClock;
    }
}
