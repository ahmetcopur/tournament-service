package io.github.ahmetcopur.tournament.tournament;

import java.time.Instant;

public record TournamentResponse(long id, Instant startsAt, Instant endsAt, Tournament.Status status, boolean open) {
}
