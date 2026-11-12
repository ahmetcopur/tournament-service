package com.example.tournament.user;

import com.example.tournament.common.ApiException;
import com.example.tournament.common.ErrorCode;
import com.example.tournament.config.GameProperties;
import com.example.tournament.config.TimeSource;
import com.example.tournament.tournament.ScoringService;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository users;
    private final ScoringService scoring;
    private final CountryPicker countryPicker;
    private final GameProperties props;
    private final TimeSource time;
    private final ApplicationEventPublisher events;

    public UserService(UserRepository users, ScoringService scoring, CountryPicker countryPicker,
                       GameProperties props, TimeSource time, ApplicationEventPublisher events) {
        this.users = users;
        this.scoring = scoring;
        this.countryPicker = countryPicker;
        this.props = props;
        this.time = time;
        this.events = events;
    }

    @Transactional
    public User create(String username) {
        if (users.existsByUsername(username)) {
            throw new ApiException(ErrorCode.USERNAME_TAKEN);
        }
        // A racing insert with the same name still fails on uq_users_username.
        User user = users.saveAndFlush(new User(username, countryPicker.pick(), props.startingCoins(), time.now()));
        events.publishEvent(new UserCreated(user.getId(), user.getUsername(), user.getCountry()));
        return user;
    }

    @Transactional(readOnly = true)
    public User get(long id) {
        return users.findById(id).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
    }

    /**
     * +1 level and the level coins, plus +1 score if the user is in an ACTIVE group. All of it
     * is one transaction. The UPDATE on users runs first and holds the user's row lock, so
     * level ups for one user are applied one at a time.
     */
    @Transactional
    public User levelUp(long id) {
        Instant now = time.now();
        if (users.levelUp(id, props.coinsPerLevel()) == 0) {
            throw new ApiException(ErrorCode.USER_NOT_FOUND);
        }
        scoring.recordLevelPassed(id, now);
        return users.findById(id).orElseThrow();
    }
}
