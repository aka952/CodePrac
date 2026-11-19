package dev.codecomp.repo;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codecomp.contracts.JobView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * POST /internal/runs on run-service. A synchronous call on purpose: the browser needs the job id back to follow it.
 * Short timeouts, so a slow run-service fails the request instead of piling up threads here.
 */
@Component
class HttpRunClient implements RunClient {

    private final RestClient http;
    private final ObjectMapper json;

    HttpRunClient(@Value("${app.run-service.url}") String baseUrl, ObjectMapper json) {
        var timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(2));
        timeouts.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(timeouts).build();
        this.json = json;
    }

    @Override
    public JobView submit(UUID jobId, UUID userId, String language, String code) {
        var body = new java.util.HashMap<String, Object>(Map.of("id", jobId, "language", language, "code", code));
        if (userId != null) body.put("userId", userId);
        try {
            return http.post().uri("/internal/runs").contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(JobView.class);
        } catch (RestClientResponseException e) {
            throw new RunServiceException(e.getStatusCode().value(), message(e), e.getResponseHeaders() == null ? null
                    : e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        } catch (ResourceAccessException e) {
            throw new RunServiceException(503, "Code runs are unavailable, try again in a moment", null);
        }
    }

    /** run-service's own explanation ("You already have runs in progress..."), so the browser shows the same text. */
    private String message(RestClientResponseException e) {
        try {
            var problem = json.readTree(e.getResponseBodyAsString());
            for (var field : new String[] {"detail", "message"}) {
                if (problem.hasNonNull(field) && !problem.get(field).asText().isBlank()) return problem.get(field).asText();
            }
        } catch (Exception ignored) {
            // not JSON
        }
        return "The run could not be started";
    }
}
