package io.github.nishantshah0.papertrader;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

// An outside detector named in the settings is a detector like any other: listed, and
// scanned and graded through the same endpoints (so evaluations and trials run it too).
@SpringBootTest(properties = {
        "papertrader.surveillance.external[0].name=outside",
        "papertrader.surveillance.external[0].command[0]=python3",
        "papertrader.surveillance.external[0].command[1]=-c",
        "papertrader.surveillance.external[0].command[2]=import json, sys; log = json.load(sys.stdin); "
                + "print(json.dumps([{'accountId': o['accountId'], 'score': 1} for o in log['orders'][:1]]))"
})
class ExternalDetectorAppTest extends ApiTestSupport {

    @Test
    void itIsListedScannedAndGradedLikeTheBuiltInDetectors() throws Exception {
        assertThat(get("/api/surveillance/detectors")).hasStatusOk().bodyJson().extractingPath("$")
                .asArray().contains("outside", "spoofing-rules", "spoofing-rules-v2");

        var account = createAccount();
        assertThat(post("/api/accounts/" + account.id() + "/orders",
                "{\"symbol\":\"AAPL\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":1,\"limitPrice\":1.00}"))
                .hasStatus(HttpStatus.CREATED);
        assertThat(get("/api/surveillance/alerts?detector=outside")).hasStatusOk().bodyJson()
                .extractingPath("$[0].detector").isEqualTo("outside");
        assertThat(get("/api/surveillance/score?detector=outside")).hasStatusOk().bodyJson()
                .extractingPath("$.detector").isEqualTo("outside");
    }

    // a log from elsewhere goes through every detector, built-in and outside, untouched by this exchange
    @Test
    void anyDetectorCanScanALogFromElsewhere() {
        String spoofing = java.util.stream.IntStream.range(0, 3).mapToObj(e -> {
            int t = e * 60;
            String at = "2026-01-05T14:%02d:%02dZ";
            return ("{\"id\": %d, \"accountId\": 9, \"symbol\": \"X\", \"side\": \"BUY\", \"type\": \"LIMIT\", \"quantity\": 40, "
                    + "\"limitPrice\": 99.90, \"firstSeq\": %d, \"placedAt\": \"" + at + "\", \"endedAt\": \"" + at + "\", "
                    + "\"end\": \"CANCELLED\", \"filled\": 0}, "
                    + "{\"id\": %d, \"accountId\": 9, \"symbol\": \"X\", \"side\": \"SELL\", \"type\": \"LIMIT\", \"quantity\": 10, "
                    + "\"limitPrice\": 100.01, \"firstSeq\": %d, \"placedAt\": \"" + at + "\", \"endedAt\": \"" + at + "\", "
                    + "\"end\": \"FILLED\", \"filled\": 10}").formatted(2 * e + 1, 2 * e + 1, 30 + t / 60, 0, 30 + t / 60, 3,
                    2 * e + 2, 2 * e + 2, 30 + t / 60, 1, 30 + t / 60, 2);
        }).collect(java.util.stream.Collectors.joining(", "));
        var result = post("/api/surveillance/scan", "{\"orders\": [" + spoofing + "], \"usernames\": {\"9\": \"agent_9\"}}");
        assertThat(result).hasStatusOk().bodyJson().extractingPath("$['spoofing-rules'][0].accountId").isEqualTo(9);
        assertThat(result).bodyJson().extractingPath("$.outside[0].detector").isEqualTo("outside");
    }
}
