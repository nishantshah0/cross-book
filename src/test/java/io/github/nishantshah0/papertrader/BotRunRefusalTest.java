package io.github.nishantshah0.papertrader;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

// The default app has a house and the wall clock: a bot run there would trade with the
// house and could not be replayed, so it is refused before anything is touched.
class BotRunRefusalTest extends ApiTestSupport {

    @Test
    void aBotRunNeedsBookMode() throws Exception {
        var result = post("/api/bots/runs", "{\"seed\": 1}");
        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(detail(result)).contains("book mode");
    }

    @Test
    void aBotRunNeedsASeed() {
        assertThat(post("/api/bots/runs", "{\"ticks\": 10}")).hasStatus(HttpStatus.BAD_REQUEST);
    }
}
