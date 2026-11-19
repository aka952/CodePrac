package dev.codecomp.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codecomp.contracts.RunEvent;
import dev.codecomp.contracts.RunRequested;
import dev.codecomp.contracts.RunResult;
import dev.codecomp.contracts.RunResult.RunStatus;
import dev.codecomp.messaging.MessageSender;
import dev.codecomp.sandbox.Sandbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

class JobWorkerTest {

    final ObjectMapper json = new ObjectMapper();
    final FakeQueue queue = new FakeQueue();
    final List<RunEvent> published = new CopyOnWriteArrayList<>();
    volatile boolean topicDown;
    volatile BiFunction<String, String, RunResult> sandboxRun = (language, code) -> new RunResult(RunStatus.OK, "ran " + code + "\n", "", 0, 5, false);

    final MessageSender messages = new MessageSender() {
        public void send(String queueUrl, Object message) { throw new UnsupportedOperationException(); }

        public void publish(String topicArn, Object message) {
            assertThat(topicArn).isEqualTo("run-events");
            if (topicDown && message instanceof RunEvent.Completed) throw new IllegalStateException("message too large");
            published.add((RunEvent) message);
        }
    };

    final Sandbox sandbox = (language, code) -> sandboxRun.apply(language, code);
    final JobWorker worker = new JobWorker(queue, sandbox, messages, json, "run-events", 2);

    @AfterEach
    void stop() throws Exception {
        worker.stop();
    }

    RunRequests.Received request(UUID id, String code) throws Exception {
        return new RunRequests.Received(json.writeValueAsString(new RunRequested(id, "python", code)), "receipt-" + id);
    }

    @Test
    void runsTheCodeAndPublishesStartedThenCompleted() throws Exception {
        var id = UUID.randomUUID();
        var message = request(id, "print(1)");
        worker.process(message);

        assertThat(published).hasSize(2);
        assertThat(published.get(0)).isInstanceOf(RunEvent.Started.class);
        var completed = (RunEvent.Completed) published.get(1);
        assertThat(completed.jobId()).isEqualTo(id);
        assertThat(completed.result().stdout()).isEqualTo("ran print(1)\n");
        assertThat(completed.cached()).isFalse();
        assertThat(queue.done).containsExactly(message.receipt());
    }

    @Test
    void aBusySandboxPutsTheRequestBackForLater() throws Exception {
        sandboxRun = (language, code) -> { throw new Sandbox.BusyException(); };
        var message = request(UUID.randomUUID(), "print(1)");
        worker.process(message);

        assertThat(queue.done).isEmpty();
        assertThat(queue.retried).containsExactly(message.receipt());
        assertThat(published).noneMatch(e -> e instanceof RunEvent.Completed || e instanceof RunEvent.Failed);
    }

    @Test
    void aSandboxFailureIsPublishedAndTheRequestDeleted() throws Exception {
        sandboxRun = (language, code) -> { throw new Sandbox.UnsupportedLanguageException(language); };
        var id = UUID.randomUUID();
        worker.process(request(id, "x"));

        var failed = (RunEvent.Failed) published.getLast();
        assertThat(failed.jobId()).isEqualTo(id);
        assertThat(failed.error()).contains("Unsupported language");
        assertThat(queue.done).hasSize(1);
    }

    @Test
    void aResultThatCannotBePublishedIsReportedAsAFailure() throws Exception {
        topicDown = true;
        var id = UUID.randomUUID();
        worker.process(request(id, "print('x' * 100000)"));

        assertThat(published.getLast()).isInstanceOf(RunEvent.Failed.class);
        assertThat(queue.done).hasSize(1);
    }

    @Test
    void aMessageThatIsNotARunRequestIsDropped() {
        worker.process(new RunRequests.Received("{not json", "r1"));
        assertThat(queue.done).containsExactly("r1");
        assertThat(published).isEmpty();
    }

    @Test
    void neverHoldsMoreMessagesThanItHasThreads() throws Exception {
        var running = new AtomicInteger();
        var mostAtOnce = new AtomicInteger();
        sandboxRun = (language, code) -> {
            mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
            sleep(100);
            running.decrementAndGet();
            return new RunResult(RunStatus.OK, "", "", 0, 100, false);
        };
        for (int i = 0; i < 6; i++) queue.waiting.add(request(UUID.randomUUID(), "job " + i));

        worker.run(null);
        for (int i = 0; i < 100 && queue.done.size() < 6; i++) sleep(50);

        assertThat(queue.done).hasSize(6);
        assertThat(mostAtOnce.get()).isLessThanOrEqualTo(2);
        assertThat(queue.largestRequest.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void messagesReceivedWhileStoppingAreHandedBackAtOnce() throws Exception {
        var message = request(UUID.randomUUID(), "print(1)");
        var receiving = new java.util.concurrent.CountDownLatch(1);
        var stopped = new java.util.concurrent.CountDownLatch(1);
        queue.onReceive = () -> {
            // the long poll is waiting when the worker is told to stop; then a message arrives
            receiving.countDown();
            await(stopped);
            return List.of(message);
        };
        worker.run(null);
        await(receiving);
        Thread.ofVirtual().start(() -> {
            try {
                worker.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        sleep(100);
        stopped.countDown();
        for (int i = 0; i < 50 && queue.retried.isEmpty(); i++) sleep(20);

        assertThat(queue.retried).containsExactly(message.receipt());
        assertThat(queue.retriedAfter).containsExactly(0);
        assertThat(published).isEmpty();
    }

    static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** An in-memory run-requests queue. */
    static class FakeQueue implements RunRequests {
        final LinkedBlockingQueue<Received> waiting = new LinkedBlockingQueue<>();
        final List<String> done = new CopyOnWriteArrayList<>();
        final List<String> retried = new CopyOnWriteArrayList<>();
        final List<Integer> retriedAfter = new CopyOnWriteArrayList<>();
        final AtomicInteger largestRequest = new AtomicInteger();
        /** Replaces the next receive, once. */
        volatile java.util.function.Supplier<List<Received>> onReceive;

        @Override
        public List<Received> receive(int max) {
            largestRequest.accumulateAndGet(max, Math::max);
            var hook = onReceive;
            if (hook != null) {
                onReceive = null;
                return hook.get();
            }
            var batch = new ArrayList<Received>();
            try {
                var first = waiting.poll(200, TimeUnit.MILLISECONDS);
                if (first == null) return batch;
                batch.add(first);
                waiting.drainTo(batch, max - 1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return batch;
        }

        @Override
        public void done(Received message) { done.add(message.receipt()); }

        @Override
        public void retryLater(Received message, int seconds) {
            retried.add(message.receipt());
            retriedAfter.add(seconds);
        }
    }
}
