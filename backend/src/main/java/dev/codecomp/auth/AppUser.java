package dev.codecomp.auth;

import java.io.Serializable;
import java.util.UUID;

/** The signed-in user, kept in the HTTP session. */
public record AppUser(UUID id, String name, String email, String picture) implements Serializable {}
