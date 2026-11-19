package dev.codecomp.exercise;

import dev.codecomp.exercise.Exercise.Difficulty;
import dev.codecomp.grading.Grader;
import dev.codecomp.grading.Points;
import dev.codecomp.exercise.ExerciseApi.AttemptRequest;
import dev.codecomp.exercise.ExerciseApi.AttemptResult;
import dev.codecomp.exercise.ExerciseApi.ExerciseSummary;
import dev.codecomp.exercise.ExerciseApi.ExerciseView;
import dev.codecomp.exercise.ExerciseApi.Solution;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
        var all = exercises.findAllByOrderBySortOrderAsc();
        var points = earned(user, all);
        return all.stream()
                .filter(e -> language == null || e.getLanguage().equals(language))
                .filter(e -> difficulty == null || e.getDifficulty() == difficulty)
                .map(e -> new ExerciseSummary(e.getSlug(), e.getTitle(), e.getLanguage(), e.getType(), e.getDifficulty(), e.getTags(),
                        solved.contains(e.getId()) ? "SOLVED" : attempted.contains(e.getId()) ? "ATTEMPTED" : "NEW",
                        points.getOrDefault(e.getId(), 0), Points.snippetMax(e.getDifficulty())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ExerciseView get(UUID user, String slug) {
        var e = find(slug);
        boolean solved = attempts.existsByUserIdAndExerciseIdAndCorrectTrue(user, e.getId());
        return new ExerciseView(e.getSlug(), e.getTitle(), e.getLanguage(), e.getType(), e.getDifficulty(), e.getTags(),
                e.getCode(), e.getQuestion(), e.getHint(), solved, solved ? solution(e) : null,
                earned(user, List.of(e)).getOrDefault(e.getId(), 0), Points.snippetMax(e.getDifficulty()));
    }

    @Transactional
    public AttemptResult attempt(UUID user, String slug, AttemptRequest req) {
        var e = find(slug);
        boolean correct = !req.giveUp() && Grader.isCorrect(e.getType(), e.getAnswer(), req.answer());
        attempts.save(new Attempt(user, e.getId(), req.giveUp() ? null : req.answer(), correct));
        long count = attempts.countByUserIdAndExerciseId(user, e.getId());
        boolean reveal = correct || req.giveUp() || count >= REVEAL_AFTER_ATTEMPTS;
        return new AttemptResult(correct, count, reveal ? solution(e) : null, earned(user, List.of(e)).getOrDefault(e.getId(), 0));
    }

    @Transactional(readOnly = true)
    public int totalPoints(UUID user) {
        return earned(user, exercises.findAll()).values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Points per exercise id, replayed from the attempt history: the first correct answer scores by how many attempts
     * it took, and nothing can be earned once the answer has been revealed (give-up or the third wrong attempt).
     */
    private Map<Long, Integer> earned(UUID user, Collection<Exercise> scope) {
        var difficulty = scope.stream().collect(Collectors.toMap(Exercise::getId, Exercise::getDifficulty));
        var tries = new HashMap<Long, Integer>();
        var closed = new HashSet<Long>();
        var points = new HashMap<Long, Integer>();
        for (var a : attempts.findByUserIdOrderByIdAsc(user)) {
            Long id = a.getExerciseId();
            if (!difficulty.containsKey(id) || closed.contains(id)) continue;
            int n = tries.merge(id, 1, Integer::sum);
            if (a.isCorrect()) {
                points.put(id, Points.snippet(difficulty.get(id), n));
                closed.add(id);
            } else if (a.getAnswer() == null || n >= REVEAL_AFTER_ATTEMPTS) {
                closed.add(id);
            }
        }
        return points;
    }

    private static Solution solution(Exercise e) { return new Solution(e.getAnswer(), e.getExplanation()); }

    private Exercise find(String slug) {
        return exercises.findBySlug(slug).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exercise not found: " + slug));
    }
}
