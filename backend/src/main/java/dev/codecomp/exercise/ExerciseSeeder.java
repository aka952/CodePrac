package dev.codecomp.exercise;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dev.codecomp.exercise.Exercise.Difficulty;
import dev.codecomp.grading.Grader.ExerciseType;
import dev.codecomp.sandbox.Sandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@Component
@Order(1)
@ConditionalOnExpression("'${app.role}' == 'all' or '${app.role}' == 'seed'")
public class ExerciseSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExerciseSeeder.class);

    public record Def(String slug, String title, String language, ExerciseType type, Difficulty difficulty, List<String> tags,
                      String code, String question, String answer, String explanation, String hint) {}

    private final ExerciseRepository exercises;
    private final Sandbox sandbox;
    private final YAMLMapper yaml = YAMLMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public ExerciseSeeder(ExerciseRepository exercises, Sandbox sandbox) {
        this.exercises = exercises;
        this.sandbox = sandbox;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        var defs = load();
        var slugs = new HashSet<String>();
        for (int i = 0; i < defs.size(); i++) {
            var d = defs.get(i);
            validate(d);
            if (!slugs.add(d.slug())) throw new IllegalStateException("Duplicate exercise slug: " + d.slug());
            var e = exercises.findBySlug(d.slug()).orElseGet(() -> new Exercise(d.slug()));
            e.apply(d, i);
            exercises.save(e);
        }
        log.info("Seeded {} exercises", defs.size());
    }

    List<Def> load() throws IOException {
        var resources = new PathMatchingResourcePatternResolver().getResources("classpath*:exercises/*.yml");
        var defs = new ArrayList<Def>();
        for (Resource r : Stream.of(resources).sorted(Comparator.comparing(Resource::getFilename)).toList()) {
            try (var in = r.getInputStream()) {
                defs.addAll(yaml.readValue(in, new TypeReference<List<Def>>() {}));
            }
        }
        return defs;
    }

    private void validate(Def d) {
        Stream.<Object>of(d.slug(), d.title(), d.language(), d.type(), d.difficulty(), d.code(), d.question(), d.answer(), d.explanation())
                .forEach(v -> Objects.requireNonNull(v, () -> "Exercise '" + d.slug() + "' is missing a required field"));
        if (!sandbox.languages().contains(d.language()))
            throw new IllegalStateException("Exercise '" + d.slug() + "' uses unsupported language " + d.language());
        if (d.type() == ExerciseType.SPOT_BUG) {
            int lines = (int) d.code().stripTrailing().lines().count();
            int line;
            try {
                line = Integer.parseInt(d.answer().strip());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("SPOT_BUG exercise '" + d.slug() + "' answer must be a line number");
            }
            if (line < 1 || line > lines) throw new IllegalStateException("SPOT_BUG exercise '" + d.slug() + "' answer out of range");
        }
    }
}
