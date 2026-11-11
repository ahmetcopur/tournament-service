package com.example.tournament.user;

/** The doc's User shape: {id, username, country, level, coins}. */
public record UserResponse(long id, String username, String country, int level, long coins) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getCountry(), user.getLevel(), user.getCoins());
    }
}
