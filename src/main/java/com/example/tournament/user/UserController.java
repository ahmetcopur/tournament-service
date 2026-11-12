package com.example.tournament.user;

import com.example.tournament.security.CurrentPlayer;
import com.example.tournament.security.TokenService;
import com.example.tournament.tournament.RewardService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService users;
    private final RewardService rewards;
    private final TokenService tokens;

    public UserController(UserService users, RewardService rewards, TokenService tokens) {
        this.users = users;
        this.rewards = rewards;
        this.tokens = tokens;
    }

    /** Open to anyone: creates the player and returns the token for all later requests. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse create(@Valid @RequestBody CreateUserRequest request) {
        User user = users.create(request.username());
        return SignupResponse.of(user, tokens.issue(user.getId()));
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable long id) {
        CurrentPlayer.requireSelf(id);
        return UserResponse.from(users.get(id));
    }

    @PostMapping("/{id}/levels")
    public UserResponse levelUp(@PathVariable long id) {
        CurrentPlayer.requireSelf(id);
        return UserResponse.from(users.levelUp(id));
    }

    @PostMapping("/{id}/rewards/claim")
    public UserResponse claimReward(@PathVariable long id) {
        CurrentPlayer.requireSelf(id);
        return UserResponse.from(rewards.claim(id));
    }
}
