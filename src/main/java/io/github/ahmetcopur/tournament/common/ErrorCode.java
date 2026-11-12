package io.github.ahmetcopur.tournament.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    // From the design doc
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "Unknown user id"),
    TOURNAMENT_CLOSED(HttpStatus.CONFLICT, "The tournament is closed right now"),
    LEVEL_TOO_LOW(HttpStatus.CONFLICT, "Level is below the entry level"),
    NOT_ENOUGH_COINS(HttpStatus.CONFLICT, "Not enough coins for the entry fee"),
    UNCLAIMED_REWARD(HttpStatus.CONFLICT, "A reward from an earlier tournament is still unclaimed"),
    ALREADY_JOINED(HttpStatus.CONFLICT, "Already in today's tournament"),
    NO_REWARD(HttpStatus.NOT_FOUND, "Nothing to claim"),

    // Added for cases the doc leaves open
    USERNAME_TAKEN(HttpStatus.CONFLICT, "Username is already taken"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Missing, invalid or expired token"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "You can only act as your own player"),
    TOURNAMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Unknown tournament id"),
    GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "Unknown group id"),
    NOT_IN_TOURNAMENT(HttpStatus.NOT_FOUND, "User did not join this tournament"),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Invalid request"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "No such endpoint"),
    CONFLICT(HttpStatus.CONFLICT, "Conflicting update, please retry"),
    SEARCH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Player search is unavailable right now"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
