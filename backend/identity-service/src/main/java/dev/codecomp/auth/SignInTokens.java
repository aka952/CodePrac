package dev.codecomp.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import dev.codecomp.platform.AuthTokens;
import dev.codecomp.platform.SignedInUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Signs sign-in tokens (RS256 JWTs) and publishes the public key as a JWKS, so other services can check tokens
 * without calling this one. A token cannot be revoked before it expires: signing out only deletes the cookie.
 */
@Configuration
public class SignInTokens {

    private static final Logger log = LoggerFactory.getLogger(SignInTokens.class);

    private final RSAKey key;
    private final NimbusJwtEncoder encoder;
    private final Duration lifetime;

    SignInTokens(@Value("${app.auth.jwt-private-key}") String privateKeyPem, @Value("${app.auth.token-days}") int days) throws GeneralSecurityException {
        this.key = privateKeyPem.isBlank() ? generated() : fromPem(privateKeyPem);
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        this.lifetime = Duration.ofDays(days);
    }

    public Duration lifetime() { return lifetime; }

    public String issue(SignedInUser user) {
        var now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer(AuthTokens.ISSUER)
                .subject(user.id().toString())
                .issuedAt(now)
                .expiresAt(now.plus(lifetime))
                .claims(c -> {
                    if (user.name() != null) c.put("name", user.name());
                    if (user.email() != null) c.put("email", user.email());
                    if (user.picture() != null) c.put("picture", user.picture());
                })
                .build();
        var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** The public half only, for {@code GET /api/auth/jwks}. */
    public Map<String, Object> publicKeys() {
        return new JWKSet(key.toPublicJWK()).toJSONObject();
    }

    /** This service checks its own tokens with the key it holds instead of fetching the JWKS over HTTP. */
    @Bean
    JwtDecoder jwtDecoder() throws Exception {
        var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(AuthTokens.ISSUER));
        return decoder;
    }

    private static RSAKey generated() throws GeneralSecurityException {
        log.warn("JWT_PRIVATE_KEY is not set: using a temporary signing key. Everyone is signed out when this service restarts.");
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        return build((RSAPublicKey) pair.getPublic(), (RSAPrivateCrtKey) pair.getPrivate());
    }

    private static RSAKey fromPem(String pem) throws GeneralSecurityException {
        var der = Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "").strip());
        var factory = KeyFactory.getInstance("RSA");
        var privateKey = (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
        var publicKey = (RSAPublicKey) factory.generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
        return build(publicKey, privateKey);
    }

    private static RSAKey build(RSAPublicKey publicKey, RSAPrivateCrtKey privateKey) {
        try {
            return new RSAKey.Builder(publicKey).privateKey(privateKey).keyIDFromThumbprint().build();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
