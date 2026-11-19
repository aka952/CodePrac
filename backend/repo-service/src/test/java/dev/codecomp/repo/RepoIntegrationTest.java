package dev.codecomp.repo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codecomp.contracts.JobView;
import dev.codecomp.contracts.RunEvent;
import dev.codecomp.contracts.RunResult;
import dev.codecomp.contracts.RunResult.RunStatus;
import dev.codecomp.repo.CodeEditAssistant.CodeEdit;
import dev.codecomp.repo.ReviewGrader.IssueVerdict;
import dev.codecomp.repo.ReviewGrader.ReviewGrade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** repo-service on its own: run-service is a fake that records submissions, and the test delivers the result events. */
@SpringBootTest(properties = {
        "app.auth.dev-header=true",
        "app.seed.enabled=true",
        "app.baseline.enabled=false",
        "app.messaging.listen=false",
        "spring.datasource.url=jdbc:h2:mem:repo;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class RepoIntegrationTest {

    /** What the fake sandbox prints for every hidden-test run: one test passes, one fails. */
    static final RunResult HALF_PASSING = new RunResult(RunStatus.RUNTIME_ERROR, "PASS works\nFAIL breaks: expected 1 but got 2\n", "", 1, 5, false);
    static final RunResult ALL_PASSING = new RunResult(RunStatus.OK, "PASS works\nPASS breaks\n", "", 0, 5, false);

    static final AtomicInteger graderCalls = new AtomicInteger();
    static final AtomicInteger assistantCalls = new AtomicInteger();

    /** Stands in for run-service. */
    static class FakeRuns implements RunClient {
        record Submitted(UUID jobId, UUID userId, String language, String code) {}

        final List<Submitted> submitted = new CopyOnWriteArrayList<>();
        volatile RunServiceException refuse;

        @Override
        public JobView submit(UUID jobId, UUID userId, String language, String code) {
            if (refuse != null) throw refuse;
            submitted.add(new Submitted(jobId, userId, language, code));
            return new JobView(jobId, "QUEUED", 1, null, null);
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        FakeRuns fakeRuns() { return new FakeRuns(); }

        /** Says the review found issue 1 with a correct fix, and nothing else. */
        @Bean
        @Primary
        ReviewGrader fakeGrader() {
            return (exercise, content, review) -> {
                graderCalls.incrementAndGet();
                return new ReviewGrade(List.of(new IssueVerdict(1, true, true, "spot on")), List.of(), "Good start.");
            };
        }

        /** Upper-cases the selection, or declines when asked to "fix it". */
        @Bean
        @Primary
        CodeEditAssistant fakeEditor() {
            return (language, file, selection, instruction) -> instruction.equals("fix it")
                    ? new CodeEdit(selection, "Say what to change.")
                    : new CodeEdit(selection.toUpperCase(), null);
        }

        @Bean
        @Primary
        SyntaxAssistant fakeAssistant() {
            return (language, question) -> {
                assistantCalls.incrementAndGet();
                return "about " + language + ": " + question;
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    FakeRuns runs;

    @Autowired
    RepoRunEvents events;

    @Autowired
    RepoExerciseRepository repos;

    @Autowired
    RepoSubmissionRepository submissions;

    @Autowired
    PendingRunRepository pending;

    final String user = UUID.randomUUID().toString();

    @BeforeEach
    void runServiceUp() {
        runs.refuse = null;
    }

    ResultActions json(String path, String body) throws Exception {
        return mvc.perform(post(path).header("X-User-Id", user).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    void deliver(RunEvent event) throws Exception {
        events.onRunEvent(json.writeValueAsString(event));
    }

    /** Submits a test run and delivers its result, as run-service would after a worker ran it. */
    JsonNode testRun(String slug, String files, RunResult result) throws Exception {
        var job = body(json("/api/repos/" + slug + "/test-runs", "{\"files\":" + files + "}").andExpect(status().isAccepted()));
        var id = UUID.fromString(job.get("id").asText());
        deliver(new RunEvent.Completed(id, result, false));
        return body(mvc.perform(get("/api/repos/{slug}/test-runs/{id}", slug, id).header("X-User-Id", user)).andExpect(status().isOk()));
    }

    @Test
    void repoIssuesStayHiddenUntilReviewed() throws Exception {
        mvc.perform(get("/api/repos").header("X-User-Id", user))
                .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(5)))
                .andExpect(jsonPath("$[0].difficulty", is("EASY")))
                .andExpect(jsonPath("$[*].status", everyItem(is("NEW"))));
        mvc.perform(get("/api/repos/java-checkout-service").header("X-User-Id", user))
                .andExpect(jsonPath("$.files.length()", is(4)))
                .andExpect(jsonPath("$.revealed", is(false)))
                .andExpect(jsonPath("$.issues", nullValue()))
                .andExpect(jsonPath("$.tests").doesNotExist())
                .andExpect(jsonPath("$.solution").doesNotExist());

        json("/api/repos/java-checkout-service/reviews", "{\"review\":\"Inventory.find compares strings with ==\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found", is(1)))
                .andExpect(jsonPath("$.total", is(7)))
                .andExpect(jsonPath("$.fixesCorrect", is(1)))
                .andExpect(jsonPath("$.issues[0].found", is(true)))
                .andExpect(jsonPath("$.issues[0].issue.line", is(15)))
                .andExpect(jsonPath("$.issues[1].found", is(false)));

        mvc.perform(get("/api/repos/java-checkout-service").header("X-User-Id", user))
                .andExpect(jsonPath("$.revealed", is(true)))
                .andExpect(jsonPath("$.issues.length()", is(7)));
        mvc.perform(get("/api/repos").header("X-User-Id", user))
                .andExpect(jsonPath("$[?(@.slug == 'java-checkout-service')].status", contains("ATTEMPTED")));
    }

    @Test
    void skippingTheReviewRevealsIssuesWithoutGrading() throws Exception {
        int before = graderCalls.get();
        json("/api/repos/java-user-registry/reveal", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revealed", is(true)))
                .andExpect(jsonPath("$.issues.length()", is(10)));
        assertThat(graderCalls.get()).isEqualTo(before);
    }

    @Test
    void testRunsAreSubmittedToRunServiceAndScoredFromItsEvent() throws Exception {
        var job = body(json("/api/repos/java-checkout-service/test-runs", "{\"files\":{\"src/main/java/com/shop/Cart.java\":\"class Cart {}\"}}")
                .andExpect(status().isAccepted()));
        var id = UUID.fromString(job.get("id").asText());
        var submitted = runs.submitted.stream().filter(s -> s.jobId().equals(id)).findFirst().orElseThrow();
        assertThat(submitted.userId()).isEqualTo(UUID.fromString(user));
        assertThat(submitted.language()).isEqualTo("java-project");

        // not recorded until the result event arrives
        mvc.perform(get("/api/repos/java-checkout-service/test-runs/{id}", id).header("X-User-Id", user)).andExpect(status().isNotFound());
        deliver(new RunEvent.Completed(id, HALF_PASSING, false));

        var r = body(mvc.perform(get("/api/repos/java-checkout-service/test-runs/{id}", id).header("X-User-Id", user)).andExpect(status().isOk()));
        assertThat(r.get("total").asInt()).isEqualTo(2);
        assertThat(r.get("passed").asInt()).isEqualTo(1);
        assertThat(r.get("allPassed").asBoolean()).isFalse();
        // nothing was won: the baseline is not measured here, so only a full pass would score
        assertThat(r.get("points").asInt()).isZero();
        assertThat(r.get("maxPoints").asInt()).isEqualTo(100);
        assertThat(r.at("/tests/1/name").asText()).isEqualTo("breaks");
        assertThat(r.at("/tests/1/message").asText()).isEqualTo("expected 1 but got 2");
        // someone else cannot read it
        mvc.perform(get("/api/repos/java-checkout-service/test-runs/{id}", id).header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void theSameResultIsRecordedOnceAndUnknownJobsAreIgnored() throws Exception {
        var job = body(json("/api/repos/java-user-registry/test-runs", "{\"files\":{}}"));
        var id = UUID.fromString(job.get("id").asText());
        deliver(new RunEvent.Completed(id, HALF_PASSING, false));
        deliver(new RunEvent.Completed(id, ALL_PASSING, false));
        mvc.perform(get("/api/repos/java-user-registry/test-runs").header("X-User-Id", user)).andExpect(jsonPath("$.length()", is(1)));

        // a result for a job this service never submitted (for example someone's snippet run) records nothing
        long before = submissions.count();
        deliver(new RunEvent.Completed(UUID.randomUUID(), ALL_PASSING, false));
        assertThat(submissions.count()).isEqualTo(before);
    }

    @Test
    void aFailedRunRecordsNothing() throws Exception {
        var id = UUID.fromString(body(json("/api/repos/java-user-registry/test-runs", "{\"files\":{}}")).get("id").asText());
        deliver(new RunEvent.Failed(id, "The run stopped unexpectedly. Try again."));
        assertThat(pending.existsById(id)).isFalse();
        assertThat(submissions.existsByJobId(id)).isFalse();
    }

    @Test
    void testRunsAreKeptAsHistoryNewestFirst() throws Exception {
        mvc.perform(get("/api/repos/java-user-registry/test-runs").header("X-User-Id", user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(0)));
        testRun("java-user-registry", "{}", HALF_PASSING);
        mvc.perform(get("/api/repos/java-user-registry/test-runs").header("X-User-Id", user))
                .andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$[0].passed", is(1)))
                .andExpect(jsonPath("$[0].total", is(2)))
                .andExpect(jsonPath("$[0].allPassed", is(false)))
                .andExpect(jsonPath("$[0].createdAt", notNullValue()))
                .andExpect(jsonPath("$[0].tests[1].message", is("expected 1 but got 2")));
        mvc.perform(get("/api/repos/java-user-registry/test-runs").header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(jsonPath("$.length()", is(0)));
    }

    @Test
    void theBaselineIsMeasuredThroughRunServiceAndCountsTowardPoints() throws Exception {
        var checker = new BaselineChecker(repos, pending, new RepoScoring(submissions, json), runs);
        checker.check();
        var exercise = repos.findBySlug("java-sales-report").orElseThrow();
        var baselineRun = runs.submitted.stream()
                .filter(s -> s.userId() == null && pending.findById(s.jobId()).map(p -> p.getRepoExerciseId().equals(exercise.getId())).orElse(false))
                .findFirst().orElseThrow();
        // asking again while that run is pending does not submit another
        int submittedSoFar = runs.submitted.size();
        checker.check();
        assertThat(runs.submitted).hasSize(submittedSoFar);

        deliver(new RunEvent.Completed(baselineRun.jobId(), HALF_PASSING, true));
        assertThat(repos.findBySlug("java-sales-report").orElseThrow().getBaseline()).isEqualTo(1);

        // with a baseline of 1 test out of 2, fixing nothing more earns nothing, and a full pass earns everything
        assertThat(testRun("java-sales-report", "{}", HALF_PASSING).get("points").asInt()).isZero();
        var full = testRun("java-sales-report", "{}", ALL_PASSING);
        assertThat(full.get("allPassed").asBoolean()).isTrue();
        assertThat(full.get("points").asInt()).isEqualTo(full.get("maxPoints").asInt());
        mvc.perform(get("/api/points/repos").header("X-User-Id", user)).andExpect(jsonPath("$.points", is(full.get("maxPoints").asInt())));
    }

    @Test
    void runServiceRefusalsArePassedOn() throws Exception {
        runs.refuse = new RunClient.RunServiceException(429, "Run limit reached, try again in 7s", "7");
        json("/api/repos/java-user-registry/test-runs", "{\"files\":{}}")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "7"))
                .andExpect(jsonPath("$.detail", is("Run limit reached, try again in 7s")));
        // the run never started, so nothing is left waiting for it
        assertThat(pending.findAll()).noneMatch(p -> p.getUserId() != null && p.getUserId().equals(UUID.fromString(user)));
    }

    @Test
    void askAiAnswersUntilTheQuotaRunsOut() throws Exception {
        mvc.perform(get("/api/repos/java-user-registry").header("X-User-Id", user))
                .andExpect(jsonPath("$.questionsLeft", is(5)));
        json("/api/repos/java-user-registry/questions", "{\"question\":\"what is a record\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", is("about java: what is a record")))
                .andExpect(jsonPath("$.questionsLeft", is(4)));
        for (int i = 0; i < 4; i++) json("/api/repos/java-user-registry/questions", "{\"question\":\"q\"}").andExpect(status().isOk());
        int calls = assistantCalls.get();
        json("/api/repos/java-user-registry/questions", "{\"question\":\"one more\"}").andExpect(status().isTooManyRequests());
        assertThat(assistantCalls.get()).isEqualTo(calls);
        // asking questions is not an attempt, and the quota is per exercise
        mvc.perform(get("/api/repos").header("X-User-Id", user))
                .andExpect(jsonPath("$[?(@.slug == 'java-user-registry')].status", contains("NEW")));
        mvc.perform(get("/api/repos/java-checkout-service").header("X-User-Id", user))
                .andExpect(jsonPath("$.questionsLeft", is(5)));
    }

    @Test
    void inlineEditRewritesTheSelection() throws Exception {
        String body = "{\"language\":\"java\",\"code\":\"int a = 1;\\nint b = 2;\\n\",\"selection\":\"int b = 2;\",\"instruction\":\"%s\"}";
        json("/api/edits", body.formatted("shout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replacement", is("INT B = 2;")))
                .andExpect(jsonPath("$.note", nullValue()));
        json("/api/edits", body.formatted("fix it"))
                .andExpect(jsonPath("$.replacement", is("int b = 2;")))
                .andExpect(jsonPath("$.note", is("Say what to change.")));
    }

    @Test
    void inlineEditRejectsBadInput() throws Exception {
        json("/api/edits", "{\"language\":\"java\",\"code\":\"int a;\",\"selection\":\"int a;\",\"instruction\":\" \"}")
                .andExpect(status().isBadRequest());
        json("/api/edits", "{\"language\":\"java\",\"code\":\"int a;\",\"selection\":\"int zzz;\",\"instruction\":\"rename\"}")
                .andExpect(status().isBadRequest());
        json("/api/edits", "{\"language\":\"cobol\",\"code\":\"x\",\"selection\":\"x\",\"instruction\":\"rename\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void repoEndpointsRejectBadInput() throws Exception {
        json("/api/repos/java-checkout-service/test-runs", "{\"files\":{\"Evil.java\":\"x\"}}").andExpect(status().isBadRequest());
        json("/api/repos/java-checkout-service/reviews", "{\"review\":\" \"}").andExpect(status().isBadRequest());
        json("/api/repos/java-checkout-service/questions", "{\"question\":\" \"}").andExpect(status().isBadRequest());
        mvc.perform(get("/api/repos/nope").header("X-User-Id", user)).andExpect(status().isNotFound());
        mvc.perform(get("/api/repos")).andExpect(status().isUnauthorized());
    }

    @Test
    void reseedingChangedContentForgetsTheBaseline() {
        var r = repos.findBySlug("java-user-registry").orElseThrow();
        r.measured(3);
        var def = new RepoExercise.Def(r.getSlug(), r.getTitle(), r.getLanguage(), r.getDifficulty(), r.getTags(), r.getDescription(),
                List.of(), List.of(), List.of(), List.of());
        r.apply(def, r.getContent(), 0);
        assertThat(r.getBaseline()).isEqualTo(3);
        r.apply(def, r.getContent() + " ", 0);
        assertThat(r.getBaseline()).isNull();
    }
}
