package dev.codecomp.run;

import dev.codecomp.run.RunJob.Status;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Every state change is a conditional UPDATE that only moves a job forward (QUEUED → RUNNING → DONE/FAILED, never back).
 * Events arrive at least once and out of order, so a duplicate or late event matches no row and changes nothing.
 */
public interface RunJobRepository extends JpaRepository<RunJob, UUID> {

    @Query("select j.id from RunJob j where j.status = dev.codecomp.run.RunJob.Status.QUEUED order by j.createdAt, j.id")
    List<UUID> queuedIds(Pageable page);

    long countByStatus(Status status);

    long countByUserIdAndStatusIn(UUID userId, Collection<Status> statuses);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update RunJob j set j.sentAt = :now where j.id = :id")
    int markSent(@Param("id") UUID id, @Param("now") Instant now);

    /** Queued runs whose message never reached the queue (the send failed, or this service stopped in between). */
    @Query("""
            select j.id from RunJob j
            where j.status = dev.codecomp.run.RunJob.Status.QUEUED and j.sentAt is null and j.createdAt < :cutoff""")
    List<UUID> unsentBefore(@Param("cutoff") Instant cutoff);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update RunJob j set j.status = dev.codecomp.run.RunJob.Status.RUNNING, j.worker = :worker, j.startedAt = :now
            where j.id = :id and j.status = dev.codecomp.run.RunJob.Status.QUEUED""")
    int markRunning(@Param("id") UUID id, @Param("worker") String worker, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update RunJob j set j.status = dev.codecomp.run.RunJob.Status.DONE, j.result = :result, j.finishedAt = :now
            where j.id = :id and j.status in (dev.codecomp.run.RunJob.Status.QUEUED, dev.codecomp.run.RunJob.Status.RUNNING)""")
    int complete(@Param("id") UUID id, @Param("result") String result, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update RunJob j set j.status = dev.codecomp.run.RunJob.Status.FAILED, j.error = :error, j.finishedAt = :now
            where j.id = :id and j.status in (dev.codecomp.run.RunJob.Status.QUEUED, dev.codecomp.run.RunJob.Status.RUNNING)""")
    int fail(@Param("id") UUID id, @Param("error") String error, @Param("now") Instant now);

    /** The last resort when every event about a job was lost: fail it rather than leave the browser waiting forever. */
    @Transactional
    @Modifying
    @Query("""
            update RunJob j set j.status = dev.codecomp.run.RunJob.Status.FAILED, j.error = :error, j.finishedAt = :now
            where j.status in (dev.codecomp.run.RunJob.Status.QUEUED, dev.codecomp.run.RunJob.Status.RUNNING) and j.createdAt < :cutoff""")
    int failOlderThan(@Param("cutoff") Instant cutoff, @Param("error") String error, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("""
            delete from RunJob j
            where j.status in (dev.codecomp.run.RunJob.Status.DONE, dev.codecomp.run.RunJob.Status.FAILED) and j.finishedAt < :cutoff""")
    int deleteFinishedBefore(@Param("cutoff") Instant cutoff);
}
