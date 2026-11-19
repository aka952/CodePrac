package dev.codecomp.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codecomp.contracts.RunEvent;
import dev.codecomp.contracts.RunRequested;
import dev.codecomp.messaging.MessageSender;
import dev.codecomp.sandbox.Sandbox;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Takes run requests from the queue and runs them, at most {@code app.worker.threads} at a time, and publishes what
 * happened on the run-events topic. It asks the queue only for as many messages as it has free threads, so a message
 * it holds always gets run promptly, and more workers simply means more consumers of the same queue.
 *
 * <p>A message is deleted only after its outcome is published. If the worker dies first, the message reappears after
 * the queue's visibility timeout and another worker runs it; the consumers ignore the duplicate events that can cause.
 */
@Component
class JobWorker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    static final int BUSY_RETRY_SECONDS = 5;

    private final RunRequests queue;
    private final Sandbox sandbox;
    private final MessageSender messages;
    private final ObjectMapper json;
    private final String topic;
    private final int threads;
    private final String name;
    private final Semaphore slots;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean stopping;
    private Thread dispatcher;

    JobWorker(RunRequests queue, Sandbox sandbox, MessageSender messages, ObjectMapper json,
              @Value("${app.messaging.run-events-topic}") String topic, @Value("${app.worker.threads}") int threads) {
        this.queue = queue;
        this.sandbox = sandbox;
        this.messages = messages;
        this.json = json;
        this.topic = topic;
        this.threads = threads;
        this.slots = new Semaphore(threads);
        this.name = hostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public void run(ApplicationArguments args) {
        dispatcher = Thread.ofVirtual().name("job-dispatcher").start(this::dispatch);
        log.info("Worker {} started with {} threads", name, threads);
    }

    /** Waits for a free thread, then asks for up to as many messages as there are free threads. */
    private void dispatch() {
        while (!stopping) {
            slots.acquireUninterruptibly();
            int free = 1;
            while (free < 10 && slots.tryAcquire()) free++;
            try {
                var received = queue.receive(free);
                if (stopping) {
                    // shutting down while the receive was waiting: hand them straight back instead of letting them
                    // sit out the visibility timeout (2 minutes) with a worker that will never run them
                    received.forEach(message -> queue.retryLater(message, 0));
                    slots.release(free);
                    return;
                }
                slots.release(free - received.size());
                for (var message : received) {
                    pool.submit(() -> {
                        try {
                            process(message);
                        } finally {
                            slots.release();
                        }
                    });
                }
            } catch (RuntimeException e) {
                slots.release(free);
                if (stopping) return;
                log.warn("Could not receive run requests: {}", e.getMessage());
                sleep(2_000);
            }
        }
    }

    void process(RunRequests.Received message) {
        RunRequested request;
        try {
            request = json.readValue(message.body(), RunRequested.class);
        } catch (IOException e) {
            log.warn("Dropping a message that is not a run request: {}", e.getMessage());
            queue.done(message);
            return;
        }
        var id = request.jobId();
        try {
            messages.publish(topic, new RunEvent.Started(id, name));
            var result = sandbox.run(request.language(), request.code());
            publishOutcome(new RunEvent.Completed(id, result, false));
        } catch (Sandbox.BusyException e) {
            // every sandbox slot is taken (for example by the startup warm-up): let it come back in a few seconds
            queue.retryLater(message, BUSY_RETRY_SECONDS);
            return;
        } catch (RuntimeException e) {
            log.warn("Run {} failed: {}", id, e.toString());
            publishOutcome(new RunEvent.Failed(id, "The run could not be completed: " + e.getMessage()));
        }
        queue.done(message);
    }

    /**
     * SNS takes messages up to 256 KB. Output is capped at 64 KB per stream, but escaping can still push a result over,
     * so if the result cannot be published, a failure is published in its place. If that fails too, this throws, the
     * message is not deleted, and the run is retried (and in the end failed through the dead-letter queue).
     */
    private void publishOutcome(RunEvent event) {
        try {
            messages.publish(topic, event);
        } catch (RuntimeException e) {
            if (!(event instanceof RunEvent.Completed)) throw e;
            log.warn("Could not publish the result of run {}: {}", event.jobId(), e.getMessage());
            messages.publish(topic, new RunEvent.Failed(event.jobId(), "The run finished, but its output could not be delivered"));
        }
    }

    /**
     * Lets the current receive finish first (up to its 20 s wait), so whatever it brings back is handed back to the
     * queue; exiting in the middle would leave those messages hidden until their visibility timeout. Then waits for
     * the runs in progress. The container's stop timeout must cover both (60 s in ECS and Compose).
     */
    @PreDestroy
    void stop() throws InterruptedException {
        stopping = true;
        if (dispatcher != null) dispatcher.join(Duration.ofSeconds(25));
        pool.shutdown();
        // anything still running after this was never deleted, so it reappears on the queue for another worker
        pool.awaitTermination(25, TimeUnit.SECONDS);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "worker";
        }
    }
}
