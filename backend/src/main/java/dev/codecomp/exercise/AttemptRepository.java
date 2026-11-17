package dev.codecomp.exercise;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AttemptRepository extends JpaRepository<Attempt, Long> {

    long countByUserIdAndExerciseId(UUID userId, Long exerciseId);

    List<Attempt> findByUserIdOrderByIdAsc(UUID userId);

    boolean existsByUserIdAndExerciseIdAndCorrectTrue(UUID userId, Long exerciseId);

    @Query("select distinct a.exerciseId from Attempt a where a.userId = :userId")
    List<Long> attemptedIds(@Param("userId") UUID userId);

    @Query("select distinct a.exerciseId from Attempt a where a.userId = :userId and a.correct = true")
    List<Long> solvedIds(@Param("userId") UUID userId);
}
