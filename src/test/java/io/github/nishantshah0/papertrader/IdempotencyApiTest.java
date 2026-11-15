package io.github.nishantshah0.papertrader;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.nishantshah0.papertrader.account.AccountResponse;
import io.github.nishantshah0.papertrader.trading.OrderResponse;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

class IdempotencyApiTest extends ApiTestSupport {
    @Test void retryHeaderReturnsOriginalOrderAndConflictingPayloadReturns409() throws Exception {
        var a=createAccount(); String body="{\"symbol\":\"AAPL\",\"side\":\"BUY\",\"quantity\":1}";
        var first=mvc.post().uri(orders(a)).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key","retry").content(body).exchange();
        var second=mvc.post().uri(orders(a)).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key","retry").content(body).exchange();
        assertThat(first).hasStatus(HttpStatus.CREATED);assertThat(second).hasStatus(HttpStatus.CREATED);
        assertThat(read(first,OrderResponse.class).id()).isEqualTo(read(second,OrderResponse.class).id());
        assertThat(portfolio(a).cash()).isEqualByComparingTo("900");
        assertThat(mvc.post().uri(orders(a)).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key","retry").content(body.replace(":1}",":2}")).exchange()).hasStatus(HttpStatus.CONFLICT);
    }

    // The reviewer's last finding. A key is checked before the order's account is locked,
    // so the same key sent on two symbols at once reaches two matchers, and both can pass
    // the check. The unique index stops the second; it must still get the idempotency
    // conflict, not a generic database error. Every round must end with one order placed
    // and one refusal saying why, whichever way the race went.
    @Test
    void theSameKeyRacedOnTwoSymbolsPlacesOneOrderAndExplainsTheOther() throws Exception {
        for (int round = 0; round < 20; round++) {
            var account = createAccount(); // the test balance only buys a few shares
            String key = "race-" + round;
            var start = new CountDownLatch(1);
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var apple = pool.submit(() -> keyed(account, key, "AAPL", start));
                var microsoft = pool.submit(() -> keyed(account, key, "MSFT", start));
                start.countDown();
                List<MvcTestResult> results = List.of(apple.get(), microsoft.get());

                assertThat(results).filteredOn(r -> r.getResponse().getStatus() == 201).hasSize(1);
                var refused = results.stream().filter(r -> r.getResponse().getStatus() != 201).findFirst().orElseThrow();
                assertThat(refused).hasStatus(HttpStatus.CONFLICT);
                assertThat(detail(refused)).isEqualTo("Idempotency-Key was already used for a different order");
            }
        }
    }

    private MvcTestResult keyed(AccountResponse account, String key, String symbol, CountDownLatch start)
            throws InterruptedException {
        start.await();
        return mvc.post().uri(orders(account)).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key)
                .content("{\"symbol\":\"" + symbol + "\",\"side\":\"BUY\",\"quantity\":1}").exchange();
    }
}
