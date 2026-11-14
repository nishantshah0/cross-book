package io.github.nishantshah0.papertrader.trading;

import io.github.nishantshah0.papertrader.NotFoundException;
import io.github.nishantshah0.papertrader.account.Account;
import io.github.nishantshah0.papertrader.account.AccountRepository;
import io.github.nishantshah0.papertrader.trading.OrderBook.PlannedFill;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// The database side of matching. Every method is one transaction, called by the
// MatchingEngine on the matcher thread that owns the symbol's book. A method may read
// the book but never changes it: it returns what changed, and the engine applies that
// to the book only after the transaction has committed.
@Service
class OrderExecution {

    // what a committed transaction did to the book
    sealed interface BookChange {
        record Reduce(long orderId, int shares) implements BookChange {
        }

        record Remove(long orderId) implements BookChange {
        }

        record Rest(RestingOrder order) implements BookChange {
        }
    }

    // takerShares is how many shares the order filled as the taker in this transaction:
    // what moves the price afterwards. Zero for a cancel, a quote-driven fill or a retry.
    record Result(Order order, List<BookChange> changes, int takerShares) {
        Result(Order order, List<BookChange> changes) {
            this(order, changes, 0);
        }
    }

    // thrown when a resting order in the book no longer matches its row; the engine
    // reloads the book from Postgres and retries
    static class BookOutOfSyncException extends IllegalStateException {
        BookOutOfSyncException(String message) {
            super(message);
        }
    }

    private final AccountRepository accounts;
    private final OrderRepository orders;
    private final TradeRepository trades;
    private final PositionRepository positions;
    private final HousePositionRepository house;
    private final OrderEventRepository eventLog;
    private final PriceImpact impact;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    OrderExecution(AccountRepository accounts, OrderRepository orders, TradeRepository trades,
            PositionRepository positions, HousePositionRepository house, OrderEventRepository eventLog,
            PriceImpact impact, Clock clock, ApplicationEventPublisher events) {
        this.accounts = accounts;
        this.orders = orders;
        this.trades = trades;
        this.positions = positions;
        this.house = house;
        this.eventLog = eventLog;
        this.impact = impact;
        this.clock = clock;
        this.events = events;
    }

