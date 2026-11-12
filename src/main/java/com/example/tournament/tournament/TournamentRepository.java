package com.example.tournament.tournament;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TournamentRepository extends JpaRepository<Tournament, Long> {

    Optional<Tournament> findByStartsAt(Instant startsAt);

    List<Tournament> findByStatus(Tournament.Status status);

    /** Oldest first, so a backlog settles in the order the tournaments ended. */
    List<Tournament> findByStatusAndEndsAtLessThanEqualOrderByEndsAtAsc(Tournament.Status status, Instant now);

    /** Finished tournaments whose final board rebuild hasn't succeeded yet, ended after {@code since}. */
    List<Tournament> findByStatusAndBoardsFinalizedAtIsNullAndEndsAtAfter(Tournament.Status status, Instant since);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE tournaments SET boards_finalized_at = :at WHERE id = :id", nativeQuery = true)
    int markBoardsFinalized(@Param("id") long id, @Param("at") Instant at);

    /** The UNIQUE rule on starts_at lets several app instances run this at once; one insert wins. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO tournaments (starts_at, ends_at, status)
            VALUES (:startsAt, :endsAt, 'ACTIVE')
            ON CONFLICT (starts_at) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("startsAt") Instant startsAt, @Param("endsAt") Instant endsAt);

    /**
     * The open tournament, share-locked. Joins hold this lock until they commit, and the
     * 20:00 job's UPDATE waits for it, so no join can land after the job has listed the groups.
     */
    @Query(value = """
            SELECT * FROM tournaments
            WHERE status = 'ACTIVE' AND starts_at <= :now AND ends_at > :now
            FOR SHARE
            """, nativeQuery = true)
    Optional<Tournament> findOpenForShare(@Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE tournaments SET status = 'ENDED' WHERE id = :id AND status = 'ACTIVE'", nativeQuery = true)
    int markEnded(@Param("id") long id);
}
