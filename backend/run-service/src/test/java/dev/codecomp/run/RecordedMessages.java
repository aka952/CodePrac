package dev.codecomp.run;

import dev.codecomp.messaging.MessageSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Stands in for SQS and SNS: keeps what was sent, and can be told to fail like an unreachable queue. */
class RecordedMessages implements MessageSender {

    record Sent(String destination, Object message) {}

    final List<Sent> sent = new CopyOnWriteArrayList<>();
    final List<Sent> published = new CopyOnWriteArrayList<>();
    volatile boolean down;

    @Override
    public void send(String queueUrl, Object message) {
        if (down) throw new IllegalStateException("queue unreachable");
        sent.add(new Sent(queueUrl, message));
    }

    @Override
    public void publish(String topicArn, Object message) {
        if (down) throw new IllegalStateException("topic unreachable");
        published.add(new Sent(topicArn, message));
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        RecordedMessages recordedMessages() { return new RecordedMessages(); }
    }
}
