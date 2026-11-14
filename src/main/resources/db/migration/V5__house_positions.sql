-- The house's books, one row per symbol. The house is the other side of every trade a
-- user does not make with another user. Its quantity goes negative when it has sold
-- more than it has bought, and its cash is what it has taken in minus what it has paid
-- out, so it can go negative too.
--
-- With these rows in place nothing is created or destroyed by trading: for every symbol
-- the users' positions and the house's quantity sum to zero, and the users' cash plus
-- the house's cash always equals the cash the accounts started with.
create table house_positions (
    symbol     varchar(10) primary key,
    quantity   bigint not null default 0,
    cash       numeric(20, 2) not null default 0,
    updated_at timestamptz not null default now()
);

-- every trade so far without a counterparty order was against the house; notional is
-- rounded per trade to the cent, the same way settlement rounds it
insert into house_positions (symbol, quantity, cash)
select symbol,
       sum(case when side = 'BUY' then -quantity else quantity end),
       sum(case when side = 'BUY' then 1 else -1 end * round(price * quantity, 2))
from trades
where counterparty_order_id is null
group by symbol;
