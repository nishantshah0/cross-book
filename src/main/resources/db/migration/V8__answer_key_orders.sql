-- The answer key, order by order: which of a cheater's orders were fake (SPOOF, never
-- meant to trade) and which were the trade it was after (REAL), grouped into numbered
-- episodes. Lets a detector be graded on the exact orders it flags, not only on the
-- accounts. Kept apart from the market data like the answer_key table itself.
create table answer_key_orders (
    order_id   bigint primary key references orders (id),
    account_id bigint      not null references answer_key (account_id),
    episode    integer     not null check (episode > 0),
    role       varchar(8)  not null check (role in ('SPOOF', 'REAL'))
);

create index answer_key_orders_by_account_idx on answer_key_orders (account_id, episode);
