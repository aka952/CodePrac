package dev.codecomp.repo;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** The shared Claude API client. Absent when no credentials are configured, so the app still runs without AI features. */
@Component
class ClaudeClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeClient.class);

    private final AnthropicClient client;

    ClaudeClient() {
        boolean configured = !blank(System.getenv("ANTHROPIC_API_KEY")) || !blank(System.getenv("ANTHROPIC_AUTH_TOKEN"));
        this.client = configured ? AnthropicOkHttpClient.fromEnv() : null;
        if (!configured) log.warn("ANTHROPIC_API_KEY is not set: review grading and Ask AI are disabled");
    }

    AnthropicClient get() {
        if (client == null) throw new ReviewGrader.UnavailableException("AI features are not configured: set ANTHROPIC_API_KEY on the backend");
        return client;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
