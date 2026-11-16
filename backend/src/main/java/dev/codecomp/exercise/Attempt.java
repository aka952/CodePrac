package dev.codecomp.exercise;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "attempts")
public class Attempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id")
    private UUID userId;
    @Column(name = "exercise_id")
    private Long exerciseId;
    private String answer;
    private boolean correct;
    @Column(name = "created_at")
    private Instant createdAt;

    protected Attempt() {}

    Attempt(UUID userId, Long exerciseId, String answer, boolean correct) {
        this.userId = userId;
        this.exerciseId = exerciseId;
        this.answer = answer;
        this.correct = correct;
        this.createdAt = Instant.now();
    }

    public boolean isCorrect() { return correct; }
}
