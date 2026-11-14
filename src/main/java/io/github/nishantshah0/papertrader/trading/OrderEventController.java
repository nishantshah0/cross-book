package io.github.nishantshah0.papertrader.trading;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// The market-wide order event log, oldest first. Page through it by passing the last
// seq you saw as after. This is an operator's view of every account, like the rest of
// this unauthenticated practice app.
@RestController
class OrderEventController {

    private final OrderEventRepository events;

    OrderEventController(OrderEventRepository events) {
        this.events = events;
    }

    @GetMapping("/api/events")
    @Transactional(readOnly = true)
    List<OrderEventResponse> events(@RequestParam(defaultValue = "0") @Min(0) long after,
            @RequestParam(defaultValue = "500") @Min(1) @Max(1000) int limit,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) Long accountId) {
        String key = symbol == null ? null : symbol.toUpperCase(Locale.ROOT);
        return events.after(after, key, accountId, Limit.of(limit)).stream().map(OrderEventResponse::from).toList();
    }
}
