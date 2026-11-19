package dev.codecomp.platform;

/** How the sign-in token travels. identity-service issues it; every service checks it. */
public final class AuthTokens {

    /** HTTP-only cookie holding the signed JWT. */
    public static final String COOKIE = "cc_session";

    /** The {@code iss} claim. A token from any other issuer is rejected. */
    public static final String ISSUER = "codecomp-identity";

    /** Dev and tests only, when app.auth.dev-header is on: the caller's user id, trusted as-is. */
    public static final String DEV_HEADER = "X-User-Id";

    private AuthTokens() {}
}
