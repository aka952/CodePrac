package dev.codecomp.exercise;

import dev.codecomp.exercise.Exercise.Difficulty;
import dev.codecomp.exercise.ExerciseApi.AttemptRequest;
import dev.codecomp.exercise.ExerciseApi.AttemptResult;
import dev.codecomp.exercise.ExerciseApi.ExerciseSummary;
import dev.codecomp.exercise.ExerciseApi.ExerciseView;
import dev.codecomp.exercise.ExerciseApi.PointsTotal;
import dev.codecomp.platform.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
class ExerciseController {

    private final ExerciseService exercises;

    ExerciseController(ExerciseService exercises) {
        this.exercises = exercises;
    }

    @GetMapping("/exercises")
    List<ExerciseSummary> list(@CurrentUser UUID user,
                               @RequestParam(name = "language", required = false) String language,
                               @RequestParam(name = "difficulty", required = false) Difficulty difficulty) {
        return exercises.list(user, language, difficulty);
    }

    @GetMapping("/exercises/{slug}")
    ExerciseView get(@CurrentUser UUID user, @PathVariable("slug") String slug) {
        return exercises.get(user, slug);
    }

    @PostMapping("/exercises/{slug}/attempts")
    AttemptResult attempt(@CurrentUser UUID user, @PathVariable("slug") String slug, @Valid @RequestBody AttemptRequest req) {
        return exercises.attempt(user, slug, req);
    }

    @GetMapping("/points/snippets")
    PointsTotal points(@CurrentUser UUID user) {
        return new PointsTotal(exercises.totalPoints(user));
    }
}
