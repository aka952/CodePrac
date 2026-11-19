package dev.codecomp.exercise;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExerciseRepository extends JpaRepository<Exercise, Long> {

    Optional<Exercise> findBySlug(String slug);

    List<Exercise> findAllByOrderBySortOrderAsc();
}
