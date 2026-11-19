package dev.codecomp.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;
    private String provider;
    private String subject;
    private String email;
    private String name;
    private String picture;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected User() {}

    User(String provider, String subject) {
        this.id = UUID.randomUUID();
        this.provider = provider;
        this.subject = subject;
        this.createdAt = Instant.now();
    }

    /** Refreshes the profile details the provider sent with this sign-in. */
    void signedIn(String email, String name, String picture) {
        this.email = email;
        this.name = name;
        this.picture = picture;
        this.lastLoginAt = Instant.now();
    }

    AppUser toAppUser() { return new AppUser(id, name, email, picture); }
}
