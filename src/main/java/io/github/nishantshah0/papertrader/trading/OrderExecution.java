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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
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

    // houseVolume is how many shares users bought from the house in this transaction,
    // negative when they sold to it: what moves the house's prices afterwards. Zero when
    // the house was not involved, for a cancel, and for a retry. lastPrice is the price of
    // the transaction's last fill, or null when nothing traded.
    record Result(Order order, List<BookChange> changes, int houseVolume, BigDecimal lastPrice) {
        Result(Order order, List<BookChange> changes) {
            this(order, changes, 0, null);
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
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final MarketMode mode;

    OrderExecution(AccountRepository accounts, OrderRepository orders, TradeRepository trades,
            PositionRepository positions, HousePositionRepository house, OrderEventRepository eventLog,
            Clock clock, ApplicationEventPublisher events, @Value("${papertrader.market.mode:house}") String mode) {
        this.mode = MarketMode.parse(mode);
        this.accounts = accounts;
        this.orders = orders;
        this.trades = trades;
        this.positions = positions;
        this.house = house;
        this.eventLog = eventLog;
        this.clock = clock;
        this.events = events;
    }

    // Places an order and matches it: first against other users' resting orders the
    // book offers, then against the house for what is left, if the house price is
    // within the limit. A limit order with shares left rests in the book. In book mode
    // there is no house: a market order takes what the book offers and the rest is
    // cancelled, and one that would find nothing at all is refused.
    //
    // A trade between two users touches two accounts. Both rows are locked in ID order,
    // the same order every matcher thread uses, so two symbols matching the same pair of
    // accounts at once cannot deadlock.
    @Transactional
    public Result place(Long accountId, PlaceOrderRequest request, String key, String symbol, OrderBook book,
            HouseMarket house) {
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
        boolean houseMode = mode == MarketMode.HOUSE;
        if (type == OrderType.MARKET && houseMode && house == null) {
            throw new OrderRejectedException("quote is stale; wait for a fresh market quote");
        }

        List<PlannedFill> planned = book.match(request.side(), request.limitPrice(), request.quantity(),
                accountId, house == null ? null : house.touch());
        if (type == OrderType.MARKET && !houseMode && planned.isEmpty()) {
            throw new OrderRejectedException("no " + (request.side() == OrderSide.BUY ? "sell" : "buy")
                    + " orders in the " + symbol + " book to trade with");
        }
        TreeSet<Long> involved = new TreeSet<>();
        involved.add(accountId);
        planned.forEach(fill -> involved.add(fill.maker().accountId()));
        Map<Long, Account> locked = lockInIdOrder(involved);
        Account account = locked.get(accountId);
        Holdings holdings = new Holdings();

        Order order = type == OrderType.LIMIT
                ? Order.limit(accountId, symbol, request.side(), request.quantity(), request.limitPrice(), now)
                : Order.market(accountId, symbol, request.side(), request.quantity(), now);
        // What a buy can cost at most: a limit order its limit for every share; a market
        // order in house mode the house's price for its whole size; and in book mode
        // exactly the resting orders it was matched with, since nothing else will fill it.
        BigDecimal maxCost;
        if (type == OrderType.LIMIT) {
            maxCost = notional(order.getLimitPrice(), order.getQuantity());
        } else if (houseMode) {
            maxCost = notional(house.price(order.getSide(), order.getQuantity()), order.getQuantity());
        } else {
            maxCost = planned.stream().map(fill -> notional(fill.price(), fill.quantity()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        checkAvailable(account, order, maxCost, holdings);
        order.setIdempotencyKey(key);
        try {
            orders.save(order);
        } catch (DataIntegrityViolationException e) {
            if (key == null) {
                throw e;
            }
            // The key's unique index caught a placement on another symbol, run by another
            // matcher, that committed after the check above. Its symbol differs, so it is a
            // different order, and the answer is the same as if it had come first.
            throw new OrderStateException("Idempotency-Key was already used for a different order");
        }
        eventLog.save(OrderEvent.placed(order));

        List<BookChange> changes = new ArrayList<>();
        BigDecimal lastPrice = null;
        // one query loads every maker; the lookups below then come from the session
        orders.findAllById(planned.stream().map(fill -> fill.maker().id()).toList());
        for (PlannedFill fill : planned) {
            Order maker = orders.findById(fill.maker().id())
                    .filter(row -> row.isOpen() && row.remainingQuantity() >= fill.quantity())
                    .orElseThrow(() -> new BookOutOfSyncException("order " + fill.maker().id()
                            + " in the " + symbol + " book no longer matches its row"));
            Account makerAccount = locked.get(maker.getAccountId());
            if (!canSettle(makerAccount, maker, fill.quantity(), fill.price(), holdings)) {
                // only possible for an order placed before reservations existed
                maker.reject(now);
                eventLog.save(OrderEvent.rejected(maker, "could not pay for a fill of " + fill.quantity()
                        + " at " + fill.price()));
                changes.add(new BookChange.Remove(maker.getId()));
                publish(maker);
                continue;
            }
            settle(account, order, fill.quantity(), fill.price(), maker.getId(), now, holdings);
            settle(makerAccount, maker, fill.quantity(), fill.price(), order.getId(), now, holdings);
            lastPrice = fill.price();
            changes.add(new BookChange.Reduce(maker.getId(), fill.quantity()));
            publish(maker);
        }
        // the house prices what is left along its impact curve, and a limit order takes
        // only the shares that price keeps within its limit
        int houseVolume = 0;
        if (order.isOpen() && house != null) {
            int shares = house.fillable(order.getSide(), order.getLimitPrice(), order.remainingQuantity());
            if (shares > 0) {
                lastPrice = house.price(order.getSide(), shares);
                settle(account, order, shares, lastPrice, null, now, holdings);
                houseVolume = order.getSide() == OrderSide.BUY ? shares : -shares;
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
        return new Result(order, changes, houseVolume, lastPrice);
    }

    // Hands an account shares before a simulated run starts, the way an issuer would: it
    // buys them from the house at the given price, as a filled market order. A book
    // market has no other source of shares, so a run starts by allocating them. It goes
    // through the house's books like any house fill, so every ledger check still holds,
    // and it is not a market trade, so it leaves the last price alone.
    @Transactional
    public Result allocate(Long accountId, String symbol, int shares, BigDecimal price) {
        Instant now = clock.instant();
        Account account = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new NotFoundException("account " + accountId + " not found"));
        Order order = Order.market(accountId, symbol, OrderSide.BUY, shares, now);
        Holdings holdings = new Holdings();
        checkAvailable(account, order, notional(price, shares), holdings);
        orders.save(order);
        eventLog.save(OrderEvent.placed(order));
        settle(account, order, shares, price, null, now, holdings);
        publish(order);
        return new Result(order, List.of());
    }

    // An order refused before it was placed rolls back its own transaction, so the
    // refusal is recorded afterwards in a transaction of its own.
    @Transactional
    public void recordRefusal(Long accountId, String symbol, PlaceOrderRequest request, String reason) {
        if (accounts.existsById(accountId)) {
            eventLog.save(OrderEvent.refused(accountId, symbol, request, reason, clock.instant()));
        }
    }

    // A resting order the house's quote has moved through. The house fills as much of it
    // as its curve allows within the order's limit, at the curve price, exactly as if the
    // order had just arrived: resting gets no discount. What is left stays in the book.
    @Transactional
    public Result fillAgainstHouse(RestingOrder resting, HouseMarket house) {
        Instant now = clock.instant();
        long orderId = resting.id();
        Account account = accounts.findByIdForUpdate(resting.accountId()).orElseThrow();
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new BookOutOfSyncException("order " + orderId + " is in the book but not the database"));
        if (!order.isOpen()) {
            return new Result(order, List.of(new BookChange.Remove(orderId)));
        }
        int shares = house == null ? 0 : house.fillable(order.getSide(), order.getLimitPrice(), order.remainingQuantity());
        if (shares == 0) {
            return new Result(order, List.of());
        }
        BigDecimal price = house.price(order.getSide(), shares);
        Holdings holdings = new Holdings();
        if (!canSettle(account, order, shares, price, holdings)) {
            order.reject(now);
            eventLog.save(OrderEvent.rejected(order, "could not pay for a fill of " + shares + " at " + price));
            publish(order);
            return new Result(order, List.of(new BookChange.Remove(orderId)));
        }
        settle(account, order, shares, price, null, now, holdings);
        publish(order);
        BookChange change = order.isOpen() ? new BookChange.Reduce(orderId, shares) : new BookChange.Remove(orderId);
        return new Result(order, List.of(change), order.getSide() == OrderSide.BUY ? shares : -shares, price);
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

    // one statement locks them all, still in ID order (see AccountRepository)
    private Map<Long, Account> lockInIdOrder(TreeSet<Long> accountIds) {
        Map<Long, Account> locked = new TreeMap<>();
        for (Account account : accountIds.size() == 1
                ? accounts.findByIdForUpdate(accountIds.first()).stream().toList()
                : accounts.findAllByIdForUpdate(accountIds)) {
            locked.put(account.getId(), account);
        }
        for (Long id : accountIds) {
            if (!locked.containsKey(id)) {
                throw new NotFoundException("account " + id + " not found");
            }
        }
        return locked;
    }

    // A new order may only use what open limit orders have not already claimed, so a
    // resting order stays funded until it fills or is cancelled. Buys reserve at their
    // limit and fill at or below it, which means the hold always covers the fill.
    private void checkAvailable(Account account, Order order, BigDecimal cost, Holdings holdings) {
        Reservations reserved = Reservations.of(
                orders.findByAccountIdAndStatusOrderByCreatedAtDesc(account.getId(), OrderStatus.OPEN));
        if (order.getSide() == OrderSide.BUY) {
            BigDecimal buyingPower = account.getCash().subtract(reserved.cash());
            if (buyingPower.compareTo(cost) < 0) {
                throw new OrderRejectedException("insufficient cash: order needs " + cost + ", buying power is "
                        + buyingPower.max(BigDecimal.ZERO) + " after open orders");
            }
            long held = holdings.held(account.getId(), order.getSymbol());
            if (held + order.getQuantity() > Integer.MAX_VALUE) {
                throw new OrderRejectedException("position quantity exceeds supported maximum");
            }
        } else {
            long free = holdings.held(account.getId(), order.getSymbol()) - reserved.shares(order.getSymbol());
            if (free < order.getQuantity()) {
                throw new OrderRejectedException("insufficient shares: order sells " + order.getQuantity() + " "
                        + order.getSymbol() + ", " + Math.max(free, 0) + " not already on open orders");
            }
        }
    }

    private boolean canSettle(Account account, Order order, int shares, BigDecimal price, Holdings holdings) {
        return order.getSide() == OrderSide.BUY
                ? account.canAfford(notional(price, shares))
                : holdings.held(account.getId(), order.getSymbol()) >= shares;
    }

    // One side of one fill: cash, position, order and trade row move together. With no
    // counterparty order the house is the other side, and its books move in the same
    // transaction, so every dollar and share a user gains, the house gives up.
    private void settle(Account account, Order order, int shares, BigDecimal price, Long counterpartyOrderId,
            Instant now, Holdings holdings) {
        BigDecimal notional = notional(price, shares);
        Position position = holdings.forFill(account.getId(), order.getSymbol(), now);
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

    // The positions one transaction touches, each read from the database once: the
    // balance checks and every fill on the same account and symbol share the row.
    private final class Holdings {
        private final Map<String, Optional<Position>> rows = new HashMap<>();

        int held(Long accountId, String symbol) {
            return find(accountId, symbol).map(Position::getQuantity).orElse(0);
        }

        // the row a fill moves, made if the account has never held the symbol
        Position forFill(Long accountId, String symbol, Instant now) {
            Position position = find(accountId, symbol).orElseGet(() -> new Position(accountId, symbol, now));
            rows.put(key(accountId, symbol), Optional.of(position));
            return position;
        }

        private Optional<Position> find(Long accountId, String symbol) {
            return rows.computeIfAbsent(key(accountId, symbol),
                    key -> positions.findByAccountIdAndSymbol(accountId, symbol));
        }

        private static String key(Long accountId, String symbol) {
            return accountId + "/" + symbol;
        }
    }

    private void publish(Order order) {
        events.publishEvent(new OrderChanged(order.getAccountId(), order.getId(), order.getStatus(),
                order.getFilledQuantity(), order.getQuantity()));
    }

    private static BigDecimal notional(BigDecimal price, int shares) {
        return price.multiply(BigDecimal.valueOf(shares)).setScale(2, RoundingMode.HALF_UP);
    }
}
