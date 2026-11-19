package dev.codecomp.run;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Where this service's messages go: the work queue (to the workers) and the run-events topic (cache hits). */
@Component
class Queues {

    final String runRequests;
    final String runEventsTopic;

    Queues(@Value("${app.messaging.run-requests-queue}") String runRequests,
           @Value("${app.messaging.run-events-topic}") String runEventsTopic) {
        this.runRequests = runRequests;
        this.runEventsTopic = runEventsTopic;
    }
}
