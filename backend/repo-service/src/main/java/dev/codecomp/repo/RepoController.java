package dev.codecomp.repo;

import dev.codecomp.contracts.JobView;
import dev.codecomp.platform.CurrentUser;
import dev.codecomp.repo.RepoApi.EditRequest;
import dev.codecomp.repo.RepoApi.EditResult;
import dev.codecomp.repo.RepoApi.PointsTotal;
import dev.codecomp.repo.RepoApi.QuestionAnswer;
import dev.codecomp.repo.RepoApi.QuestionRequest;
import dev.codecomp.repo.RepoApi.RepoSummary;
import dev.codecomp.repo.RepoApi.RepoView;
import dev.codecomp.repo.RepoApi.ReviewRequest;
import dev.codecomp.repo.RepoApi.ReviewResult;
import dev.codecomp.repo.RepoApi.TestRunRequest;
import dev.codecomp.repo.RepoApi.TestRunResult;
import dev.codecomp.repo.RepoApi.TestRunSummary;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
class RepoController {

    private final RepoService repos;
    private final CodeEditService edits;

    RepoController(RepoService repos, CodeEditService edits) {
        this.repos = repos;
        this.edits = edits;
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

    @PostMapping("/repos/{slug}/questions")
    QuestionAnswer ask(@CurrentUser UUID user, @PathVariable("slug") String slug, @Valid @RequestBody QuestionRequest req) {
        return repos.ask(user, slug, req.question());
    }

    @GetMapping("/repos/{slug}/test-runs")
    List<TestRunSummary> testRuns(@CurrentUser UUID user, @PathVariable("slug") String slug) {
        return repos.testRuns(user, slug);
    }

    /** run-service's job for the run: 200 when it was answered from the cache, otherwise 202 to follow on run-service. */
    @PostMapping("/repos/{slug}/test-runs")
    ResponseEntity<JobView> testRun(@CurrentUser UUID user, @PathVariable("slug") String slug, @Valid @RequestBody TestRunRequest req) {
        var job = repos.runTests(user, slug, req.files());
        return ResponseEntity.status("DONE".equals(job.status()) ? HttpStatus.OK : HttpStatus.ACCEPTED).body(job);
    }

    /** The scored result of one test run, once recorded. */
    @GetMapping("/repos/{slug}/test-runs/{jobId}")
    TestRunResult testRunResult(@CurrentUser UUID user, @PathVariable("slug") String slug, @PathVariable("jobId") UUID jobId) {
        return repos.testRun(user, slug, jobId);
    }

    @PostMapping("/edits")
    EditResult edit(@CurrentUser UUID user, @Valid @RequestBody EditRequest req) {
        return edits.edit(user, req);
    }

    @GetMapping("/points/repos")
    PointsTotal points(@CurrentUser UUID user) {
        return new PointsTotal(repos.totalPoints(user));
    }
}
