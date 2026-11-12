package com.example.tournament.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Coins never go through read-modify-write. Every change is a single UPDATE whose WHERE
 * clause carries the rule, so two requests racing on one balance can't both pass.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByUsername(String username);

    /** Keyset pagination for re-indexing players into Elasticsearch. */
    List<User> findTop500ByIdGreaterThanOrderByIdAsc(long id);

    /**
     * Serialises joins for one user, so the entry checks see committed state. FOR NO KEY UPDATE
     * is the lock an UPDATE of non-key columns takes: it still conflicts with other joins, level
     * ups and claims of this player, but unlike FOR UPDATE it doesn't block foreign-key checks
     * from rows that reference the player.
     */
    @Query(value = "SELECT * FROM users WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Optional<User> findByIdForUpdate(@Param("id") long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE users SET level = level + 1, coins = coins + :coins WHERE id = :id", nativeQuery = true)
    int levelUp(@Param("id") long id, @Param("coins") long coins);

    /** Zero rows means the rule failed and the join is rejected. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE users SET coins = coins - :fee
            WHERE id = :id AND level >= :minLevel AND coins >= :fee
            """, nativeQuery = true)
    int chargeEntryFee(@Param("id") long id, @Param("fee") long fee, @Param("minLevel") int minLevel);

    /**
     * Claims every unclaimed reward and credits it in one statement. When two claims race,
     * the second one waits on the member row, then finds reward_claimed = true and changes nothing.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            WITH claimed AS (
                UPDATE group_members SET reward_claimed = true
                WHERE user_id = :id AND reward_coins > 0 AND reward_claimed = false
                RETURNING reward_coins
            )
            UPDATE users SET coins = coins + (SELECT sum(reward_coins) FROM claimed)
            WHERE id = :id AND EXISTS (SELECT 1 FROM claimed)
            """, nativeQuery = true)
    int claimRewards(@Param("id") long id);

    /** Gives everyone whose group never filled up back exactly the fee they paid. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE users u SET coins = u.coins + m.entry_fee
            FROM group_members m
            JOIN tournament_groups g ON g.id = m.group_id
            WHERE m.user_id = u.id AND g.tournament_id = :tournamentId AND g.started_at IS NULL
            """, nativeQuery = true)
    int refundUnstartedEntries(@Param("tournamentId") long tournamentId);
}
