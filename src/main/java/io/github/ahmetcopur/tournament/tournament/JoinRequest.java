package io.github.ahmetcopur.tournament.tournament;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record JoinRequest(@NotNull @Positive Long userId) {
}
