-- Who each bot account really is, for grading a detector after a run. It sits apart
-- from the market data on purpose: a detector reads orders, trades and events, never
-- this table, the same way a real surveillance desk never gets told who is cheating.
-- A simulation reset empties it with everything else.
create table answer_key (
    account_id bigint primary key references accounts (id),
    -- the bot's stable name, e.g. maker-03; its random streams are seeded from it
    bot        varchar(32) not null unique,
    kind       varchar(16) not null,
    cheater    boolean     not null
);
