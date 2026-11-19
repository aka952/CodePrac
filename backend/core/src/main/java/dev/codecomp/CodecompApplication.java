package dev.codecomp;

import dev.codecomp.sandbox.SandboxConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The entry point of every process (api, worker, seed, all-in-one). Component scanning finds only what is on that
 * process's classpath, so each jar's contents decide what the process does.
 */
@SpringBootApplication
@EnableScheduling
public class CodecompApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodecompApplication.class, args);
    }

    @Bean
    SandboxConfig sandboxConfig(Environment env) {
        return Binder.get(env).bind("sandbox", SandboxConfig.class).orElseThrow(() -> new IllegalStateException("Missing sandbox.* configuration"));
    }
}
