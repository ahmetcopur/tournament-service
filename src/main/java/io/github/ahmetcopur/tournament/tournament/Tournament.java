package io.github.ahmetcopur.tournament.tournament;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "tournaments")
public class Tournament {

    public enum Status { ACTIVE, ENDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    /** When the Redis boards were rebuilt from the final values; null until that succeeds. */
    @Column(name = "boards_finalized_at")
    private Instant boardsFinalizedAt;

    protected Tournament() {
    }

    public Instant getBoardsFinalizedAt() {
        return boardsFinalizedAt;
    }

    public Long getId() {
        return id;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isOpenAt(Instant now) {
        return status == Status.ACTIVE && !now.isBefore(startsAt) && now.isBefore(endsAt);
    }
}
