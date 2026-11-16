package io.github.nishantshah0.papertrader.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.nishantshah0.papertrader.quote.Quote;
import io.github.nishantshah0.papertrader.quote.QuoteCache;
import io.github.nishantshah0.papertrader.trading.OrderExecution.BookChange;
import io.github.nishantshah0.papertrader.trading.OrderExecution.BookOutOfSyncException;
import io.github.nishantshah0.papertrader.trading.OrderExecution.Result;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

// The engine with its database side mocked out: what reaches the book, and when.
class MatchingEngineTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final TradeRepository trades = mock(TradeRepository.class);
    private final OrderExecution execution = mock(OrderExecution.class);
    private final QuoteCache quotes = mock(QuoteCache.class);
    private final Order bid90 = limit(11L, 1L, "90");
    private final Order bid80 = limit(12L, 2L, "80");
    private final PlaceOrderRequest request =
            new PlaceOrderRequest("AAPL", OrderSide.BUY, OrderType.LIMIT, 1, new BigDecimal("70"));
    private MatchingEngine engine;

    @BeforeEach
    void rebuildFromOpenOrders() {
        when(repository.findByStatusOrderByCreatedAtAscIdAsc(OrderStatus.OPEN)).thenReturn(List.of(bid90, bid80));
        when(repository.findBySymbolAndStatusOrderByCreatedAtAscIdAsc("AAPL", OrderStatus.OPEN))
                .thenReturn(List.of(bid90, bid80));
        when(quotes.symbols()).thenReturn(Set.of("AAPL"));
        when(quotes.get("AAPL")).thenReturn(Optional.empty());
        // engine() waits for the rebuild on the matcher thread, so no test stubs a mock
        // while that thread may still be calling it
        engine = engine(10_000);
    }

    @AfterEach
    void stopMatcherThreads() {
        engine.stop();
    }

    @Test
    void startupRestsEveryOpenLimitOrder() {
        assertThat(engine.restingOrders("AAPL")).isEqualTo(2);
    }

    @Test
    void aQuoteFillsOnlyTheOrdersTheHouseCrosses() {
        quoteAt("85");
        when(execution.fillAgainstHouse(argThat(order -> order.id() == 11L), any()))
                .thenReturn(new Result(bid90, List.of(new BookChange.Remove(11L))));

        engine.onQuote(new Quote("AAPL", new BigDecimal("85"), Instant.now()));

        assertThat(engine.restingOrders("AAPL")).isEqualTo(1); // queued behind the sweep
        verify(execution, never()).fillAgainstHouse(argThat(order -> order.id() == 12L), any());
    }

    @Test
    void anOrderThatFailsToFillStaysInTheBook() {
        quoteAt("85");
        when(execution.fillAgainstHouse(any(), any())).thenThrow(new IllegalStateException("database hiccup"));

        engine.onQuote(new Quote("AAPL", new BigDecimal("85"), Instant.now()));

        assertThat(engine.restingOrders("AAPL")).isEqualTo(2);
    }

    @Test
    void aCommittedPlacementUpdatesTheBookFromWhatItReports() {
        Order bid70 = limit(13L, 3L, "70");
        when(execution.place(any(), any(), any(), any(), any(), any())).thenReturn(new Result(bid70, List.of(
                new BookChange.Reduce(11L, 1), new BookChange.Rest(RestingOrder.of(bid70)))));

        assertThat(engine.place(3L, request, null, "AAPL")).isSameAs(bid70);

        assertThat(engine.restingOrders("AAPL")).isEqualTo(2); // 11 filled and left, 13 joined
        assertThat(engine.book("AAPL", 5).bids()).extracting(OrderBook.Level::price)
                .containsExactly(new BigDecimal("80"), new BigDecimal("70"));
    }

    @Test
    void aRejectedPlacementLeavesTheBookAloneAndReachesTheCaller() {
        when(execution.place(any(), any(), any(), any(), any(), any()))
                .thenThrow(new OrderRejectedException("insufficient cash"));

        assertThatThrownBy(() -> engine.place(3L, request, null, "AAPL"))
                .isInstanceOf(OrderRejectedException.class)
                .hasMessage("insufficient cash");
        assertThat(engine.restingOrders("AAPL")).isEqualTo(2);
    }

    @Test
    void aBookOutOfSyncIsReloadedFromTheDatabaseAndThePlacementRetried() {
        Order bid70 = limit(13L, 3L, "70");
        when(execution.place(any(), any(), any(), any(), any(), any()))
                .thenThrow(new BookOutOfSyncException("order 11 no longer matches its row"))
                .thenReturn(new Result(bid70, List.of()));

        assertThat(engine.place(3L, request, null, "AAPL")).isSameAs(bid70);

        verify(execution, times(2)).place(any(), any(), any(), any(), any(), any());
        verify(repository, times(2)).findBySymbolAndStatusOrderByCreatedAtAscIdAsc("AAPL", OrderStatus.OPEN);
    }

    @Test
    void eachSymbolMatchesOnItsOwnThread() {
        AtomicReference<String> thread = new AtomicReference<>();
        when(execution.place(any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            thread.set(Thread.currentThread().getName());
            return new Result(bid90, List.of());
        });

        engine.place(3L, request, null, "AAPL");

        assertThat(thread.get()).isEqualTo("match-AAPL");
    }

    // Reads come from the snapshot the matcher publishes after every change, so polling
    // the book or the house's prices never queues behind an order in its transaction.
    @Test
    void readingTheBookDoesNotWaitBehindAnOrder() throws Exception {
        when(execution.place(any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            Thread.sleep(800);
            return new Result(bid90, List.of());
        });
        var slow = Executors.newVirtualThreadPerTaskExecutor().submit(() -> engine.place(3L, request, null, "AAPL"));
        Thread.sleep(100); // the order is now inside its transaction on the AAPL matcher

        long start = System.nanoTime();
        var book = engine.book("AAPL", 5);
        engine.leanBps("AAPL");
        engine.estimatePrice("AAPL", OrderSide.BUY, 10);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(millis).isLessThan(200);
        assertThat(book.bids()).hasSize(2);
        assertThat(slow.get(5, TimeUnit.SECONDS)).isSameAs(bid90);
    }

    // A 503 must mean nothing happened. An order still queued when the wait runs out is
    // cancelled before it starts, so retrying it is safe.
    @Test
    void anOrderStillQueuedWhenTheWaitRunsOutNeverRuns() throws Exception {
        MatchingEngine impatient = engine(150);
        try {
            when(execution.place(any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
                Thread.sleep(500); // the first order holds the AAPL matcher
                return new Result(bid90, List.of());
            });
            var first = Executors.newVirtualThreadPerTaskExecutor().submit(() -> impatient.place(1L, request, null, "AAPL"));
            Thread.sleep(50);

            assertThatThrownBy(() -> impatient.place(2L, request, null, "AAPL"))
                    .isInstanceOf(EngineBusyException.class)
                    .hasMessageContaining("nothing was done");
            assertThat(first.get(5, TimeUnit.SECONDS)).isSameAs(bid90); // the first one ran past the wait
            impatient.restingOrders("AAPL"); // let the matcher drain
            verify(execution, times(1)).place(any(), any(), any(), any(), any(), any());
        } finally {
            impatient.stop();
        }
    }

    // An order already running when the wait runs out is inside its transaction and will
    // finish, so the caller gets its real outcome, not a 503 that might be untrue.
    @Test
    void anOrderAlreadyRunningWhenTheWaitRunsOutReturnsItsRealResult() {
        MatchingEngine impatient = engine(100);
        try {
            when(execution.place(any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
                Thread.sleep(400);
                return new Result(bid80, List.of());
            });

            assertThat(impatient.place(3L, request, null, "AAPL")).isSameAs(bid80);
        } finally {
            impatient.stop();
        }
    }

    // Interrupting the caller is the same race as a timeout: once the order is running it
    // will commit, so the caller gets the real result, and the interrupt is kept.
    @Test
    void aCallerInterruptedWhileItsOrderRunsStillGetsTheRealResult() throws Exception {
        when(execution.place(any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            Thread.sleep(400);
            return new Result(bid80, List.of());
        });
        AtomicReference<Object> outcome = new AtomicReference<>();
        AtomicReference<Boolean> stillInterrupted = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                outcome.set(engine.place(3L, request, null, "AAPL"));
            } catch (RuntimeException e) {
                outcome.set(e);
            }
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        Thread.sleep(100); // the order is inside its transaction
        caller.interrupt();
        caller.join(5_000);

        assertThat(outcome.get()).isSameAs(bid80);
        assertThat(stillInterrupted.get()).isTrue();
    }

    // A snapshot refreshes only when the book changes, so a feed that goes stale under it
    // must not leave a live-looking house quote on show while orders are refused.
    @Test
    void aStaleFeedTakesTheHouseOutOfTheBookView() {
        Quote quote = new Quote("AAPL", new BigDecimal("200"), Instant.now());
        when(quotes.get("AAPL")).thenReturn(Optional.of(quote));
        when(quotes.isTradable(quote)).thenReturn(true);
        engine.tick("AAPL"); // refreshes the snapshot with the house in it; nothing crosses at 200
        assertThat(engine.book("AAPL", 5).house()).isNotNull();

        when(quotes.isTradable(quote)).thenReturn(false); // the provider stops answering
        assertThat(engine.book("AAPL", 5).house()).isNull();
        assertThat(engine.book("AAPL", 5).bids()).hasSize(2); // the users' book is still there
        assertThat(engine.estimatePrice("AAPL", OrderSide.BUY, 1)).isNull();
    }

    private MatchingEngine engine(long timeoutMs) {
        MatchingEngine built = new MatchingEngine(repository, trades, execution, quotes, PriceImpact.none(), 0,
                "house", Clock.systemUTC(), timeoutMs);
        built.rebuild();
        built.restingOrders("AAPL"); // wait for the rebuild before anything stubs a mock
        return built;
    }

    private void quoteAt(String price) {
        Quote quote = new Quote("AAPL", new BigDecimal(price), Instant.now());
        when(quotes.get("AAPL")).thenReturn(Optional.of(quote));
        when(quotes.isTradable(quote)).thenReturn(true);
    }

    private static Order limit(Long id, Long accountId, String price) {
        Order order = Order.limit(accountId, "AAPL", OrderSide.BUY, 1, new BigDecimal(price), Instant.EPOCH);
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }
}
