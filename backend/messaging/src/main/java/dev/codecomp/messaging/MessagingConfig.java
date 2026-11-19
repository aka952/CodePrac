package dev.codecomp.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

/**
 * The AWS clients. Credentials come from the default chain: the task role on ECS, AWS_ACCESS_KEY_ID locally.
 * {@code app.aws.endpoint} points them at LocalStack for local development.
 */
@Configuration
class MessagingConfig {

    private final Region region;
    private final String endpoint;

    MessagingConfig(@Value("${app.aws.region:us-east-1}") String region, @Value("${app.aws.endpoint:}") String endpoint) {
        this.region = Region.of(region);
        this.endpoint = endpoint;
    }

    @Bean(destroyMethod = "close")
    SqsClient sqsClient() {
        var builder = SqsClient.builder().region(region);
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    SnsClient snsClient() {
        var builder = SnsClient.builder().region(region);
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }

    @Bean
    MessageSender messageSender(SqsClient sqs, SnsClient sns, ObjectMapper json) {
        return new MessageSender() {
            @Override
            public void send(String queueUrl, Object message) {
                sqs.sendMessage(r -> r.queueUrl(queueUrl).messageBody(write(message)));
            }

            @Override
            public void publish(String topicArn, Object message) {
                sns.publish(r -> r.topicArn(topicArn).message(write(message)));
            }

            private String write(Object message) {
                try {
                    return json.writeValueAsString(message);
                } catch (JsonProcessingException e) {
                    throw new IllegalArgumentException(e);
                }
            }
        };
    }
}
