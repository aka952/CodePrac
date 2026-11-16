package dev.codecomp.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "repo_submissions")
public class RepoSubmission {

    /** SKIP: the user revealed the issues without a graded review. ASK: one Ask AI question (counts toward the quota only). */
    public enum Kind { REVIEW, SKIP, FIX, ASK }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id")
    private UUID userId;
    @Column(name = "repo_exercise_id")
    private Long repoExerciseId;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    private int score;
    @Column(name = "max_score")
    private int maxScore;
    @Column(name = "created_at")
    private Instant createdAt;
    private String detail;

    protected RepoSubmission() {}

    RepoSubmission(UUID userId, Long repoExerciseId, Kind kind, int score, int maxScore) {
        this.userId = userId;
        this.repoExerciseId = repoExerciseId;
        this.kind = kind;
        this.score = score;
        this.maxScore = maxScore;
        this.createdAt = Instant.now();
    }

    RepoSubmission(UUID userId, Long repoExerciseId, Kind kind, int score, int maxScore, String detail) {
        this(userId, repoExerciseId, kind, score, maxScore);
        this.detail = detail;
    }

    public Long getId() { return id; }
    public int getScore() { return score; }
    public int getMaxScore() { return maxScore; }
    public Instant getCreatedAt() { return createdAt; }
    public String getDetail() { return detail; }
}
