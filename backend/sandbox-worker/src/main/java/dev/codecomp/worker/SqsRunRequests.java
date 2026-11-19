package dev.codecomp.worker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.List;

/**
 * The run-requests queue on SQS. Its visibility timeout (2 minutes, longer than the slowest run) is what replaces the
 * old heartbeats: a worker that dies mid-run never deletes the message, so it reappears for another worker. The queue's
 * redrive policy moves a message to the dead-letter queue after 3 receives, and run-service then marks the run FAILED.
 */
@Component
class SqsRunRequests implements RunRequests {

    private final SqsClient sqs;
    private final String queueUrl;

    SqsRunRequests(SqsClient sqs, @Value("${app.messaging.run-requests-queue}") String queueUrl) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
    }

    @Override
    public List<Received> receive(int max) {
        return sqs.receiveMessage(r -> r.queueUrl(queueUrl).maxNumberOfMessages(max).waitTimeSeconds(20)).messages().stream()
                .map(m -> new Received(m.body(), m.receiptHandle()))
                .toList();
    }

    @Override
    public void done(Received message) {
        sqs.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(message.receipt()));
    }

    @Override
    public void retryLater(Received message, int seconds) {
        sqs.changeMessageVisibility(r -> r.queueUrl(queueUrl).receiptHandle(message.receipt()).visibilityTimeout(seconds));
    }
}
