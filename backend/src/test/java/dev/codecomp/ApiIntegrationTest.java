package dev.codecomp;

import dev.codecomp.repo.ReviewGrader;
import dev.codecomp.repo.ReviewGrader.IssueVerdict;
import dev.codecomp.repo.ReviewGrader.ReviewGrade;
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
            return (exercise, content, review) -> new ReviewGrade(
                    List.of(new IssueVerdict(1, true, true, "spot on")), List.of(), "Good start.");
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
                .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$[*].status", everyItem(is("NEW"))));
        mvc.perform(get("/api/repos/java-checkout-service").header("X-User-Id", user))
                .andExpect(jsonPath("$.files.length()", is(4)))
                .andExpect(jsonPath("$.reviewed", is(false)))
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
                .andExpect(jsonPath("$.reviewed", is(true)))
                .andExpect(jsonPath("$.issues.length()", is(7)));
        mvc.perform(get("/api/repos").header("X-User-Id", user))
                .andExpect(jsonPath("$[0].status", is("ATTEMPTED")));
    }

    @Test
    void repoTestRunsReportEachHiddenTest() throws Exception {
        json("/api/repos/java-checkout-service/test-runs", "{\"files\":{\"src/main/java/com/shop/Cart.java\":\"class Cart {}\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(2)))
                .andExpect(jsonPath("$.passed", is(1)))
                .andExpect(jsonPath("$.allPassed", is(false)))
                .andExpect(jsonPath("$.tests[1].name", is("breaks")))
                .andExpect(jsonPath("$.tests[1].message", is("expected 1 but got 2")));
    }

    @Test
    void repoEndpointsRejectBadInput() throws Exception {
        json("/api/repos/java-checkout-service/test-runs", "{\"files\":{\"Evil.java\":\"x\"}}").andExpect(status().isBadRequest());
        json("/api/repos/java-checkout-service/reviews", "{\"review\":\" \"}").andExpect(status().isBadRequest());
        mvc.perform(get("/api/repos/nope").header("X-User-Id", user)).andExpect(status().isNotFound());
    }
}
