package dev.codecomp;

import dev.codecomp.repo.CodeEditAssistant;
import dev.codecomp.repo.CodeEditAssistant.CodeEdit;
import dev.codecomp.repo.ReviewGrader;
import dev.codecomp.repo.ReviewGrader.IssueVerdict;
import dev.codecomp.repo.ReviewGrader.ReviewGrade;
import dev.codecomp.repo.SyntaxAssistant;
import dev.codecomp.sandbox.RunResult;
import dev.codecomp.sandbox.RunResult.RunStatus;
import dev.codecomp.sandbox.Sandbox;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.warm-on-startup=false",
        "app.runs-per-minute=2",
        "spring.datasource.url=jdbc:h2:mem:api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class ApiIntegrationTest {

    static final AtomicInteger sandboxCalls = new AtomicInteger();
    static final AtomicInteger graderCalls = new AtomicInteger();
    static final AtomicInteger assistantCalls = new AtomicInteger();

    @TestConfiguration
    static class FakeSandbox {
        @Bean
        @Primary
        Sandbox fakeSandbox() {
            return new Sandbox() {
                public Set<String> languages() { return Set.of("java", "python", "java-project"); }

                public RunResult run(String language, String code) {
                    sandboxCalls.incrementAndGet();
                    if (language.equals("java-project"))
                        return new RunResult(RunStatus.RUNTIME_ERROR, "PASS works\nFAIL breaks: expected 1 but got 2\n", "", 1, 5, false);
                    return new RunResult(RunStatus.OK, "ran " + language + "\n", "", 0, 5, false);
                }
            };
        }

        /** Says the review found issue 1 with a correct fix, and nothing else. */
        @Bean
        @Primary
        ReviewGrader fakeGrader() {
            return (exercise, content, review) -> {
                graderCalls.incrementAndGet();
                return new ReviewGrade(List.of(new IssueVerdict(1, true, true, "spot on")), List.of(), "Good start.");
            };
        }

        /** Upper-cases the selection, or declines when asked to "fix it". */
        @Bean
        @Primary
        CodeEditAssistant fakeEditor() {
            return (language, file, selection, instruction) -> instruction.equals("fix it")
                    ? new CodeEdit(selection, "Say what to change.")
                    : new CodeEdit(selection.toUpperCase(), null);
        }

        @Bean
        @Primary
        SyntaxAssistant fakeAssistant() {
            return (language, question) -> {
                assistantCalls.incrementAndGet();
                return "about " + language + ": " + question;
            };
        }
    }

    @Autowired
    MockMvc mvc;

    final String user = UUID.randomUUID().toString();

    ResultActions attempt(String slug, String answer) throws Exception {
        return mvc.perform(post("/api/exercises/{slug}/attempts", slug).header("X-User-Id", user)
                .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"" + answer + "\"}"));
    }

    ResultActions run(String language, String code) throws Exception {
        return mvc.perform(post("/api/runs").header("X-User-Id", user)
                .contentType(MediaType.APPLICATION_JSON).content("{\"language\":\"" + language + "\",\"code\":\"" + code + "\"}"));
    }

    @Test
    void listsSeededExercisesAndFiltersByLanguage() throws Exception {
        mvc.perform(get("/api/exercises").header("X-User-Id", user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(30)))
                .andExpect(jsonPath("$[*].status", everyItem(is("NEW"))));
        mvc.perform(get("/api/exercises").param("language", "python").header("X-User-Id", user))
                .andExpect(jsonPath("$[*].language", everyItem(is("python"))));
    }

    @Test
    void exerciseViewHidesTheAnswer() throws Exception {
        mvc.perform(get("/api/exercises/java-int-overflow").header("X-User-Id", user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", notNullValue()))
                .andExpect(jsonPath("$.answer").doesNotExist())
                .andExpect(jsonPath("$.solution", nullValue()));
    }

    @Test
    void correctAnswerRevealsSolutionAndMarksSolved() throws Exception {
        attempt("java-int-overflow", "-2147483648 2147483648\\n")
                .andExpect(jsonPath("$.correct", is(true)))
                .andExpect(jsonPath("$.solution.explanation", notNullValue()));
        mvc.perform(get("/api/exercises/java-int-overflow").header("X-User-Id", user))
                .andExpect(jsonPath("$.solved", is(true)))
                .andExpect(jsonPath("$.solution.answer", notNullValue()));
    }

    @Test
    void solutionIsRevealedAfterThreeWrongAttempts() throws Exception {
        attempt("java-off-by-one", "3").andExpect(jsonPath("$.correct", is(false))).andExpect(jsonPath("$.solution", nullValue()));
        attempt("java-off-by-one", "5").andExpect(jsonPath("$.solution", nullValue()));
        attempt("java-off-by-one", "6").andExpect(jsonPath("$.attempts", is(3))).andExpect(jsonPath("$.solution.answer", is("4")));
    }

    @Test
    void pointsRewardSolvingInFewerAttempts() throws Exception {
        mvc.perform(get("/api/points").header("X-User-Id", user)).andExpect(jsonPath("$.total", is(0)));

        attempt("java-int-overflow", "-2147483648 2147483648\\n").andExpect(jsonPath("$.points", is(10)));
        attempt("java-off-by-one", "3").andExpect(jsonPath("$.points", is(0)));
        attempt("java-off-by-one", "4").andExpect(jsonPath("$.correct", is(true))).andExpect(jsonPath("$.points", is(7)));
        // solving again later does not change what was earned
        attempt("java-off-by-one", "4").andExpect(jsonPath("$.points", is(7)));

        mvc.perform(get("/api/points").header("X-User-Id", user))
                .andExpect(jsonPath("$.total", is(17)))
                .andExpect(jsonPath("$.snippets", is(17)))
                .andExpect(jsonPath("$.repos", is(0)));
        mvc.perform(get("/api/exercises").header("X-User-Id", user))
                .andExpect(jsonPath("$[?(@.slug == 'java-off-by-one')].points", contains(7)))
                .andExpect(jsonPath("$[?(@.slug == 'java-off-by-one')].maxPoints", contains(10)));
        mvc.perform(get("/api/exercises/java-off-by-one").header("X-User-Id", user))
                .andExpect(jsonPath("$.points", is(7)))
                .andExpect(jsonPath("$.maxPoints", is(10)));
    }

    @Test
    void nothingIsEarnedAfterGivingUp() throws Exception {
        mvc.perform(post("/api/exercises/java-off-by-one/attempts").header("X-User-Id", user)
                .contentType(MediaType.APPLICATION_JSON).content("{\"giveUp\":true}"));
        attempt("java-off-by-one", "4").andExpect(jsonPath("$.correct", is(true))).andExpect(jsonPath("$.points", is(0)));
        mvc.perform(get("/api/points").header("X-User-Id", user)).andExpect(jsonPath("$.total", is(0)));
    }

    @Test
    void giveUpRevealsSolution() throws Exception {
        mvc.perform(post("/api/exercises/py-slicing/attempts").header("X-User-Id", user)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"giveUp\":true}"))
                .andExpect(jsonPath("$.correct", is(false)))
                .andExpect(jsonPath("$.solution.answer", notNullValue()));
    }

    @Test
    void runsAreCachedAndRateLimited() throws Exception {
        int before = sandboxCalls.get();
        run("python", "print(1)").andExpect(status().isOk()).andExpect(jsonPath("$.cached", is(false)));
        run("python", "print(1)").andExpect(jsonPath("$.cached", is(true)));
        run("python", "print(2)").andExpect(status().isOk());
        run("python", "print(3)").andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        run("python", "print(1)").andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(sandboxCalls.get() - before).isEqualTo(2);
    }

    @Test
    void rejectsBadRequests() throws Exception {
        mvc.perform(get("/api/exercises")).andExpect(status().isBadRequest());
        run("cobol", "DISPLAY 'HI'").andExpect(status().isBadRequest());
        mvc.perform(get("/api/exercises/nope").header("X-User-Id", user)).andExpect(status().isNotFound());
    }

    ResultActions json(String path, String body) throws Exception {
        return mvc.perform(post(path).header("X-User-Id", user).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void repoIssuesStayHiddenUntilReviewed() throws Exception {
        mvc.perform(get("/api/repos").header("X-User-Id", user))
                .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(5)))
                .andExpect(jsonPath("$[0].difficulty", is("EASY")))
                .andExpect(jsonPath("$[*].status", everyItem(is("NEW"))));
        mvc.perform(get("/api/repos/java-checkout-service").header("X-User-Id", user))
                .andExpect(jsonPath("$.files.length()", is(4)))
                .andExpect(jsonPath("$.revealed", is(false)))
                .andExpect(jsonPath("$.issues", nullValue()))
                .andExpect(jsonPath("$.tests").doesNotExist())
                .andExpect(jsonPath("$.solution").doesNotExist());

        json("/api/repos/java-checkout-service/reviews", "{\"review\":\"Inventory.find compares strings with ==\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found", is(1)))
                .andExpect(jsonPath("$.total", is(7)))
                .andExpect(jsonPath("$.fixesCorrect", is(1)))
                .andExpect(jsonPath("$.issues[0].found", is(true)))
                .andExpect(jsonPath("$.issues[0].issue.line", is(15)))
                .andExpect(jsonPath("$.issues[1].found", is(false)));

        mvc.perform(get("/api/repos/java-checkout-service").header("X-User-Id", user))
                .andExpect(jsonPath("$.revealed", is(true)))
                .andExpect(jsonPath("$.issues.length()", is(7)));
        mvc.perform(get("/api/repos").header("X-User-Id", user))
                .andExpect(jsonPath("$[?(@.slug == 'java-checkout-service')].status", contains("ATTEMPTED")));
    }

    @Test
    void skippingTheReviewRevealsIssuesWithoutGrading() throws Exception {
        int before = graderCalls.get();
        json("/api/repos/java-user-registry/reveal", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revealed", is(true)))
                .andExpect(jsonPath("$.issues.length()", is(10)));
        mvc.perform(get("/api/repos/java-user-registry").header("X-User-Id", user))
                .andExpect(jsonPath("$.revealed", is(true)))
                .andExpect(jsonPath("$.issues.length()", is(10)));
        org.assertj.core.api.Assertions.assertThat(graderCalls.get()).isEqualTo(before);
    }

    @Test
    void repoTestRunsReportEachHiddenTest() throws Exception {
        json("/api/repos/java-checkout-service/test-runs", "{\"files\":{\"src/main/java/com/shop/Cart.java\":\"class Cart {}\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(2)))
                .andExpect(jsonPath("$.passed", is(1)))
                .andExpect(jsonPath("$.allPassed", is(false)))
                // the fake sandbox passes the same single test for the untouched code, so nothing was won
                .andExpect(jsonPath("$.points", is(0)))
                .andExpect(jsonPath("$.maxPoints", is(100)))
                .andExpect(jsonPath("$.tests[1].name", is("breaks")))
                .andExpect(jsonPath("$.tests[1].message", is("expected 1 but got 2")));
    }

    @Test
    void testRunsAreKeptAsHistoryNewestFirst() throws Exception {
        mvc.perform(get("/api/repos/java-user-registry/test-runs").header("X-User-Id", user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(0)));
        json("/api/repos/java-user-registry/test-runs", "{\"files\":{}}").andExpect(status().isOk());
        mvc.perform(get("/api/repos/java-user-registry/test-runs").header("X-User-Id", user))
                .andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$[0].passed", is(1)))
                .andExpect(jsonPath("$[0].total", is(2)))
                .andExpect(jsonPath("$[0].allPassed", is(false)))
                .andExpect(jsonPath("$[0].createdAt", notNullValue()))
                .andExpect(jsonPath("$[0].tests[1].message", is("expected 1 but got 2")));
        mvc.perform(get("/api/repos/java-user-registry/test-runs").header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(jsonPath("$.length()", is(0)));
    }

    @Test
    void askAiAnswersUntilTheQuotaRunsOut() throws Exception {
        mvc.perform(get("/api/repos/java-user-registry").header("X-User-Id", user))
                .andExpect(jsonPath("$.questionsLeft", is(5)));
        json("/api/repos/java-user-registry/questions", "{\"question\":\"what is a record\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", is("about java: what is a record")))
                .andExpect(jsonPath("$.questionsLeft", is(4)));
        for (int i = 0; i < 4; i++) json("/api/repos/java-user-registry/questions", "{\"question\":\"q\"}").andExpect(status().isOk());
        int calls = assistantCalls.get();
        json("/api/repos/java-user-registry/questions", "{\"question\":\"one more\"}").andExpect(status().isTooManyRequests());
        org.assertj.core.api.Assertions.assertThat(assistantCalls.get()).isEqualTo(calls);
        mvc.perform(get("/api/repos/java-user-registry").header("X-User-Id", user))
                .andExpect(jsonPath("$.questionsLeft", is(0)));
        // asking questions is not an attempt, and the quota is per exercise
        mvc.perform(get("/api/repos").header("X-User-Id", user))
                .andExpect(jsonPath("$[?(@.slug == 'java-user-registry')].status", contains("NEW")));
        mvc.perform(get("/api/repos/java-checkout-service").header("X-User-Id", user))
                .andExpect(jsonPath("$.questionsLeft", is(5)));
    }

    @Test
    void inlineEditRewritesTheSelection() throws Exception {
        String body = "{\"language\":\"java\",\"code\":\"int a = 1;\\nint b = 2;\\n\",\"selection\":\"int b = 2;\",\"instruction\":\"%s\"}";
        json("/api/edits", body.formatted("shout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replacement", is("INT B = 2;")))
                .andExpect(jsonPath("$.note", nullValue()));
        json("/api/edits", body.formatted("fix it"))
                .andExpect(jsonPath("$.replacement", is("int b = 2;")))
                .andExpect(jsonPath("$.note", is("Say what to change.")));
    }

    @Test
    void inlineEditRejectsBadInput() throws Exception {
        json("/api/edits", "{\"language\":\"java\",\"code\":\"int a;\",\"selection\":\"int a;\",\"instruction\":\" \"}")
                .andExpect(status().isBadRequest());
        json("/api/edits", "{\"language\":\"java\",\"code\":\"int a;\",\"selection\":\"int zzz;\",\"instruction\":\"rename\"}")
                .andExpect(status().isBadRequest());
        json("/api/edits", "{\"language\":\"cobol\",\"code\":\"x\",\"selection\":\"x\",\"instruction\":\"rename\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void repoEndpointsRejectBadInput() throws Exception {
        json("/api/repos/java-checkout-service/test-runs", "{\"files\":{\"Evil.java\":\"x\"}}").andExpect(status().isBadRequest());
        json("/api/repos/java-checkout-service/reviews", "{\"review\":\" \"}").andExpect(status().isBadRequest());
        json("/api/repos/java-checkout-service/questions", "{\"question\":\" \"}").andExpect(status().isBadRequest());
        mvc.perform(get("/api/repos/nope").header("X-User-Id", user)).andExpect(status().isNotFound());
    }
}
