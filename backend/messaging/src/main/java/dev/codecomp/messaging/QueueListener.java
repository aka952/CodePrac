package dev.codecomp.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Long-polls one SQS queue and hands each message body to a handler. A message is deleted only after the handler
 * returns; if it throws, the message becomes visible again after the queue's visibility timeout and is retried, and
 * after the queue's maxReceiveCount it moves to the dead-letter queue. So handlers must be idempotent.
 */
public final class QueueListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(QueueListener.class);

    @FunctionalInterface
    public interface Handler {
        void handle(String body) throws Exception;
    }

    private final String name;
    private final SqsClient sqs;
    private final String queueUrl;
    private final Handler handler;
    private volatile boolean running;
    private Thread thread;

    public QueueListener(String name, SqsClient sqs, String queueUrl, Handler handler) {
        this.name = name;
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.handler = handler;
    }

    @Override
    public void start() {
        running = true;
        thread = Thread.ofVirtual().name("queue-" + name).start(this::poll);
        log.info("Listening to {} ({})", name, queueUrl);
    }

    private void poll() {
        while (running) {
            try {
                var messages = sqs.receiveMessage(r -> r.queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(20)).messages();
                for (var m : messages) {
                    try {
                        handler.handle(m.body());
                        sqs.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(m.receiptHandle()));
                    } catch (Exception e) {
                        log.warn("{}: message {} failed, it will be retried: {}", name, m.messageId(), e.toString());
                    }
                }
            } catch (RuntimeException e) {
                if (!running) return;
                log.warn("{}: could not receive messages: {}", name, e.getMessage());
                sleep();
            }
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    @Override
    public boolean isRunning() { return running; }
}