    // Places an order and matches it: first against other users' resting orders the
    // book offers, then against the house for what is left, if the house price is
    // within the limit. A limit order with shares left rests in the book.
    //
    // A trade between two users touches two accounts. Both rows are locked in ID order,
    // the same order every matcher thread uses, so two symbols matching the same pair of
    // accounts at once cannot deadlock.
    @Transactional
    public Result place(Long accountId, PlaceOrderRequest request, String key, String symbol, OrderBook book,
            HouseQuote house) {
        if (key != null) {
            if (key.isBlank() || key.length() > 128) {
                throw new OrderRejectedException("Idempotency-Key must contain 1 to 128 characters");
            }
            var previous = orders.findByAccountIdAndIdempotencyKey(accountId, key);
            if (previous.isPresent()) {
                if (!previous.get().sameRequest(request)) {
                    throw new OrderStateException("Idempotency-Key was already used for a different order");
                }
                return new Result(previous.get(), List.of());
            }
        }
        // one instant for everything this transaction records
        Instant now = clock.instant();
        OrderType type = request.typeOrMarket();
        if ((type == OrderType.LIMIT) != (request.limitPrice() != null)) {
            throw new OrderRejectedException("limit orders require limitPrice; market orders must omit it");
        }
        if (type == OrderType.MARKET && house == null) {
            throw new OrderRejectedException("quote is stale; wait for a fresh market quote");
        }

        List<PlannedFill> planned = book.match(request.side(), request.limitPrice(), request.quantity(),
                accountId, house);
        TreeSet<Long> involved = new TreeSet<>();
        involved.add(accountId);
        planned.forEach(fill -> involved.add(fill.maker().accountId()));
        Map<Long, Account> locked = lockInIdOrder(involved);
        Account account = locked.get(accountId);

        Order order = type == OrderType.LIMIT
                ? Order.limit(accountId, symbol, request.side(), request.quantity(), request.limitPrice(), now)
                : Order.market(accountId, symbol, request.side(), request.quantity(), now);
        // a market buy pays at most the house's price for its whole size, so that bounds
        // what it can cost
        BigDecimal worstPrice = type == OrderType.LIMIT ? order.getLimitPrice()
                : impact.fillPrice(house, order.getSide(), order.getQuantity());
        checkAvailable(account, order, worstPrice);
        order.setIdempotencyKey(key);
        orders.save(order);
        eventLog.save(OrderEvent.placed(order));

        List<BookChange> changes = new ArrayList<>();
        for (PlannedFill fill : planned) {
            Order maker = orders.findById(fill.maker().id())
                    .filter(row -> row.isOpen() && row.remainingQuantity() >= fill.quantity())
                    .orElseThrow(() -> new BookOutOfSyncException("order " + fill.maker().id()
                            + " in the " + symbol + " book no longer matches its row"));
            Account makerAccount = locked.get(maker.getAccountId());
            if (!canSettle(makerAccount, maker, fill.quantity(), fill.price())) {
                // only possible for an order placed before reservations existed
                maker.reject(now);
                eventLog.save(OrderEvent.rejected(maker, "could not pay for a fill of " + fill.quantity()
                        + " at " + fill.price()));
                changes.add(new BookChange.Remove(maker.getId()));
                publish(maker);
                continue;
            }
            settle(account, order, fill.quantity(), fill.price(), maker.getId(), now);
            settle(makerAccount, maker, fill.quantity(), fill.price(), order.getId(), now);
            changes.add(new BookChange.Reduce(maker.getId(), fill.quantity()));
            publish(maker);
        }
        // the house prices what is left by its size, and a limit order takes only the
        // shares that price keeps within its limit
        if (order.isOpen() && house != null) {
            int shares = impact.fillable(house, order.getSide(), order.getLimitPrice(), order.remainingQuantity());
            if (shares > 0) {
                settle(account, order, shares, impact.fillPrice(house, order.getSide(), shares), null, now);
            }
        }
        if (order.isOpen()) {
            if (type == OrderType.LIMIT) {
                changes.add(new BookChange.Rest(RestingOrder.of(order)));
            } else {
                order.cancel(now); // nothing left to trade with; the filled part stands
                eventLog.save(OrderEvent.cancelled(order, "market order: nothing left to trade with"));
            }
        }
        publish(order);
        return new Result(order, changes, order.getFilledQuantity());
    }

    // An order refused before it was placed rolls back its own transaction, so the
    // refusal is recorded afterwards in a transaction of its own.
    @Transactional
    public void recordRefusal(Long accountId, String symbol, PlaceOrderRequest request, String reason) {
        if (accounts.existsById(accountId)) {
            eventLog.save(OrderEvent.refused(accountId, symbol, request, reason, clock.instant()));
        }
    }

