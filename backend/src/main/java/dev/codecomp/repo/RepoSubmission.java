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

    /** SKIP: the user revealed the issues without a graded review. */
    public enum Kind { REVIEW, SKIP, FIX }

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

    protected RepoSubmission() {}

    RepoSubmission(UUID userId, Long repoExerciseId, Kind kind, int score, int maxScore) {
        this.userId = userId;
        this.repoExerciseId = repoExerciseId;
        this.kind = kind;
        this.score = score;
        this.maxScore = maxScore;
        this.createdAt = Instant.now();
    }
}
