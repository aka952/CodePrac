package dev.codecomp.repo;

import java.util.List;

/**
 * @param files    the project as shown to (and editable by) the user
 * @param tests    hidden test sources; must contain a {@code RepoTests} class printing {@code PASS name} / {@code FAIL name: why}
 * @param issues   the reference list of problems the review is graded against
 * @param solution replacement files that fix every testable issue; only used to verify the exercise
 */
public record RepoContent(List<RepoFile> files, List<RepoFile> tests, List<Issue> issues, List<RepoFile> solution) {

    public record RepoFile(String path, String content) {}

    public record Issue(int id, String file, int line, String category, String title, String detail) {}
}
