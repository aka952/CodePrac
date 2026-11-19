package dev.codecomp.repo;

import dev.codecomp.exercise.Exercise.Difficulty;
import dev.codecomp.repo.RepoContent.Issue;
import dev.codecomp.repo.RepoContent.RepoFile;
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

/** A small multi-file project full of deliberate problems. Files, hidden tests, issues and solution live in {@code content} as JSON. */
@Entity
@Table(name = "repo_exercises")
public class RepoExercise {

    /** One repo exercise as written in repos/*.yml (read by the seed job). */
    public record Def(String slug, String title, String language, Difficulty difficulty, List<String> tags, String description,
                      List<RepoFile> files, List<RepoFile> tests, List<Issue> issues, List<RepoFile> solution) {}

    /** The sandbox language that runs a whole project with its hidden tests, e.g. java -> java-project. */
    public static String projectLanguage(String language) { return language + "-project"; }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String slug;
    private String title;
    private String language;
    @Enumerated(EnumType.STRING)
    private Difficulty difficulty;
    private String tags;
    private String description;
    private String content;
    @Column(name = "sort_order")
    private int sortOrder;

    protected RepoExercise() {}

    RepoExercise(String slug) { this.slug = slug; }

    void apply(Def d, String contentJson, int order) {
        title = d.title();
        language = d.language();
        difficulty = d.difficulty();
        tags = d.tags() == null ? "" : String.join(",", d.tags());
        description = d.description().strip();
        content = contentJson;
        sortOrder = order;
    }

    public Long getId() { return id; }
    public String getSlug() { return slug; }
    public String getTitle() { return title; }
    public String getLanguage() { return language; }
    public Difficulty getDifficulty() { return difficulty; }
    public List<String> getTags() { return tags.isBlank() ? List.of() : Arrays.asList(tags.split(",")); }
    public String getDescription() { return description; }
    public String getContent() { return content; }
}
