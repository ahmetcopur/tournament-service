package io.github.ahmetcopur.tournament.common;

/** Every error body: {code, message}. */
public record ApiError(String code, String message) {

    public static ApiError of(ErrorCode code, String message) {
        return new ApiError(code.name(), message);
    }
}
