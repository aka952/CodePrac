package dev.codecomp;

import dev.codecomp.contracts.SandboxConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RepoApplication {

    public static void main(String[] args) {
        SpringApplication.run(RepoApplication.class, args);
    }

    @Bean
    SandboxConfig sandboxConfig(Environment env) {
        return Binder.get(env).bind("sandbox", SandboxConfig.class).orElseThrow(() -> new IllegalStateException("Missing sandbox.* configuration"));
    }
}
