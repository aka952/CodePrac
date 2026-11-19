package dev.codecomp.content;

import dev.codecomp.contracts.SandboxConfig;
import dev.codecomp.sandbox.DockerSandbox;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** The real sandbox, configured exactly as in production (sandbox.yml). */
final class Sandboxes {

    private Sandboxes() {}

    static boolean dockerAvailable() {
        try {
            return new ProcessBuilder("docker", "info").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static SandboxConfig config() throws Exception {
        var env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("sandbox", new ClassPathResource("sandbox.yml")).forEach(env.getPropertySources()::addLast);
        return Binder.get(env).bind("sandbox", SandboxConfig.class).get();
    }

    static DockerSandbox start() throws Exception {
        var sandbox = new DockerSandbox(config());
        sandbox.prepare();
        return sandbox;
    }
}
