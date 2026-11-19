package dev.codecomp.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codecomp.sandbox.RunResult;
import dev.codecomp.sandbox.RunResult.RunStatus;
import dev.codecomp.sandbox.Sandbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The queue end to end on a real server: one worker thread, and a sandbox the test can hold up and release. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.warm-on-startup=false",
        "app.auth.dev-header=true",
        "app.runs-per-minute=1000",
        "app.worker.threads=1",
        "spring.datasource.url=jdbc:h2:mem:queue;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class JobQueueIntegrationTest {

    /** Runs whose code contains HOLD wait here until the test releases a permit. */
    static final Semaphore gate = new Semaphore(0);

    @TestConfiguration
    static class HeldSandbox {
        @Bean
        @Primary
        Sandbox heldSandbox() {
            return new Sandbox() {
                public Set<String> languages() { return Set.of("java", "python", "java-project"); }

                public RunResult run(String language, String code) {
                    if (code.contains("HOLD")) {
                        try {
                            gate.tryAcquire(20, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return new RunResult(RunStatus.OK, "ran " + code + "\n", "", 0, 5, false);
                }
            };
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    @Autowired
    JobService jobs;

    @Autowired
    RunJobRepository jobRows;

    @Autowired
    PlatformTransactionManager transactions;

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void closeGate() {
        gate.drainPermits();
    }

    @Test
    void eventsFollowAJobFromRunningToDone() throws Exception {
        var user = UUID.randomUUID();
        var job = submit(user, "print('HOLD 1')");
        assertThat(job.get("status").asText()).isEqualTo("QUEUED");

        var events = stream(user, job.get("id").asText());
        assertThat(nextStatus(events)).isIn("QUEUED", "RUNNING");
        if (!last.get("status").asText().equals("RUNNING")) assertThat(nextStatus(events)).isEqualTo("RUNNING");

        gate.release();
        assertThat(nextStatus(events)).isEqualTo("DONE");
        assertThat(last.at("/result/stdout").asText()).startsWith("ran print('HOLD 1')");
        assertThat(events.poll(5, TimeUnit.SECONDS)).isEqualTo(END);
    }

    @Test
    void waitingJobsSeeTheirPlaceInTheQueue() throws Exception {
        var running = submit(UUID.randomUUID(), "print('HOLD a')");
        awaitStatus(running, "RUNNING");
        var second = submit(UUID.randomUUID(), "print('HOLD b')");
        var third = submit(UUID.randomUUID(), "print('HOLD c')");
        assertThat(second.get("position").asInt()).isEqualTo(1);
        assertThat(third.get("position").asInt()).isEqualTo(2);

        gate.release(3);
        for (var job : List.of(running, second, third)) awaitStatus(job, "DONE");
    }

    @Test
    void jobsAreOnlyVisibleToTheirOwner() throws Exception {
        var owner = UUID.randomUUID();
        var job = submit(owner, "print('mine')");
        var id = job.get("id").asText();
        assertThat(get(UUID.randomUUID(), "/api/jobs/" + id).statusCode()).isEqualTo(404);
        assertThat(get(UUID.randomUUID(), "/api/jobs/" + id + "/events").statusCode()).isEqualTo(404);
        awaitStatus(job, "DONE");
    }

    @Test
    void oneUserCannotFillTheQueue() throws Exception {
        var user = UUID.randomUUID();
        var first = submit(user, "print('HOLD 1')");
        var second = submit(user, "print('HOLD 2')");
        var third = post(user, "/api/runs", "{\"language\":\"python\",\"code\":\"print('HOLD 3') # " + UUID.randomUUID() + "\"}");
        assertThat(third.statusCode()).isEqualTo(429);
        assertThat(third.body()).contains("already have runs in progress");
        gate.release(2);
        awaitStatus(first, "DONE");
        awaitStatus(second, "DONE");
    }

    @Test
    void jobsFromAWorkerThatDiedAreRunAgain() throws Exception {
        var user = UUID.randomUUID();
        // in one transaction, so the live worker never sees the job while it is still QUEUED
        var job = new TransactionTemplate(transactions).execute(status -> {
            var saved = jobRows.save(new RunJob(user, RunJob.Kind.SNIPPET, "python", "print('orphan') # " + UUID.randomUUID(), null));
            jobRows.flush();
            // claimed by a worker whose last heartbeat was five minutes ago
            jobRows.claim(saved.getId(), "dead-worker", Instant.now().minus(Duration.ofMinutes(5)));
            return saved;
        });
        assertThat(jobRows.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(RunJob.Status.RUNNING);

        assertThat(jobs.recoverStale()).isEqualTo(1);
        var view = json.readTree(get(user, "/api/jobs/" + job.getId()).body());
        awaitStatus(view, "DONE");
    }

    // ---- helpers ----

    static final JsonNode END = com.fasterxml.jackson.databind.node.TextNode.valueOf("end of stream");
    JsonNode last;

    /** Submits code that has never run before (a unique suffix), so it is queued, not answered from the cache. */
    JsonNode submit(UUID user, String code) throws Exception {
        var unique = code + " # " + UUID.randomUUID();
        var res = post(user, "/api/runs", json.writeValueAsString(java.util.Map.of("language", "python", "code", unique)));
        assertThat(res.statusCode()).as(res.body()).isEqualTo(202);
        return json.readTree(res.body());
    }

    void awaitStatus(JsonNode job, String status) throws Exception {
        for (int i = 0; i < 200; i++) {
            var now = jobs.views(List.of(UUID.fromString(job.get("id").asText()))).values().iterator().next();
            if (now.status().equals(status)) return;
            Thread.sleep(50);
        }
        throw new AssertionError("job " + job.get("id") + " never reached " + status);
    }

    String nextStatus(BlockingQueue<JsonNode> events) throws InterruptedException {
        last = events.poll(10, TimeUnit.SECONDS);
        assertThat(last).as("an event within 10 s").isNotNull().isNotEqualTo(END);
        return last.get("status").asText();
    }

    /** Opens the event stream and hands each event's JSON to the returned queue, then END when the stream closes. */
    BlockingQueue<JsonNode> stream(UUID user, String id) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/jobs/" + id + "/events"))
                .header("X-User-Id", user.toString()).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
        Thread.ofVirtual().start(() -> {
            try {
                response.body().filter(line -> line.startsWith("data:")).forEach(line -> {
                    try {
                        events.add(json.readTree(line.substring(5)));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            } finally {
                events.add(END);
            }
        });
        return events;
    }

    HttpResponse<String> post(UUID user, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-User-Id", user.toString()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(UUID user, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-User-Id", user.toString()).build(), HttpResponse.BodyHandlers.ofString());
    }
}
