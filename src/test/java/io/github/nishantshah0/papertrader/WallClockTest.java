package io.github.nishantshah0.papertrader;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

// By default the app runs on the wall clock, and the simulation controls refuse to move it.
class WallClockTest extends ApiTestSupport {

    @Test
    void theWallClockCannotBeTicked() throws Exception {
        var status = get("/api/simulation");
        assertThat(status).hasStatus(HttpStatus.OK);
        assertThat(status).bodyJson().extractingPath("$.clock").isEqualTo("system");

        var tick = post("/api/simulation/ticks?count=1", "");
        assertThat(tick).hasStatus(HttpStatus.CONFLICT);
        assertThat(detail(tick)).contains("not simulated");
    }

    @Test
    void ordersAreStampedWithTheWallClock() throws Exception {
        var account = createAccount();
        Instant before = Instant.now();

        var placed = post(orders(account), "{\"symbol\":\"AAPL\",\"side\":\"BUY\",\"quantity\":1}");

        String created = json.readTree(placed.getResponse().getContentAsString()).get("createdAt").asString();
        assertThat(Duration.between(before, Instant.parse(created)).abs()).isLessThan(Duration.ofSeconds(5));
    }
}
