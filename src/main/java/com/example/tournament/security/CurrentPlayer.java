package com.example.tournament.security;

import com.example.tournament.common.ApiException;
import com.example.tournament.common.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Authorization for player actions: a token only lets you act as the player it was issued for. */
public final class CurrentPlayer {

    private CurrentPlayer() {
    }

    /**
     * Throws FORBIDDEN unless the caller's token is for {@code userId}. Checked before anything
     * else, so another player's id gives 403 whether or not that player exists.
     */
    public static void requireSelf(long userId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !Long.toString(userId).equals(authentication.getName())) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }
}
