package dev.codecomp.platform;

import java.util.UUID;

/** The signed-in user, as carried in the sign-in token (or made up from the dev header). */
public record SignedInUser(UUID id, String name, String email, String picture) {}
