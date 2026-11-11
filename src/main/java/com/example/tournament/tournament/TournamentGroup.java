package com.example.tournament.tournament;

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
@Table(name = "tournament_groups")
public class TournamentGroup {

    public enum Status { WAITING, ACTIVE, ENDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tournament_id", nullable = false)
    private long tournamentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "member_count", nullable = false)
    private int memberCount;

    @Column(name = "started_at")
    private Instant startedAt;

    protected TournamentGroup() {
    }

    public static TournamentGroup waiting(long tournamentId) {
        TournamentGroup group = new TournamentGroup();
        group.tournamentId = tournamentId;
        group.status = Status.WAITING;
        return group;
    }

    /** Call only while holding the row lock. Returns true when this member filled the group. */
    public boolean addMember(int groupSize, Instant now) {
        if (status != Status.WAITING) {
            throw new IllegalStateException("Group " + id + " is " + status);
        }
        memberCount++;
        if (memberCount >= groupSize) {
            status = Status.ACTIVE;
            startedAt = now;
            return true;
        }
        return false;
    }

    public Long getId() {
        return id;
    }

    public long getTournamentId() {
        return tournamentId;
    }

    public Status getStatus() {
        return status;
    }

    public int getMemberCount() {
        return memberCount;
    }

    public Instant getStartedAt() {
        return startedAt;
    }
}
