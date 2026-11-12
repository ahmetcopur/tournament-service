package io.github.ahmetcopur.tournament.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Game rules from application.yml. Changing a value here never requires a code change. */
@Validated
@ConfigurationProperties(prefix = "game")
public record GameProperties(
        @PositiveOrZero long startingCoins,
        @NotEmpty List<@Pattern(regexp = "[A-Z]{2}") String> countries,
        @PositiveOrZero long coinsPerLevel,
        @NotNull @Valid TournamentRules tournament,
        @NotNull @Valid LeaderboardRules leaderboard) {

    /** One player per country, so the group size is the number of countries. */
    public int groupSize() {
        return countries.size();
    }

    public record TournamentRules(
            @NotNull Duration opensAt,
            @NotNull Duration closesAt,
            @Positive int minLevel,
            @PositiveOrZero long entryFee,
            @NotNull List<@PositiveOrZero Long> rewards) {

        public TournamentRules {
            if (opensAt != null && closesAt != null
                    && (opensAt.isNegative() || closesAt.compareTo(opensAt) <= 0 || closesAt.compareTo(Duration.ofHours(24)) > 0)) {
                throw new IllegalArgumentException(
                        "game.tournament needs 0h <= opens-at < closes-at <= 24h, got " + opensAt + " and " + closesAt);
            }
        }
    }

    /**
     * @param countryShards slices of each tournament's country board; each slice is its own Redis
     *                      Cluster slot, so country traffic spreads over that many nodes. Changing
     *                      it starts fresh boards (the count is part of the key), rebuilt from Postgres.
     * @param countryBoardCache how long an instance reuses a summed country board. Reading it costs
     *                      one Redis call per slice; it is one total for the whole tournament, so a
     *                      second of staleness buys a lot. Zero turns the cache off.
     */
    public record LeaderboardRules(@NotNull Duration ttlAfterEnd, @DefaultValue("8") @Positive int countryShards,
                                   @DefaultValue("1s") Duration countryBoardCache) {
    }
}
