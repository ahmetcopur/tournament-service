package com.example.tournament.tournament;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {

    boolean existsByTournamentIdAndUserId(long tournamentId, long userId);

    boolean existsByGroupIdAndCountry(long groupId, String country);

    Optional<GroupMember> findByTournamentIdAndUserId(long tournamentId, long userId);

    List<GroupMember> findByGroupIdOrderByIdAsc(long groupId);

    @Query("""
            select count(m) > 0 from GroupMember m
            where m.userId = :userId and m.rewardCoins > 0 and m.rewardClaimed = false
            """)
    boolean hasUnclaimedReward(@Param("userId") long userId);

    /** Same order as the Redis board and the 20:00 ranking: score, then who got there first. */
    @Query("""
            select m from GroupMember m where m.groupId = :groupId
            order by m.score desc, m.scoreUpdatedAt asc nulls last, m.id asc
            """)
    List<GroupMember> findRanked(@Param("groupId") long groupId);

    @Query("""
            select m from GroupMember m where m.tournamentId = :tournamentId and m.groupId in (
                select g.id from TournamentGroup g where g.tournamentId = :tournamentId and g.startedAt is not null)
            order by m.groupId, m.id
            """)
    List<GroupMember> findInStartedGroups(@Param("tournamentId") long tournamentId);

    @Query("""
            select m.country, sum(m.score) from GroupMember m
            where m.tournamentId = :tournamentId and m.groupId in (
                select g.id from TournamentGroup g where g.tournamentId = :tournamentId and g.startedAt is not null)
            group by m.country
            """)
    List<Object[]> countryTotals(@Param("tournamentId") long tournamentId);

    /**
     * The user's membership in an ACTIVE group of a tournament that hasn't ended, with the
     * group row share-locked until commit. The 20:00 job's UPDATE on the group waits for that
     * lock, and once the group is ENDED this query finds nothing, so no point lands after ranking.
     */
    @Query(value = """
            SELECT m.* FROM group_members m
            JOIN tournament_groups g ON g.id = m.group_id
            JOIN tournaments t ON t.id = m.tournament_id
            WHERE m.user_id = :userId AND g.status = 'ACTIVE' AND t.ends_at > :now
            ORDER BY m.tournament_id DESC
            LIMIT 1
            FOR SHARE OF g
            """, nativeQuery = true)
    Optional<GroupMember> findScoringMembershipForShare(@Param("userId") long userId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE group_members SET score = score + 1, score_updated_at = :now
            WHERE id = :id
            """, nativeQuery = true)
    int incrementScore(@Param("id") long id, @Param("now") Instant now);

    /** Final ranks for every group that filled up: score, then who reached it first. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE group_members gm SET final_rank = ranked.rnk
            FROM (
                SELECT m.id, ROW_NUMBER() OVER (
                           PARTITION BY m.group_id
                           ORDER BY m.score DESC, m.score_updated_at ASC NULLS LAST, m.id ASC) AS rnk
                FROM group_members m
                JOIN tournament_groups g ON g.id = m.group_id
                WHERE g.tournament_id = :tournamentId AND g.started_at IS NOT NULL
            ) ranked
            WHERE gm.id = ranked.id
            """, nativeQuery = true)
    int assignFinalRanks(@Param("tournamentId") long tournamentId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE group_members SET reward_coins = :coins
            WHERE tournament_id = :tournamentId AND final_rank = :rank
            """, nativeQuery = true)
    int assignReward(@Param("tournamentId") long tournamentId, @Param("rank") int rank, @Param("coins") long coins);
}
