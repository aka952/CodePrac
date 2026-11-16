package dev.codecomp;

import dev.codecomp.sandbox.DockerSandbox;
import dev.codecomp.sandbox.Sandbox;
import dev.codecomp.sandbox.SandboxConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

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

    @Bean(destroyMethod = "close")
    Sandbox sandbox(SandboxConfig config) {
        return new DockerSandbox(config);
    }
}
