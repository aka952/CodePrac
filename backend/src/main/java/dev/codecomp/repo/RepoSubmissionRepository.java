package dev.codecomp.repo;

import dev.codecomp.repo.RepoSubmission.Kind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface RepoSubmissionRepository extends JpaRepository<RepoSubmission, Long> {

    boolean existsByUserIdAndRepoExerciseIdAndKindIn(UUID userId, Long repoExerciseId, Collection<Kind> kinds);

    long countByUserIdAndRepoExerciseIdAndKind(UUID userId, Long repoExerciseId, Kind kind);

    List<RepoSubmission> findTop20ByUserIdAndRepoExerciseIdAndKindOrderByIdDesc(UUID userId, Long repoExerciseId, Kind kind);

    @Query("select distinct s.repoExerciseId from RepoSubmission s where s.userId = :userId and s.kind <> dev.codecomp.repo.RepoSubmission.Kind.ASK")
    List<Long> attemptedIds(@Param("userId") UUID userId);

    @Query("select distinct s.repoExerciseId from RepoSubmission s where s.userId = :userId and s.kind = dev.codecomp.repo.RepoSubmission.Kind.FIX and s.maxScore > 0 and s.score = s.maxScore")
    List<Long> solvedIds(@Param("userId") UUID userId);
}
