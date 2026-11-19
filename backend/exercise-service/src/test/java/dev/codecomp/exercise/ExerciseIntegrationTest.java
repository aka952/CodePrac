package dev.codecomp.exercise;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import dev.codecomp.platform.AuthTokens;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.auth.dev-header=true",
        "app.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:exercise;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class ExerciseIntegrationTest {

    /** Stands in for identity-service: tokens signed with {@link #ISSUER_KEY} are the valid ones. */
    static final RSAKey ISSUER_KEY = rsaKey();
    static final RSAKey OTHER_KEY = rsaKey();

    @TestConfiguration
    static class TestKeys {
        @Bean
        @Primary
        JwtDecoder testDecoder() throws Exception {
            var decoder = NimbusJwtDecoder.withPublicKey(ISSUER_KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(AuthTokens.ISSUER));
            return decoder;
        }
    }

    @Autowired
    MockMvc mvc;

    final String user = UUID.randomUUID().toString();

    ResultActions attempt(String slug, String answer) throws Exception {
        return mvc.perform(post("/api/exercises/{slug}/attempts", slug).header("X-User-Id", user)
                .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"" + answer + "\"}"));
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
        mvc.perform(get("/api/points/snippets").header("X-User-Id", user)).andExpect(jsonPath("$.points", is(0)));

        attempt("java-int-overflow", "-2147483648 2147483648\\n").andExpect(jsonPath("$.points", is(10)));
        attempt("java-off-by-one", "3").andExpect(jsonPath("$.points", is(0)));
        attempt("java-off-by-one", "4").andExpect(jsonPath("$.correct", is(true))).andExpect(jsonPath("$.points", is(7)));
        // solving again later does not change what was earned
        attempt("java-off-by-one", "4").andExpect(jsonPath("$.points", is(7)));

        mvc.perform(get("/api/points/snippets").header("X-User-Id", user)).andExpect(jsonPath("$.points", is(17)));
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
        mvc.perform(get("/api/points/snippets").header("X-User-Id", user)).andExpect(jsonPath("$.points", is(0)));
    }

    @Test
    void giveUpRevealsSolution() throws Exception {
        mvc.perform(post("/api/exercises/py-slicing/attempts").header("X-User-Id", user)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"giveUp\":true}"))
                .andExpect(jsonPath("$.correct", is(false)))
                .andExpect(jsonPath("$.solution.answer", notNullValue()));
    }

    @Test
    void rejectsBadRequests() throws Exception {
        mvc.perform(get("/api/exercises")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/exercises").header("X-User-Id", "not-a-uuid")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/exercises/nope").header("X-User-Id", user)).andExpect(status().isNotFound());
    }

    @Test
    void acceptsOnlyTokensSignedByIdentityService() throws Exception {
        var id = UUID.randomUUID();
        mvc.perform(get("/api/points/snippets").cookie(new Cookie(AuthTokens.COOKIE, token(ISSUER_KEY, AuthTokens.ISSUER, id, 3600))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.points", is(0)));
        // also as a bearer header, the way one service calls another for a user
        mvc.perform(get("/api/points/snippets").header("Authorization", "Bearer " + token(ISSUER_KEY, AuthTokens.ISSUER, id, 3600)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/points/snippets").cookie(new Cookie(AuthTokens.COOKIE, token(OTHER_KEY, AuthTokens.ISSUER, id, 3600))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/points/snippets").cookie(new Cookie(AuthTokens.COOKIE, token(ISSUER_KEY, "someone-else", id, 3600))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/points/snippets").cookie(new Cookie(AuthTokens.COOKIE, token(ISSUER_KEY, AuthTokens.ISSUER, id, -120))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/points/snippets").cookie(new Cookie(AuthTokens.COOKIE, "not-a-jwt")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void stateChangingRequestsNeedTheCsrfToken() throws Exception {
        var session = new Cookie(AuthTokens.COOKIE, token(ISSUER_KEY, AuthTokens.ISSUER, UUID.randomUUID(), 3600));
        String body = "{\"answer\":\"4\"}";
        mvc.perform(post("/api/exercises/java-off-by-one/attempts").cookie(session).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        var csrf = mvc.perform(get("/api/exercises").cookie(session)).andReturn().getResponse().getCookie("XSRF-TOKEN");
        mvc.perform(post("/api/exercises/java-off-by-one/attempts").cookie(session, new Cookie("XSRF-TOKEN", csrf.getValue()))
                        .header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    static String token(RSAKey key, String issuer, UUID subject, long validSeconds) {
        var now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(subject.toString())
                .issuedAt(now.minusSeconds(300)).expiresAt(now.plusSeconds(validSeconds)).build();
        var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key))).encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    static RSAKey rsaKey() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyIDFromThumbprint().build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
