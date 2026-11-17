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
}
