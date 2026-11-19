package dev.codecomp.exercise;

import dev.codecomp.grading.Grader.ExerciseType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Arrays;
import java.util.List;

@Entity
@Table(name = "exercises")
public class Exercise {

    public enum Difficulty { EASY, MEDIUM, HARD }

    /** One exercise as written in exercises/*.yml (read by the seed job). */
    public record Def(String slug, String title, String language, ExerciseType type, Difficulty difficulty, List<String> tags,
                      String code, String question, String answer, String explanation, String hint) {}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String slug;
    private String title;
    private String language;
    @Enumerated(EnumType.STRING)
    @Column(name = "exercise_type")
    private ExerciseType type;
    @Enumerated(EnumType.STRING)
    private Difficulty difficulty;
    private String tags;
    private String code;
    private String question;
    private String answer;
    private String explanation;
    private String hint;
    @Column(name = "sort_order")
    private int sortOrder;

    protected Exercise() {}

    Exercise(String slug) { this.slug = slug; }

    void apply(Def d, int order) {
        title = d.title();
        language = d.language();
        type = d.type();
        difficulty = d.difficulty();
        tags = d.tags() == null ? "" : String.join(",", d.tags());
        code = d.code().stripTrailing() + "\n";
        question = d.question().strip();
        answer = d.answer().stripTrailing();
        explanation = d.explanation().strip();
        hint = d.hint() == null ? null : d.hint().strip();
        sortOrder = order;
    }

    public Long getId() { return id; }
    public String getSlug() { return slug; }
    public String getTitle() { return title; }
    public String getLanguage() { return language; }
    public ExerciseType getType() { return type; }
    public Difficulty getDifficulty() { return difficulty; }
    public List<String> getTags() { return tags.isBlank() ? List.of() : Arrays.asList(tags.split(",")); }
    public String getCode() { return code; }
    public String getQuestion() { return question; }
    public String getAnswer() { return answer; }
    public String getExplanation() { return explanation; }
    public String getHint() { return hint; }
    public int getSortOrder() { return sortOrder; }
}
