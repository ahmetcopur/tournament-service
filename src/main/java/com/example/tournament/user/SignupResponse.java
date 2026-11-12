package com.example.tournament.user;

import com.example.tournament.security.TokenService.IssuedToken;
import java.time.Instant;

/** The User fields plus the player's bearer token, which every later request must carry. */
public record SignupResponse(long id, String username, String country, int level, long coins,
                             String token, Instant tokenExpiresAt) {

    public static SignupResponse of(User user, IssuedToken token) {
        return new SignupResponse(user.getId(), user.getUsername(), user.getCountry(), user.getLevel(),
                user.getCoins(), token.token(), token.expiresAt());
    }
}
