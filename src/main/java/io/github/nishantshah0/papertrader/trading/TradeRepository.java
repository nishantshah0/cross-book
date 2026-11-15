package io.github.nishantshah0.papertrader.trading;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeRepository extends JpaRepository<Trade, Long> {

    List<Trade> findByAccountIdOrderByExecutedAtDesc(Long accountId);

    // the symbol's most recent trade; both sides of a fill share its price
    Optional<Trade> findFirstBySymbolOrderByIdDesc(String symbol);

    // the most recent trade between two users, leaving out fills against the house
    Optional<Trade> findFirstBySymbolAndCounterpartyOrderIdIsNotNullOrderByIdDesc(String symbol);
}
