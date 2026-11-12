package com.example.tournament.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.tournament.common.ApiException;
import com.example.tournament.common.ErrorCode;
import com.example.tournament.config.GameProperties;
import com.example.tournament.config.TimeSource;
import com.example.tournament.tournament.ScoringService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

    @Mock
    private UserRepository users;
    @Mock
    private ScoringService scoring;
    @Mock
    private CountryPicker countryPicker;
    @Mock
    private ApplicationEventPublisher events;

    private UserService service;

    @BeforeEach
    void setUp() {
        GameProperties props = new GameProperties(5000, List.of("TR", "US", "GB", "DE", "FR"), 25,
                new GameProperties.TournamentRules(Duration.ZERO, Duration.ofHours(20), 20, 1000,
                        List.of(10_000L, 5_000L)),
                new GameProperties.LeaderboardRules(Duration.ofDays(2)));
        service = new UserService(users, scoring, countryPicker, props,
                new TimeSource(Clock.fixed(NOW, ZoneOffset.UTC)), events);
    }

    @Test
    void levelUpAddsLevelAndCoinsThenRecordsTheScore() {
        User updated = new User("alice", "TR", 5025, NOW);
        when(users.levelUp(7L, 25L)).thenReturn(1);
        when(users.findById(7L)).thenReturn(Optional.of(updated));

        assertThat(service.levelUp(7L)).isSameAs(updated);

        InOrder order = inOrder(users, scoring);
        order.verify(users).levelUp(7L, 25L);
        order.verify(scoring).recordLevelPassed(7L, NOW);
        order.verify(users).findById(7L);
    }

    @Test
    void levelUpForUnknownUserFailsWithoutScoring() {
        when(users.levelUp(9L, 25L)).thenReturn(0);

        assertThatThrownBy(() -> service.levelUp(9L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
        verifyNoInteractions(scoring);
    }
}
