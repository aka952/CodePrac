package dev.codecomp.repo;

import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
class ClaudeSyntaxAssistant implements SyntaxAssistant {

    private static final Logger log = LoggerFactory.getLogger(ClaudeSyntaxAssistant.class);

    private static final String SYSTEM = """
            You are the "Ask AI" helper inside a code-review practice app. The learner is in the middle of an exercise:
            finding the problems in a small project and fixing them. Working that out themselves is the whole point,
            so your job is limited to general programming-language help: syntax, what a keyword or standard-library
            method does, how a language feature behaves, how to write a common construct.

            You have not been shown the exercise. If the learner pastes code and asks what is wrong with it, asks you to
            review, debug or fix specific code, or asks for hints about the exercise, decline in one sentence and say what
            you can help with instead. Explaining a general concept they name (for example "how does == differ from
            equals for strings") is fine, even if it happens to be relevant to their exercise.

            Answer briefly: a few sentences and, where it helps, one short generic example. The answer is shown as plain
            text, so do not use Markdown; put code on its own lines.""";

    private final ClaudeClient claude;
    private final String model;

    ClaudeSyntaxAssistant(ClaudeClient claude, @Value("${app.assistant.model}") String model) {
        this.claude = claude;
        this.model = model;
    }

    @Override
    public String answer(String language, String question) {
        var params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(8000L)
                .system(SYSTEM)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .addUserMessage("The exercise is written in " + language + ".\n\n<question>\n" + question + "\n</question>")
                .build();
        try {
            var response = claude.get().messages().create(params);
            if (response.stopReason().filter(r -> r.equals(StopReason.REFUSAL)).isPresent())
                throw new ReviewGrader.UnavailableException("The assistant declined to answer this question");
            var text = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(t -> t.text())
                    .collect(Collectors.joining())
                    .strip();
            if (text.isEmpty()) throw new ReviewGrader.UnavailableException("The assistant returned no answer");
            return text;
        } catch (AnthropicException e) {
            log.warn("Ask AI failed: {}", e.getMessage());
            throw new ReviewGrader.UnavailableException("Ask AI failed, try again in a moment");
        }
    }
}