    // A resting order the house quote now crosses fills in full at the touch price: it was
    // resting, so the house came to it, and it pays nothing for size.
    @Transactional
    public Result fillAgainstHouse(RestingOrder resting, HouseQuote house) {
        Instant now = clock.instant();
        long orderId = resting.id();
        Account account = accounts.findByIdForUpdate(resting.accountId()).orElseThrow();
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new BookOutOfSyncException("order " + orderId + " is in the book but not the database"));
        if (!order.isOpen()) {
            return new Result(order, List.of(new BookChange.Remove(orderId)));
        }
        BigDecimal price = house == null ? null : house.priceFor(order.getSide());
        if (price == null || !order.crosses(price)) {
            return new Result(order, List.of());
        }
        if (!canSettle(account, order, order.remainingQuantity(), price)) {
            order.reject(now);
            eventLog.save(OrderEvent.rejected(order, "could not pay for a fill of " + order.remainingQuantity()
                    + " at " + price));
        } else {
            settle(account, order, order.remainingQuantity(), price, null, now);
        }
        publish(order);
        return new Result(order, List.of(new BookChange.Remove(orderId)));
    }

    @Transactional
    public Result cancel(Long accountId, Long orderId) {
        accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new NotFoundException("account " + accountId + " not found"));
        Order order = orders.findByIdAndAccountId(orderId, accountId)
                .orElseThrow(() -> new NotFoundException("order " + orderId + " not found"));
        if (!order.isOpen()) {
            throw new OrderStateException("order " + orderId + " is " + order.getStatus()
                    + ", only OPEN orders can be cancelled");
        }
        order.cancel(clock.instant());
        eventLog.save(OrderEvent.cancelled(order, "cancelled by the account"));
        publish(order);
        return new Result(order, List.of(new BookChange.Remove(orderId)));
    }

    private Map<Long, Account> lockInIdOrder(TreeSet<Long> accountIds) {
        Map<Long, Account> locked = new TreeMap<>();
        for (Long id : accountIds) {
            locked.put(id, accounts.findByIdForUpdate(id)
                    .orElseThrow(() -> new NotFoundException("account " + id + " not found")));
        }
        return locked;
    }

    // A new order may only use what open limit orders have not already claimed, so a
    // resting order stays funded until it fills or is cancelled. Buys reserve at their
    // limit and fill at or below it, which means the hold always covers the fill.
    private void checkAvailable(Account account, Order order, BigDecimal price) {
        Reservations reserved = Reservations.of(
                orders.findByAccountIdAndStatusOrderByCreatedAtDesc(account.getId(), OrderStatus.OPEN));
        if (order.getSide() == OrderSide.BUY) {
            BigDecimal cost = notional(price, order.getQuantity());
            BigDecimal buyingPower = account.getCash().subtract(reserved.cash());
            if (buyingPower.compareTo(cost) < 0) {
                throw new OrderRejectedException("insufficient cash: order needs " + cost + ", buying power is "
                        + buyingPower.max(BigDecimal.ZERO) + " after open orders");
            }
            long held = held(account.getId(), order.getSymbol());
            if (held + order.getQuantity() > Integer.MAX_VALUE) {
                throw new OrderRejectedException("position quantity exceeds supported maximum");
            }
        } else {
            long free = held(account.getId(), order.getSymbol()) - reserved.shares(order.getSymbol());
            if (free < order.getQuantity()) {
                throw new OrderRejectedException("insufficient shares: order sells " + order.getQuantity() + " "
                        + order.getSymbol() + ", " + Math.max(free, 0) + " not already on open orders");
            }
        }
    }

    private boolean canSettle(Account account, Order order, int shares, BigDecimal price) {
        return order.getSide() == OrderSide.BUY
                ? account.canAfford(notional(price, shares))
                : held(account.getId(), order.getSymbol()) >= shares;
    }

    // One side of one fill: cash, position, order and trade row move together. With no
    // counterparty order the house is the other side, and its books move in the same
    // transaction, so every dollar and share a user gains, the house gives up.
    private void settle(Account account, Order order, int shares, BigDecimal price, Long counterpartyOrderId,
            Instant now) {
        BigDecimal notional = notional(price, shares);
        Position position = positions.findByAccountIdAndSymbol(account.getId(), order.getSymbol())
                .orElseGet(() -> new Position(account.getId(), order.getSymbol(), now));
        if (order.getSide() == OrderSide.BUY) {
            if (!account.canAfford(notional)) {
                throw new OrderRejectedException("insufficient cash: fill costs " + notional
                        + ", account has " + account.getCash());
            }
            account.debit(notional);
            position.buy(shares, price, now);
        } else {
            if (position.getQuantity() < shares) {
                throw new OrderRejectedException("insufficient shares: fill sells " + shares + " "
                        + order.getSymbol() + ", account holds " + position.getQuantity());
            }
            position.sell(shares, price, now);
            account.credit(notional);
        }
        order.fill(shares, price, now);
        positions.save(position);
        Trade trade = trades.save(new Trade(order, shares, price, counterpartyOrderId, now));
        eventLog.save(OrderEvent.filled(order, trade));
        if (counterpartyOrderId == null) {
            HousePosition books = house.findById(order.getSymbol())
                    .orElseGet(() -> new HousePosition(order.getSymbol(), now));
            books.takeOtherSide(order.getSide(), shares, notional, now);
            house.save(books);
        }
    }

    private int held(Long accountId, String symbol) {
        return positions.findByAccountIdAndSymbol(accountId, symbol).map(Position::getQuantity).orElse(0);
    }

    private void publish(Order order) {
        events.publishEvent(new OrderChanged(order.getAccountId(), order.getId(), order.getStatus(),
                order.getFilledQuantity(), order.getQuantity()));
    }

    private static BigDecimal notional(BigDecimal price, int shares) {
        return price.multiply(BigDecimal.valueOf(shares)).setScale(2, RoundingMode.HALF_UP);
    }
}
