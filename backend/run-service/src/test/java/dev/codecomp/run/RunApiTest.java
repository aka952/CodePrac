package dev.codecomp.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codecomp.contracts.RunEvent;
import dev.codecomp.contracts.RunRequested;
import dev.codecomp.contracts.RunResult;
import dev.codecomp.contracts.RunResult.RunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The queue's rules, with events handed to the handlers directly instead of arriving from SQS. */
@SpringBootTest(properties = {
        "app.auth.dev-header=true",
        "app.runs-per-minute=2",
        "app.messaging.listen=false",
        "app.messaging.run-requests-queue=run-requests",
        "app.messaging.run-events-topic=run-events",
        "spring.datasource.url=jdbc:h2:mem:runapi;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@Import(RecordedMessages.Config.class)
class RunApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    RecordedMessages messages;

    @Autowired
    RunEvents events;

    @Autowired
    JobService service;

    @Autowired
    RunJobRepository jobs;

    final UUID user = UUID.randomUUID();

    @BeforeEach
    void queueUp() {
        messages.down = false;
    }

    ResultActions run(UUID as, String language, String code) throws Exception {
        return mvc.perform(post("/api/runs").header("X-User-Id", as.toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("language", language, "code", code))));
    }

    JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    void deliver(RunEvent event) throws Exception {
        events.onRunEvent(json.writeValueAsString(event));
    }

    static RunResult ok(String stdout) { return new RunResult(RunStatus.OK, stdout, "", 0, 5, false); }

    String jobStatus(UUID job) throws Exception {
        return body(mvc.perform(get("/api/jobs/{id}", job).header("X-User-Id", user.toString()))).get("status").asText();
    }

    @Test
    void aNewRunIsQueuedForTheWorkersAndRepeatsComeFromTheCache() throws Exception {
        var code = "print('cache me') # " + UUID.randomUUID();
        var job = body(run(user, "python", code).andExpect(status().isAccepted()).andExpect(jsonPath("$.status", is("QUEUED"))));
        var id = UUID.fromString(job.get("id").asText());

        var request = messages.sent.stream().filter(s -> s.message() instanceof RunRequested r && r.jobId().equals(id)).findFirst().orElseThrow();
        assertThat(request.destination()).isEqualTo("run-requests");
        assertThat(((RunRequested) request.message()).code()).isEqualTo(code);

        deliver(new RunEvent.Started(id, "worker-1"));
        assertThat(jobStatus(id)).isEqualTo("RUNNING");
        deliver(new RunEvent.Completed(id, ok("cache me\n"), false));
        mvc.perform(get("/api/jobs/{id}", id).header("X-User-Id", user.toString()))
                .andExpect(jsonPath("$.status", is("DONE")))
                .andExpect(jsonPath("$.result.stdout", is("cache me\n")))
                .andExpect(jsonPath("$.result.cached", is(false)));

        // answered at once from the cache, without queueing
        int sent = messages.sent.size();
        run(user, "python", code).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("DONE")))
                .andExpect(jsonPath("$.id", nullValue())).andExpect(jsonPath("$.result.cached", is(true)));
        assertThat(messages.sent).hasSize(sent);
    }

    @Test
    void duplicateAndLateEventsChangeNothing() throws Exception {
        var id = UUID.fromString(body(run(UUID.randomUUID(), "python", "print(1) # " + UUID.randomUUID())).get("id").asText());
        // delivered out of order: the result first, then a late "started", then the result again with other output
        deliver(new RunEvent.Completed(id, ok("first\n"), false));
        deliver(new RunEvent.Started(id, "worker-2"));
        deliver(new RunEvent.Completed(id, ok("second\n"), false));
        deliver(new RunEvent.Failed(id, "too late"));
        var job = jobs.findById(id).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(RunJob.Status.DONE);
        assertThat(json.readTree(job.getResult()).get("stdout").asText()).isEqualTo("first\n");
    }

    @Test
    void aRunNoWorkerCouldFinishFails() throws Exception {
        var id = UUID.fromString(body(run(UUID.randomUUID(), "python", "print(1) # " + UUID.randomUUID())).get("id").asText());
        events.onDeadRequest(json.writeValueAsString(new RunRequested(id, "python", "x")));
        var job = jobs.findById(id).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(RunJob.Status.FAILED);
        assertThat(job.getError()).isEqualTo(RunEvents.STOPPED);
    }

    @Test
    void aRunWhoseSendFailedIsSentAgain() throws Exception {
        messages.down = true;
        var id = UUID.fromString(body(run(UUID.randomUUID(), "python", "print(1) # " + UUID.randomUUID())
                .andExpect(status().isAccepted())).get("id").asText());
        messages.down = false;
        assertThat(messages.sent).noneMatch(s -> s.message() instanceof RunRequested r && r.jobId().equals(id));

        service.resendUnsent(Instant.now().plusSeconds(1));
        assertThat(messages.sent).anyMatch(s -> s.message() instanceof RunRequested r && r.jobId().equals(id));
        // sent once: the next sweep leaves it alone
        service.resendUnsent(Instant.now().plusSeconds(1));
        assertThat(messages.sent).filteredOn(s -> s.message() instanceof RunRequested r && r.jobId().equals(id)).hasSize(1);
    }

    @Test
    void runsAreRateLimitedPerUser() throws Exception {
        var limited = UUID.randomUUID();
        var a = UUID.fromString(body(run(limited, "python", "a # " + UUID.randomUUID()).andExpect(status().isAccepted())).get("id").asText());
        deliver(new RunEvent.Completed(a, ok(""), false));
        var b = UUID.fromString(body(run(limited, "python", "b # " + UUID.randomUUID()).andExpect(status().isAccepted())).get("id").asText());
        deliver(new RunEvent.Completed(b, ok(""), false));
        run(limited, "python", "c # " + UUID.randomUUID()).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }

    @Test
    void oneUserCannotFillTheQueue() throws Exception {
        var greedy = UUID.randomUUID();
        run(greedy, "python", "1 # " + UUID.randomUUID()).andExpect(status().isAccepted());
        run(greedy, "python", "2 # " + UUID.randomUUID()).andExpect(status().isAccepted());
        run(greedy, "python", "3 # " + UUID.randomUUID()).andExpect(status().isTooManyRequests())
                .andExpect(status().reason("You already have runs in progress. Wait for them to finish."));
    }

    @Test
    void jobsAreOnlyVisibleToTheirOwner() throws Exception {
        var id = body(run(user, "python", "print('mine') # " + UUID.randomUUID())).get("id").asText();
        mvc.perform(get("/api/jobs/{id}", id).header("X-User-Id", UUID.randomUUID().toString())).andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs/{id}/events", id).header("X-User-Id", UUID.randomUUID().toString())).andExpect(status().isNotFound());
    }

    @Test
    void rejectsBadRequests() throws Exception {
        mvc.perform(get("/api/jobs/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
        run(user, "cobol", "DISPLAY 'HI'").andExpect(status().isBadRequest());
        mvc.perform(get("/api/languages").header("X-User-Id", user.toString()))
                .andExpect(jsonPath("$[0]", is("java"))).andExpect(jsonPath("$.length()", is(3)));
    }

    @Test
    void internalRunsUseTheCallersIdAndAnnounceEveryOutcome() throws Exception {
        var code = "bundle-" + UUID.randomUUID();
        var first = UUID.randomUUID();
        // no sign-in and no CSRF token: only other services can reach /internal
        mvc.perform(post("/internal/runs").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("id", first, "userId", user, "language", "java-project", "code", code))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id", is(first.toString())));
        // the same id again (a retry) is the same job, not a second run
        mvc.perform(post("/internal/runs").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("id", first, "userId", user, "language", "java-project", "code", code))))
                .andExpect(jsonPath("$.id", is(first.toString())));
        assertThat(messages.sent).filteredOn(s -> s.message() instanceof RunRequested r && r.jobId().equals(first)).hasSize(1);
        deliver(new RunEvent.Completed(first, ok("PASS a\n"), false));

        // a cache hit: finished at once, and still announced, so the caller learns every result the same way
        var second = UUID.randomUUID();
        mvc.perform(post("/internal/runs").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("id", second, "userId", user, "language", "java-project", "code", code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("DONE")))
                .andExpect(jsonPath("$.result.cached", is(true)));
        assertThat(messages.published).anyMatch(p -> p.message() instanceof RunEvent.Completed c && c.jobId().equals(second) && c.cached());
        // and the browser can follow it like any other job
        mvc.perform(get("/api/jobs/{id}", second).header("X-User-Id", user.toString())).andExpect(jsonPath("$.status", is("DONE")));
    }

    @Test
    void systemRunsSkipTheUserLimits() throws Exception {
        for (int i = 0; i < 5; i++) {
            var body = new java.util.HashMap<String, Object>(Map.of("id", UUID.randomUUID(), "language", "java-project", "code", "b" + UUID.randomUUID()));
            mvc.perform(post("/internal/runs").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                    .andExpect(status().isAccepted());
        }
    }
}
