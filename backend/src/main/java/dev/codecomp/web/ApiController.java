package dev.codecomp.web;

import dev.codecomp.exercise.Exercise.Difficulty;
import dev.codecomp.exercise.ExerciseService;
import dev.codecomp.repo.RepoService;
import dev.codecomp.run.RunService;
import dev.codecomp.sandbox.Sandbox;
import dev.codecomp.web.Api.AttemptRequest;
import dev.codecomp.web.Api.AttemptResult;
import dev.codecomp.web.Api.ExerciseSummary;
import dev.codecomp.web.Api.ExerciseView;
import dev.codecomp.web.Api.RepoSummary;
import dev.codecomp.web.Api.RepoView;
import dev.codecomp.web.Api.ReviewRequest;
import dev.codecomp.web.Api.ReviewResult;
import dev.codecomp.web.Api.RunRequest;
import dev.codecomp.web.Api.RunResponse;
import dev.codecomp.web.Api.TestRunRequest;
import dev.codecomp.web.Api.TestRunResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@RestController
@RequestMapping("/api")
class ApiController {

    private static final String USER = "X-User-Id";

    private final ExerciseService exercises;
    private final RunService runs;
    private final Sandbox sandbox;
    private final RepoService repos;

    ApiController(ExerciseService exercises, RunService runs, Sandbox sandbox, RepoService repos) {
        this.exercises = exercises;
        this.runs = runs;
        this.sandbox = sandbox;
        this.repos = repos;
    }

    @GetMapping("/languages")
    Set<String> languages() { return new TreeSet<>(sandbox.languages()); }

    @GetMapping("/exercises")
    List<ExerciseSummary> list(@RequestHeader(USER) UUID user,
                               @RequestParam(name = "language", required = false) String language,
                               @RequestParam(name = "difficulty", required = false) Difficulty difficulty) {
        return exercises.list(user, language, difficulty);
    }

    @GetMapping("/exercises/{slug}")
    ExerciseView get(@RequestHeader(USER) UUID user, @PathVariable("slug") String slug) {
        return exercises.get(user, slug);
    }

    @PostMapping("/exercises/{slug}/attempts")
    AttemptResult attempt(@RequestHeader(USER) UUID user, @PathVariable("slug") String slug, @Valid @RequestBody AttemptRequest req) {
        return exercises.attempt(user, slug, req);
    }

    @PostMapping("/runs")
    RunResponse run(@RequestHeader(USER) UUID user, @Valid @RequestBody RunRequest req) {
        return runs.run(user, req.language(), req.code());
    }

    @GetMapping("/repos")
    List<RepoSummary> repos(@RequestHeader(USER) UUID user) {
        return repos.list(user);
    }

    @GetMapping("/repos/{slug}")
    RepoView repo(@RequestHeader(USER) UUID user, @PathVariable("slug") String slug) {
        return repos.get(user, slug);
    }

    @PostMapping("/repos/{slug}/reviews")
    ReviewResult review(@RequestHeader(USER) UUID user, @PathVariable("slug") String slug, @Valid @RequestBody ReviewRequest req) {
        return repos.review(user, slug, req.review());
    }

    @PostMapping("/repos/{slug}/test-runs")
    TestRunResult testRun(@RequestHeader(USER) UUID user, @PathVariable("slug") String slug, @Valid @RequestBody TestRunRequest req) {
        return repos.runTests(user, slug, req.files());
    }
}
