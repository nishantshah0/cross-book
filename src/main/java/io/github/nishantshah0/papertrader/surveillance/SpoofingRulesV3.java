package io.github.nishantshah0.papertrader.surveillance;

import java.time.Duration;
import org.springframework.stereotype.Component;

// v2, with one change found by testing outside this market. v2 treats a bid and an ask
// posted in the same instant as one quote, not fakes against a real order, which is how
// this market's makers quote. ABIDES' market maker (J.P. Morgan's simulator, see
// tools/abides/) posts its two ladders a microsecond apart, so v2 read one ladder as
// fakes against the other and flagged it 23 times in 10 markets. v3 counts orders placed
// within 5 ms of each other as one quote. That's still well under anything a spoofer
// here does between its real order and its fakes: the built-in waits at least 200 ms,
// and the Coscia-style one 20 to 80 ms.
//
// The blind spot grows with it: a spoofer that posts its real order and its fakes within
// 5 ms of each other isn't seen. Written after every result in docs/surveillance-lab.md,
// so those were its training data; its test is on markets it hadn't seen.
@Component
public class SpoofingRulesV3 extends SpoofingRulesV2 {

    static final Duration ONE_QUOTE = Duration.ofMillis(5);

    public SpoofingRulesV3() {
        super("spoofing-rules-v3", ONE_QUOTE);
    }
}
