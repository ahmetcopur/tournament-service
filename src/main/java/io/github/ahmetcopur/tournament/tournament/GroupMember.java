package io.github.ahmetcopur.tournament.tournament;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "group_members")
public class GroupMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private long groupId;

    @Column(name = "tournament_id", nullable = false)
    private long tournamentId;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(nullable = false, length = 2)
    private String country;

    @Column(nullable = false)
    private int score;

    /** When the current score was reached. For score 0 that is the join time. */
    @Column(name = "score_updated_at")
    private Instant scoreUpdatedAt;

    @Column(name = "final_rank")
    private Integer finalRank;

    @Column(name = "reward_coins", nullable = false)
    private int rewardCoins;

    @Column(name = "reward_claimed", nullable = false)
    private boolean rewardClaimed;

    /** What this player paid to enter; refunded as is if the group never fills. */
    @Column(name = "entry_fee", nullable = false, updatable = false)
    private long entryFee;

    protected GroupMember() {
    }

    public GroupMember(long groupId, long tournamentId, long userId, String country, long entryFee, Instant joinedAt) {
        this.groupId = groupId;
        this.tournamentId = tournamentId;
        this.userId = userId;
        this.country = country;
        this.entryFee = entryFee;
        this.score = 0;
        this.scoreUpdatedAt = joinedAt;
    }

    public Long getId() {
        return id;
    }

    public long getGroupId() {
        return groupId;
    }

    public long getTournamentId() {
        return tournamentId;
    }

    public long getUserId() {
        return userId;
    }

    public String getCountry() {
        return country;
    }

    public int getScore() {
        return score;
    }

    public Instant getScoreUpdatedAt() {
        return scoreUpdatedAt;
    }

    public Integer getFinalRank() {
        return finalRank;
    }

    public int getRewardCoins() {
        return rewardCoins;
    }

    public boolean isRewardClaimed() {
        return rewardClaimed;
    }

    public long getEntryFee() {
        return entryFee;
    }
}
