package dev.codecomp.exercise;

import dev.codecomp.exercise.Exercise.Difficulty;
import dev.codecomp.grading.Grader;
import dev.codecomp.web.Api.AttemptRequest;
import dev.codecomp.web.Api.AttemptResult;
import dev.codecomp.web.Api.ExerciseSummary;
import dev.codecomp.web.Api.ExerciseView;
import dev.codecomp.web.Api.Solution;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ExerciseService {

    static final int REVEAL_AFTER_ATTEMPTS = 3;

    private final ExerciseRepository exercises;
    private final AttemptRepository attempts;

    public ExerciseService(ExerciseRepository exercises, AttemptRepository attempts) {
        this.exercises = exercises;
        this.attempts = attempts;
    }

    @Transactional(readOnly = true)
    public List<ExerciseSummary> list(UUID user, String language, Difficulty difficulty) {
        var attempted = Set.copyOf(attempts.attemptedIds(user));
        var solved = Set.copyOf(attempts.solvedIds(user));
        return exercises.findAllByOrderBySortOrderAsc().stream()
                .filter(e -> language == null || e.getLanguage().equals(language))
                .filter(e -> difficulty == null || e.getDifficulty() == difficulty)
                .map(e -> new ExerciseSummary(e.getSlug(), e.getTitle(), e.getLanguage(), e.getType(), e.getDifficulty(), e.getTags(),
                        solved.contains(e.getId()) ? "SOLVED" : attempted.contains(e.getId()) ? "ATTEMPTED" : "NEW"))
                .toList();
    }

    @Transactional(readOnly = true)
    public ExerciseView get(UUID user, String slug) {
        var e = find(slug);
        boolean solved = attempts.existsByUserIdAndExerciseIdAndCorrectTrue(user, e.getId());
        return new ExerciseView(e.getSlug(), e.getTitle(), e.getLanguage(), e.getType(), e.getDifficulty(), e.getTags(),
                e.getCode(), e.getQuestion(), e.getHint(), solved, solved ? solution(e) : null);
    }

    @Transactional
    public AttemptResult attempt(UUID user, String slug, AttemptRequest req) {
        var e = find(slug);
        boolean correct = !req.giveUp() && Grader.isCorrect(e.getType(), e.getAnswer(), req.answer());
        attempts.save(new Attempt(user, e.getId(), req.giveUp() ? null : req.answer(), correct));
        long count = attempts.countByUserIdAndExerciseId(user, e.getId());
        boolean reveal = correct || req.giveUp() || count >= REVEAL_AFTER_ATTEMPTS;
        return new AttemptResult(correct, count, reveal ? solution(e) : null);
    }

    private static Solution solution(Exercise e) { return new Solution(e.getAnswer(), e.getExplanation()); }

    private Exercise find(String slug) {
        return exercises.findBySlug(slug).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exercise not found: " + slug));
    }
}
