package dev.codecomp.worker;

import java.util.List;

/** The run-requests queue as the worker uses it. {@link SqsRunRequests} in production; an in-memory one in tests. */
interface RunRequests {

    /** A message and the handle that deletes it or delays it. */
    record Received(String body, String receipt) {}

    /** Waits up to 20 s for up to {@code max} messages. Each stays hidden from other workers until done or the visibility timeout. */
    List<Received> receive(int max);

    /** Finished with it (successfully or not): delete it from the queue. */
    void done(Received message);

    /** Could not start it now: make it visible again after a short delay, for this or another worker. */
    void retryLater(Received message, int seconds);
}
