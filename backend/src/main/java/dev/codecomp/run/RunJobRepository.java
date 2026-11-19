package dev.codecomp.run;

import dev.codecomp.run.RunJob.Status;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Every state change is a conditional UPDATE ("only if it is still QUEUED / still mine"). The database applies each one
 * atomically, so two workers can never both claim or complete the same job, on Postgres or H2 alike.
 */
public interface RunJobRepository extends JpaRepository<RunJob, UUID> {

    @Query("select j.id from RunJob j where j.status = dev.codecomp.run.RunJob.Status.QUEUED order by j.createdAt, j.id")
    List<UUID> queuedIds(Pageable page);

    long countByStatus(Status status);

    long countByUserIdAndStatusIn(UUID userId, Collection<Status> statuses);

    @Modifying(clearAutomatically = true)
    @Query("""
            update RunJob j set j.status = dev.codecomp.run.RunJob.Status.RUNNING, j.worker = :worker,
                j.startedAt = :now, j.heartbeatAt = :now, j.attempts = j.attempts + 1
            where j.id = :id and j.status = dev.codecomp.run.RunJob.Status.QUEUED""")
    int claim(@Param("id") UUID id, @Param("worker") String worker, @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query("""
            update RunJob j set j.status = dev.codecomp.run.RunJob.Status.DONE, j.result = :result, j.finishedAt = :now
            where j.id = :id and j.status = dev.codecomp.run.RunJob.Status.RUNNING and j.worker = :worker""")
    int complete(@Param("id") UUID id, @Param("worker") String worker, @Param("result") String result, @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query("""
            update RunJob j set j.status = dev.codecomp.run.RunJob.Status.FAILED, j.error = :error, j.finishedAt = :now
            where j.id = :id and j.status = dev.codecomp.run.RunJob.Status.RUNNING and j.worker = :worker""")
    int fail(@Param("id") UUID id, @Param("worker") String worker, @Param("error") String error, @Param("now") Instant now);

    /** Puts a claimed job back without counting the attempt, for when the worker could not start it. */
    @Modifying(clearAutomatically = true)
    @Query("""
            update RunJob j set j.status = dev.codecomp.run.RunJob.Status.QUEUED, j.worker = null, j.attempts = j.attempts - 1
            where j.id = :id and j.status = dev.codecomp.run.RunJob.Status.RUNNING and j.worker = :worker""")
    int release(@Param("id") UUID id, @Param("worker") String worker);

    @Modifying
    @Query("""
            update RunJob j set j.heartbeatAt = :now
            where j.id in :ids and j.status = dev.codecomp.run.RunJob.Status.RUNNING and j.worker = :worker""")
    int heartbeat(@Param("ids") Collection<UUID> ids, @Param("worker") String worker, @Param("now") Instant now);

    /** Jobs whose worker stopped sending heartbeats (crashed or killed) go back in the queue... */
    @Modifying
    @Query("""
            update RunJob j set j.status = dev.codecomp.run.RunJob.Status.QUEUED, j.worker = null
            where j.status = dev.codecomp.run.RunJob.Status.RUNNING and j.heartbeatAt < :cutoff and j.attempts < :maxAttempts""")
    int requeueStale(@Param("cutoff") Instant cutoff, @Param("maxAttempts") int maxAttempts);

    /** ...unless they already took down workers too often, which suggests the job itself is the problem. */
    @Modifying
    @Query("""
            update RunJob j set j.status = dev.codecomp.run.RunJob.Status.FAILED, j.error = :error, j.finishedAt = :now
            where j.status = dev.codecomp.run.RunJob.Status.RUNNING and j.heartbeatAt < :cutoff and j.attempts >= :maxAttempts""")
    int failStale(@Param("cutoff") Instant cutoff, @Param("maxAttempts") int maxAttempts, @Param("error") String error, @Param("now") Instant now);

    @Modifying
    @Query("""
            delete from RunJob j
            where j.status in (dev.codecomp.run.RunJob.Status.DONE, dev.codecomp.run.RunJob.Status.FAILED) and j.finishedAt < :cutoff""")
    int deleteFinishedBefore(@Param("cutoff") Instant cutoff);
}
