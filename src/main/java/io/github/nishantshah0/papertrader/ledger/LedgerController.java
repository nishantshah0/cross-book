package io.github.nishantshah0.papertrader.ledger;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class LedgerController {

    private final LedgerAudit audit;

    LedgerController(LedgerAudit audit) {
        this.audit = audit;
    }

    // runs every check against the current books; balanced is false if any failed
    @GetMapping("/api/ledger")
    LedgerReport ledger() {
        return audit.run();
    }
}
