package dev.codecomp.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.codecomp.contracts.RunEvent;
import dev.codecomp.contracts.RunResult;
import dev.codecomp.contracts.RunResult.RunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Live updates end to end on a real server: the browser's event stream follows the events a worker would publish. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.dev-header=true",
        "app.runs-per-minute=1000",
        "app.messaging.listen=false",
        "app.messaging.run-requests-queue=run-requests",
        "app.messaging.run-events-topic=run-events",
        "spring.datasource.url=jdbc:h2:mem:flow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@Import(RecordedMessages.Config.class)
class JobFlowIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    @Autowired
    RunEvents events;

    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void eventsFollowAJobFromQueuedToDone() throws Exception {
        var user = UUID.randomUUID();
        var job = submit(user, "print('hi')");
        var id = UUID.fromString(job.get("id").asText());

        var stream = stream(user, id);
        assertThat(next(stream).get("status").asText()).isEqualTo("QUEUED");

        deliver(new RunEvent.Started(id, "worker-1"));
        assertThat(next(stream).get("status").asText()).isEqualTo("RUNNING");

        deliver(new RunEvent.Completed(id, new RunResult(RunStatus.OK, "hi\n", "", 0, 5, false), false));
        var done = next(stream);
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(done.at("/result/stdout").asText()).isEqualTo("hi\n");
        assertThat(stream.poll(5, TimeUnit.SECONDS)).isEqualTo(END);
    }

    @Test
    void aFailedRunEndsTheStreamWithItsError() throws Exception {
        var user = UUID.randomUUID();
        var id = UUID.fromString(submit(user, "print('boom')").get("id").asText());
        var stream = stream(user, id);
        next(stream);
        deliver(new RunEvent.Failed(id, "The run could not be completed"));
        var failed = next(stream);
        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("error").asText()).isEqualTo("The run could not be completed");
        assertThat(stream.poll(5, TimeUnit.SECONDS)).isEqualTo(END);
    }

    @Test
    void waitingJobsSeeTheirPlaceInTheQueue() throws Exception {
        var running = UUID.fromString(submit(UUID.randomUUID(), "print('a')").get("id").asText());
        deliver(new RunEvent.Started(running, "worker-1"));
        var second = submit(UUID.randomUUID(), "print('b')");
        var third = submit(UUID.randomUUID(), "print('c')");
        assertThat(third.get("position").asInt()).isEqualTo(second.get("position").asInt() + 1);
    }

    // ---- helpers ----

    static final JsonNode END = TextNode.valueOf("end of stream");

    void deliver(RunEvent event) throws Exception {
        events.onRunEvent(json.writeValueAsString(event));
    }

    /** Submits code that has never run before (a unique suffix), so it is queued, not answered from the cache. */
    JsonNode submit(UUID user, String code) throws Exception {
        var body = json.writeValueAsString(Map.of("language", "python", "code", code + " # " + UUID.randomUUID()));
        var res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/runs"))
                .header("X-User-Id", user.toString()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(202);
        return json.readTree(res.body());
    }

    JsonNode next(BlockingQueue<JsonNode> events) throws InterruptedException {
        var event = events.poll(10, TimeUnit.SECONDS);
        assertThat(event).as("an event within 10 s").isNotNull().isNotEqualTo(END);
        return event;
    }

    /** Opens the event stream and hands each event's JSON to the returned queue, then END when the stream closes. */
    BlockingQueue<JsonNode> stream(UUID user, UUID id) throws Exception {
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
}
