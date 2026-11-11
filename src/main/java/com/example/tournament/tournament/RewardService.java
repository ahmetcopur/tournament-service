package com.example.tournament.tournament;

import com.example.tournament.common.ApiException;
import com.example.tournament.common.ErrorCode;
import com.example.tournament.user.User;
import com.example.tournament.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RewardService {

    private final UserRepository users;

    public RewardService(UserRepository users) {
        this.users = users;
    }

    /** Marks the reward claimed and adds the coins in one statement; a second claim finds nothing. */
    @Transactional
    public User claim(long userId) {
        if (!users.existsById(userId)) {
            throw new ApiException(ErrorCode.USER_NOT_FOUND);
        }
        if (users.claimRewards(userId) == 0) {
            throw new ApiException(ErrorCode.NO_REWARD);
        }
        return users.findById(userId).orElseThrow();
    }
}
