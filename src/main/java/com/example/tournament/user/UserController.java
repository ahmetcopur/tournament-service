package com.example.tournament.user;

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

    public UserController(UserService users, RewardService rewards) {
        this.users = users;
        this.rewards = rewards;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return UserResponse.from(users.create(request.username()));
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable long id) {
        return UserResponse.from(users.get(id));
    }

    @PostMapping("/{id}/levels")
    public UserResponse levelUp(@PathVariable long id) {
        return UserResponse.from(users.levelUp(id));
    }

    @PostMapping("/{id}/rewards/claim")
    public UserResponse claimReward(@PathVariable long id) {
        return UserResponse.from(rewards.claim(id));
    }
}
