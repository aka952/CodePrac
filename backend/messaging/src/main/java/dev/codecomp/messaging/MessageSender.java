package dev.codecomp.messaging;

/** Sends messages as JSON. Tests replace it with a recorder, so no service test needs AWS or LocalStack. */
public interface MessageSender {

    /** Sends to one SQS queue (point to point: one worker takes it). */
    void send(String queueUrl, Object message);

    /** Publishes to an SNS topic (fan-out: every subscribed queue gets a copy). */
    void publish(String topicArn, Object message);
}
