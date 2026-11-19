package dev.codecomp.repo;

import dev.codecomp.repo.PendingRun.Purpose;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface PendingRunRepository extends JpaRepository<PendingRun, UUID> {

    boolean existsByRepoExerciseIdAndPurposeAndCreatedAtAfter(Long repoExerciseId, Purpose purpose, Instant after);
}
