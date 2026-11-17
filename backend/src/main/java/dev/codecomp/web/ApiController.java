package dev.codecomp.web;

import dev.codecomp.auth.CurrentUser;
import dev.codecomp.exercise.Exercise.Difficulty;
import dev.codecomp.exercise.ExerciseService;
import dev.codecomp.repo.CodeEditService;
import dev.codecomp.repo.RepoService;
import dev.codecomp.run.RunService;
import dev.codecomp.sandbox.Sandbox;
import dev.codecomp.web.Api.AttemptRequest;
import dev.codecomp.web.Api.AttemptResult;
import dev.codecomp.web.Api.EditRequest;
import dev.codecomp.web.Api.EditResult;
import dev.codecomp.web.Api.ExerciseSummary;
import dev.codecomp.web.Api.ExerciseView;
import dev.codecomp.web.Api.PointsSummary;
import dev.codecomp.web.Api.QuestionAnswer;
import dev.codecomp.web.Api.QuestionRequest;
import dev.codecomp.web.Api.RepoSummary;
import dev.codecomp.web.Api.RepoView;
import dev.codecomp.web.Api.ReviewRequest;
import dev.codecomp.web.Api.ReviewResult;
import dev.codecomp.web.Api.RunRequest;
import dev.codecomp.web.Api.RunResponse;
import dev.codecomp.web.Api.TestRunRequest;
import dev.codecomp.web.Api.TestRunResult;
import dev.codecomp.web.Api.TestRunSummary;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    private final ExerciseService exercises;
    private final RunService runs;
    private final Sandbox sandbox;
    private final RepoService repos;
    private final CodeEditService edits;

    ApiController(ExerciseService exercises, RunService runs, Sandbox sandbox, RepoService repos, CodeEditService edits) {
        this.edits = edits;
        this.exercises = exercises;
        this.runs = runs;
        this.sandbox = sandbox;
        this.repos = repos;
    }

    @GetMapping("/languages")
    Set<String> languages() { return new TreeSet<>(sandbox.languages()); }

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

    @PostMapping("/runs")
    RunResponse run(@CurrentUser UUID user, @Valid @RequestBody RunRequest req) {
        return runs.run(user, req.language(), req.code());
    }

    @PostMapping("/edits")
    EditResult edit(@CurrentUser UUID user, @Valid @RequestBody EditRequest req) {
        return edits.edit(user, req);
    }

    @GetMapping("/points")
    PointsSummary points(@CurrentUser UUID user) {
        int snippets = exercises.totalPoints(user);
        int repoPoints = repos.totalPoints(user);
        return new PointsSummary(snippets + repoPoints, snippets, repoPoints);
    }

    @GetMapping("/repos")
    List<RepoSummary> repos(@CurrentUser UUID user) {
        return repos.list(user);
    }

    @GetMapping("/repos/{slug}")
    RepoView repo(@CurrentUser UUID user, @PathVariable("slug") String slug) {
        return repos.get(user, slug);
    }

    @PostMapping("/repos/{slug}/reviews")
    ReviewResult review(@CurrentUser UUID user, @PathVariable("slug") String slug, @Valid @RequestBody ReviewRequest req) {
        return repos.review(user, slug, req.review());
    }

    @PostMapping("/repos/{slug}/reveal")
    RepoView reveal(@CurrentUser UUID user, @PathVariable("slug") String slug) {
        return repos.reveal(user, slug);
    }

    @GetMapping("/repos/{slug}/test-runs")
    List<TestRunSummary> testRuns(@CurrentUser UUID user, @PathVariable("slug") String slug) {
        return repos.testRuns(user, slug);
    }

    @PostMapping("/repos/{slug}/questions")
    QuestionAnswer ask(@CurrentUser UUID user, @PathVariable("slug") String slug, @Valid @RequestBody QuestionRequest req) {
        return repos.ask(user, slug, req.question());
    }

    @PostMapping("/repos/{slug}/test-runs")
    TestRunResult testRun(@CurrentUser UUID user, @PathVariable("slug") String slug, @Valid @RequestBody TestRunRequest req) {
        return repos.runTests(user, slug, req.files());
    }
}
