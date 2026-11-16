package dev.codecomp.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RepoExerciseRepository extends JpaRepository<RepoExercise, Long> {

    Optional<RepoExercise> findBySlug(String slug);

    List<RepoExercise> findAllByOrderBySortOrderAsc();
}
