package io.github.nishantshah0.papertrader.trading;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderEventRepository extends JpaRepository<OrderEvent, Long> {

    // events after a sequence number, oldest first, optionally for one symbol or account
    @Query("""
            select e from OrderEvent e
            where e.seq > :after
              and (:symbol is null or e.symbol = :symbol)
              and (:accountId is null or e.accountId = :accountId)
            order by e.seq
            """)
    List<OrderEvent> after(long after, String symbol, Long accountId, Limit limit);

    List<OrderEvent> findByOrderIdOrderBySeq(Long orderId);
}
