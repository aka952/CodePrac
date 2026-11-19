package dev.codecomp;

import dev.codecomp.contracts.SandboxConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }

    @Bean
    SandboxConfig sandboxConfig(Environment env) {
        return Binder.get(env).bind("sandbox", SandboxConfig.class).orElseThrow(() -> new IllegalStateException("Missing sandbox.* configuration"));
    }
}
