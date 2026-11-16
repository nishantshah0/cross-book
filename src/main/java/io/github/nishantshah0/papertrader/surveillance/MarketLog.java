package io.github.nishantshah0.papertrader.surveillance;

import java.util.List;
import java.util.Map;

// Everything a detector gets to see: the orders as the event log tells them, in the
// order they were placed, and each account's public username. It is read from the
// order_events table alone, which is what a surveillance desk receives from an
// exchange. Nothing here comes from the answer key.
public record MarketLog(List<LoggedOrder> orders, Map<Long, String> usernames) {
}
