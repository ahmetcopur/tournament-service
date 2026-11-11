package com.example.tournament.user;

/** Published inside the sign-up transaction; the search index picks it up after commit. */
public record UserCreated(long id, String username, String country) {
}
