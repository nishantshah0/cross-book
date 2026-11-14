package io.github.nishantshah0.papertrader.account;

import io.github.nishantshah0.papertrader.NotFoundException;
import io.github.nishantshah0.papertrader.PaperTraderProperties;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final UserRepository users;
    private final AccountRepository accounts;
    private final PaperTraderProperties properties;
    private final Clock clock;

    AccountService(UserRepository users, AccountRepository accounts, PaperTraderProperties properties, Clock clock) {
        this.users = users;
        this.accounts = accounts;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Account create(String username) {
        if (users.existsByUsername(username)) {
            throw new UsernameTakenException(username);
        }
        Instant now = clock.instant();
        User user = users.save(new User(username, now));
        return accounts.save(new Account(user, properties.startingCash(), now));
    }

    @Transactional(readOnly = true)
    public Account get(Long id) {
        return accounts.findById(id).orElseThrow(() -> new NotFoundException("account " + id + " not found"));
    }
}
