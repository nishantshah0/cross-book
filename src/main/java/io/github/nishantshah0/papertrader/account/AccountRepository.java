package io.github.nishantshah0.papertrader.account;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long> {

    // row lock so two orders on the same account cannot both spend the same cash
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);

    // Several row locks in one statement. Postgres locks the rows as they come out of
    // the sort, so they are still taken in ID order, the order every matcher uses.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);

    // with the username, for responses built outside the transaction
    @Query("select a from Account a join fetch a.user where a.id = :id")
    Optional<Account> findWithUserById(@Param("id") Long id);
}
