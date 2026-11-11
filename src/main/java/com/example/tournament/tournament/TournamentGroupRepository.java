package com.example.tournament.tournament;

import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TournamentGroupRepository extends JpaRepository<TournamentGroup, Long> {

    /**
     * Locks the oldest WAITING group that has no player from this country. SKIP LOCKED means
     * a group another join is filling right now is passed over instead of waited on.
     *
     * <p>The NOT EXISTS runs on this statement's snapshot. A join that committed into the group
     * after that snapshot is invisible here, so the caller re-checks once it holds the lock.
     */
    @Query(value = """
            SELECT g.id FROM tournament_groups g
            WHERE g.tournament_id = :tournamentId
              AND g.status = 'WAITING'
              AND g.id NOT IN (:excluded)
              AND NOT EXISTS (
                  SELECT 1 FROM group_members m WHERE m.group_id = g.id AND m.country = :country)
            ORDER BY g.id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Long> lockWaitingGroupWithout(@Param("tournamentId") long tournamentId,
                                           @Param("country") String country,
                                           @Param("excluded") Collection<Long> excluded);

    /** Waits for in-flight level ups (they share-lock the group), then ends every group. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE tournament_groups SET status = 'ENDED'
            WHERE tournament_id = :tournamentId AND status <> 'ENDED'
            """, nativeQuery = true)
    int endAll(@Param("tournamentId") long tournamentId);
}
