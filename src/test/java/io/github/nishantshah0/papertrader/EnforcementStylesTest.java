package io.github.nishantshah0.papertrader;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nishantshah0.papertrader.bots.AnswerKey;
import io.github.nishantshah0.papertrader.bots.BotKind;
import io.github.nishantshah0.papertrader.bots.BotRunRequest;
import io.github.nishantshah0.papertrader.bots.BotRunner;
import io.github.nishantshah0.papertrader.bots.SpoofTactics;
import io.github.nishantshah0.papertrader.surveillance.LoggedOrder;
import io.github.nishantshah0.papertrader.surveillance.Surveillance;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// The two spoofers modelled on enforcement cases do what the filings describe, their
// twins with no layers are honest, and a market with them still replays exactly.
@SpringBootTest(properties = {
        "papertrader.market.mode=book",
        "papertrader.clock.mode=simulated",
        "papertrader.clock.start=2026-01-05T14:30:00Z",
        "papertrader.feed.mode=demo",
        "papertrader.feed.interval-ms=15000",
        "papertrader.starting-cash=100000.00",
        "papertrader.seed-quotes.BTA=100.00",
        "papertrader.seed-quotes.BTB=40.00",
        "papertrader.seed-quotes.BTC=250.00",
        "papertrader.seed-quotes.BTD=12.50"
})
class EnforcementStylesTest extends ApiTestSupport {

    private static final List<String> SYMBOLS = List.of("BTA", "BTB", "BTC", "BTD");

    @Autowired
    BotRunner runner;

    @Autowired
    AnswerKey answerKey;

    @Autowired
    Surveillance surveillance;

    private void run(String style, int layers, int ticks) {
        runner.run(new BotRunRequest(21L, ticks, 30, 4, 10, 4, 2, layers, 2, SYMBOLS,
                new SpoofTactics(null, null, null, null, null, null, null, null, style)));
    }

    private List<LoggedOrder> labelled(String role) {
        Map<Long, LoggedOrder> log = surveillance.log().orders().stream()
                .collect(Collectors.toMap(LoggedOrder::id, Function.identity()));
        return answerKey.orders().stream().filter(o -> o.role().equals(role)).map(o -> log.get(o.orderId())).toList();
    }

    // Coscia: the small real order first, then the large fakes, gone within half a second
    @Test
    void theCosciaSpooferPullsItsFakesWithinMilliseconds() {
        run("coscia", 4, 40);
        var fakes = labelled("SPOOF");
        var reals = labelled("REAL");
        assertThat(fakes).hasSizeGreaterThan(20);
        assertThat(fakes).filteredOn(LoggedOrder::cancelled).isNotEmpty()
                .allMatch(o -> o.life().compareTo(Duration.ofMillis(500)) <= 0);
        // every fake was placed 20 to 80 ms after its episode's real order
        var realAt = answerKey.orders().stream().filter(o -> o.role().equals("REAL"))
                .collect(Collectors.toMap(o -> o.accountId() + "/" + o.episode(), o -> o.orderId()));
        Map<Long, LoggedOrder> byId = reals.stream().collect(Collectors.toMap(LoggedOrder::id, Function.identity()));
        answerKey.orders().stream().filter(o -> o.role().equals("SPOOF")).forEach(fake -> {
            LoggedOrder real = byId.get(realAt.get(fake.accountId() + "/" + fake.episode()));
            LoggedOrder f = fakes.stream().filter(x -> x.id() == fake.orderId()).findFirst().orElseThrow();
            assertThat(Duration.between(real.placedAt(), f.placedAt()).toMillis()).isBetween(20L, 80L);
        });
        assertThat(answerKey.entries()).filteredOn(e -> e.kind() == BotKind.SPOOFER).allMatch(AnswerKey.Entry::cheater);
    }

    // Sarao: fakes that sit for a long time, and get replaced as the price moves
    @Test
    void theSaraoLayererKeepsRepricingLongLivedFakes() {
        run("sarao", 5, 120);
        var fakes = labelled("SPOOF");
        long episodes = answerKey.orders().stream().filter(o -> o.role().equals("SPOOF"))
                .map(o -> o.accountId() + "/" + o.episode()).distinct().count();
        assertThat(episodes).isPositive();
        assertThat(fakes.size()).as("more fakes than layers per episode: some were repriced")
                .isGreaterThan(5 * (int) episodes);
        assertThat(fakes).anyMatch(o -> o.life() != null && o.life().compareTo(Duration.ofSeconds(30)) > 0);
        assertThat(answerKey.entries()).filteredOn(e -> e.kind() == BotKind.SPOOFER).allMatch(AnswerKey.Entry::cheater);
    }

    // honest bots label nothing (a twin runs the same code), so check they traded instead
    @Test
    void withNoLayersBothStylesAreHonestTwins() {
        for (String style : List.of("coscia", "sarao")) {
            run(style, 0, 120);
            assertThat(answerKey.orders()).as(style).isEmpty();
            assertThat(answerKey.entries()).as(style).noneMatch(AnswerKey.Entry::cheater);
            var twins = answerKey.entries().stream().filter(e -> e.kind() == BotKind.SPOOFER)
                    .map(AnswerKey.Entry::accountId).toList();
            assertThat(surveillance.log().orders()).as(style).anyMatch(o -> twins.contains(o.accountId()));
        }
    }

    @Test
    void aMarketWithThemReplaysExactly() {
        for (String style : List.of("coscia", "sarao")) {
            run(style, 4, 60);
            var first = surveillance.log().orders();
            run(style, 4, 60);
            assertThat(surveillance.log().orders()).as(style).isEqualTo(first);
        }
    }
}
