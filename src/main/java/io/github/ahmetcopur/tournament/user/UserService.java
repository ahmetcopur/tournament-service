package io.github.ahmetcopur.tournament.user;

import io.github.ahmetcopur.tournament.common.ApiException;
import io.github.ahmetcopur.tournament.common.ErrorCode;
import io.github.ahmetcopur.tournament.config.GameProperties;
import io.github.ahmetcopur.tournament.config.TimeSource;
import io.github.ahmetcopur.tournament.tournament.ScoringService;
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
        if (users.levelUp(id, props.coinsPerLevel()) == 0) {
            throw new ApiException(ErrorCode.USER_NOT_FOUND);
        }
        // Scoring reads the clock itself, after it also holds the group row: a level up that
        // arrived at 19:59:59.9 but waited on a lock past 20:00 must not score.
        scoring.recordLevelPassed(id);
        return users.findById(id).orElseThrow();
    }
}
