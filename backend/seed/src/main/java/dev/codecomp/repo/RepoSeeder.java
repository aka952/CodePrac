package dev.codecomp.repo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dev.codecomp.repo.RepoExercise.Def;
import dev.codecomp.repo.RepoContent.RepoFile;
import dev.codecomp.sandbox.SandboxConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@Component
@Order(1)
public class RepoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RepoSeeder.class);
    static final String TEST_MAIN = "RepoTests.java";

    private final RepoExerciseRepository repos;
    private final SandboxConfig sandbox;
    private final ObjectMapper json;
    private final YAMLMapper yaml = YAMLMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    RepoSeeder(RepoExerciseRepository repos, SandboxConfig sandbox, ObjectMapper json) {
        this.repos = repos;
        this.sandbox = sandbox;
        this.json = json;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        var defs = load();
        var slugs = new HashSet<String>();
        for (int i = 0; i < defs.size(); i++) {
            var d = defs.get(i);
            validate(d);
            if (!slugs.add(d.slug())) throw new IllegalStateException("Duplicate repo exercise slug: " + d.slug());
            var content = new RepoContent(normalize(d.files()), normalize(d.tests()), d.issues(),
                    d.solution() == null ? List.of() : normalize(d.solution()));
            var e = repos.findBySlug(d.slug()).orElseGet(() -> new RepoExercise(d.slug()));
            e.apply(d, json.writeValueAsString(content), i);
            repos.save(e);
        }
        log.info("Seeded {} repo exercises", defs.size());
    }

    List<Def> load() throws IOException {
        var resources = new PathMatchingResourcePatternResolver().getResources("classpath*:repos/*.yml");
        var defs = new ArrayList<Def>();
        for (Resource r : Stream.of(resources).sorted(Comparator.comparing(Resource::getFilename)).toList()) {
            try (var in = r.getInputStream()) {
                defs.addAll(yaml.readValue(in, new TypeReference<List<Def>>() {}));
            }
        }
        return defs;
    }

    private static List<RepoFile> normalize(List<RepoFile> files) {
        return files.stream().map(f -> new RepoFile(f.path(), f.content().stripTrailing() + "\n")).toList();
    }

    private void validate(Def d) {
        String s = d.slug();
        Stream.<Object>of(s, d.title(), d.language(), d.difficulty(), d.description(), d.files(), d.tests(), d.issues())
                .forEach(v -> Objects.requireNonNull(v, () -> "Repo exercise '" + s + "' is missing a required field"));
        if (!sandbox.languages().containsKey(RepoExercise.projectLanguage(d.language())))
            throw new IllegalStateException("Repo exercise '" + s + "' needs sandbox language " + RepoExercise.projectLanguage(d.language()));

        var lineCounts = new HashMap<String, Integer>();
        for (var f : d.files()) {
            if (f.path() == null || f.content() == null || !f.path().endsWith(".java") || f.path().startsWith("/") || f.path().contains(".."))
                throw new IllegalStateException("Repo exercise '" + s + "' has an invalid file entry: " + f.path());
            if (lineCounts.put(f.path(), (int) f.content().stripTrailing().lines().count()) != null)
                throw new IllegalStateException("Repo exercise '" + s + "' has duplicate file " + f.path());
        }
        if (d.tests().stream().noneMatch(t -> TEST_MAIN.equals(t.path())))
            throw new IllegalStateException("Repo exercise '" + s + "' tests must include " + TEST_MAIN);
        if (d.tests().stream().anyMatch(t -> lineCounts.containsKey(t.path())))
            throw new IllegalStateException("Repo exercise '" + s + "' test paths must not shadow project files");

        var ids = new HashSet<Integer>();
        for (var issue : d.issues()) {
            if (!ids.add(issue.id())) throw new IllegalStateException("Repo exercise '" + s + "' has duplicate issue id " + issue.id());
            Integer lines = lineCounts.get(issue.file());
            if (lines == null) throw new IllegalStateException("Repo exercise '" + s + "' issue " + issue.id() + " points at unknown file " + issue.file());
            if (issue.line() < 1 || issue.line() > lines)
                throw new IllegalStateException("Repo exercise '" + s + "' issue " + issue.id() + " line out of range");
            if (issue.title() == null || issue.detail() == null || issue.category() == null)
                throw new IllegalStateException("Repo exercise '" + s + "' issue " + issue.id() + " needs category, title and detail");
        }
        if (d.issues().isEmpty()) throw new IllegalStateException("Repo exercise '" + s + "' needs at least one issue");
        if (d.solution() != null && d.solution().stream().anyMatch(f -> !lineCounts.containsKey(f.path())))
            throw new IllegalStateException("Repo exercise '" + s + "' solution may only replace existing files");
    }
}
