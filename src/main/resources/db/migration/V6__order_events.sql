-- An append-only log of everything that happens to an order: placed, each fill, and how
-- it ended. Events are written in the same transaction as the change they describe, so
-- the log never disagrees with the orders and trades tables. seq numbers events in the
-- order they were written; one matcher thread writes each symbol's events, so within a
-- symbol seq is also the order things happened.
create table order_events (
    seq                   bigint generated always as identity primary key,
    occurred_at           timestamptz not null,
    type                  varchar(16) not null
        check (type in ('PLACED', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED', 'REJECTED')),
    -- null only for an order refused before it was ever placed
    order_id              bigint references orders (id),
    account_id            bigint not null references accounts (id),
    symbol                varchar(10) not null,
    side                  varchar(4) not null check (side in ('BUY', 'SELL')),
    order_type            varchar(6) not null check (order_type in ('MARKET', 'LIMIT')),
    quantity              integer not null check (quantity > 0),
    limit_price           numeric(18, 4),
    -- the order's filled quantity once this event has happened
    filled_quantity       integer not null check (filled_quantity >= 0),
    trade_id              bigint unique references trades (id),
    fill_quantity         integer check (fill_quantity > 0),
    fill_price            numeric(18, 4) check (fill_price > 0),
    -- the other user's order in a fill; null when the house took the other side
    counterparty_order_id bigint references orders (id),
    reason                varchar(500),
    constraint order_events_fills_carry_a_trade check (
        (type in ('PARTIALLY_FILLED', 'FILLED'))
            = (trade_id is not null and fill_quantity is not null and fill_price is not null)),
    constraint order_events_only_rejections_lack_an_order check (order_id is not null or type = 'REJECTED')
);

create index order_events_by_order_idx on order_events (order_id, seq);
create index order_events_by_symbol_idx on order_events (symbol, seq);
create index order_events_by_account_idx on order_events (account_id, seq);

-- Rebuild the history of orders placed before the log existed: placed when created,
-- one event per trade, then cancelled or rejected when last updated. Attempts that were
-- refused outright left no rows, so they cannot be recovered.
insert into order_events (occurred_at, type, order_id, account_id, symbol, side, order_type, quantity, limit_price,
                          filled_quantity, trade_id, fill_quantity, fill_price, counterparty_order_id, reason)
select occurred_at, type, order_id, account_id, symbol, side, order_type, quantity, limit_price,
       filled_quantity, trade_id, fill_quantity, fill_price, counterparty_order_id, reason
from (
    select o.created_at as occurred_at, 0 as phase, o.id as tiebreak, 'PLACED' as type, o.id as order_id,
           o.account_id, o.symbol, o.side, o.order_type, o.quantity, o.limit_price,
           0 as filled_quantity, null::bigint as trade_id, null::integer as fill_quantity,
           null::numeric as fill_price, null::bigint as counterparty_order_id, null::varchar as reason
    from orders o
    union all
    select t.executed_at, 1, t.id,
           case when sum(t.quantity) over w = o.quantity then 'FILLED' else 'PARTIALLY_FILLED' end,
           o.id, o.account_id, o.symbol, o.side, o.order_type, o.quantity, o.limit_price,
           sum(t.quantity) over w, t.id, t.quantity, t.price, t.counterparty_order_id, null
    from trades t
    join orders o on o.id = t.order_id
    window w as (partition by t.order_id order by t.id)
    union all
    select o.updated_at, 2, o.id, o.status, o.id, o.account_id, o.symbol, o.side, o.order_type, o.quantity,
           o.limit_price, o.filled_quantity, null, null, null, null, 'recorded before the event log existed'
    from orders o
    where o.status in ('CANCELLED', 'REJECTED')
) history
order by occurred_at, phase, tiebreak;
