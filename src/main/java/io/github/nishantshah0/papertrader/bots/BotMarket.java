package io.github.nishantshah0.papertrader.bots;

import io.github.nishantshah0.papertrader.quote.QuoteCache;
import io.github.nishantshah0.papertrader.simulation.Simulation;
import io.github.nishantshah0.papertrader.trading.MatchingEngine;
import io.github.nishantshah0.papertrader.trading.Order;
import io.github.nishantshah0.papertrader.trading.OrderBook.Level;
import io.github.nishantshah0.papertrader.trading.OrderRejectedException;
import io.github.nishantshah0.papertrader.trading.OrderService;
import io.github.nishantshah0.papertrader.trading.OrderSide;
import io.github.nishantshah0.papertrader.trading.OrderStateException;
import io.github.nishantshah0.papertrader.trading.OrderType;
import io.github.nishantshah0.papertrader.trading.PlaceOrderRequest;
import io.github.nishantshah0.papertrader.trading.Position;
import io.github.nishantshah0.papertrader.trading.PositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

// What a bot can see and do. It goes through the same OrderService as the REST API,
// so a bot order is checked, matched, logged and audited exactly like a user's; only
// the HTTP hop is skipped. It also counts what the bots did, for the run report.
final class BotMarket {

    private final OrderService orders;
    private final MatchingEngine engine;
    private final QuoteCache quotes;
    private final PositionRepository positions;
    private final Simulation simulation;
    final List<String> symbols;
    final BigDecimal startingCash;

    long actions, placed, refused, cancelled, cancelsTooLate;

    BotMarket(OrderService orders, MatchingEngine engine, QuoteCache quotes, PositionRepository positions,
            Simulation simulation, List<String> symbols, BigDecimal startingCash) {
        this.orders = orders;
        this.engine = engine;
        this.quotes = quotes;
        this.positions = positions;
        this.simulation = simulation;
        this.symbols = symbols;
        this.startingCash = startingCash;
    }

    // the feed price: the stock's "true value", which only some bots get to look at
    double feed(String symbol) {
        return quotes.require(symbol).price().doubleValue();
    }

    Optional<BigDecimal> bestBid(String symbol) {
        return top(engine.book(symbol, 1).bids());
    }

    Optional<BigDecimal> bestAsk(String symbol) {
        return top(engine.book(symbol, 1).asks());
    }

    // the middle of the book, else the last trade, else the feed: what a trader who only
    // watches the screen would call the price
    double mid(String symbol) {
        var bid = bestBid(symbol);
        var ask = bestAsk(symbol);
        if (bid.isPresent() && ask.isPresent()) {
            return (bid.get().doubleValue() + ask.get().doubleValue()) / 2;
        }
        return engine.lastPrice(symbol).map(BigDecimal::doubleValue).orElseGet(() -> feed(symbol));
    }

    int shares(long accountId, String symbol) {
        return positions.findByAccountIdAndSymbol(accountId, symbol).map(Position::getQuantity).orElse(0);
    }

    void allocate(long accountId, String symbol, int shares) {
        if (shares > 0) {
            simulation.allocate(accountId, symbol, shares);
        }
    }

    Optional<Order> limit(long accountId, String symbol, OrderSide side, int shares, BigDecimal price) {
        return place(accountId, new PlaceOrderRequest(symbol, side, OrderType.LIMIT, shares, price));
    }

    Optional<Order> market(long accountId, String symbol, OrderSide side, int shares) {
        return place(accountId, new PlaceOrderRequest(symbol, side, OrderType.MARKET, shares, null));
    }

    // false if the order had already filled or been cancelled
    boolean cancel(long accountId, long orderId) {
        actions++;
        try {
            orders.cancel(accountId, orderId);
            cancelled++;
            return true;
        } catch (OrderStateException e) {
            cancelsTooLate++;
            return false;
        }
    }

    private Optional<Order> place(long accountId, PlaceOrderRequest request) {
        actions++;
        try {
            Order order = orders.place(accountId, request);
            placed++;
            return Optional.of(order);
        } catch (OrderRejectedException e) {
            refused++; // logged as a REJECTED event, like any refused order
            return Optional.empty();
        }
    }

    // a price a bot is willing to post: whole cents, rounded away from the other side so
    // a passive order stays passive
    static BigDecimal cents(double price, OrderSide side) {
        return BigDecimal.valueOf(price).setScale(2, side == OrderSide.BUY ? RoundingMode.FLOOR : RoundingMode.CEILING);
    }

    private static Optional<BigDecimal> top(List<Level> levels) {
        return levels.isEmpty() ? Optional.empty() : Optional.of(levels.getFirst().price());
    }
}
