package dev.codecomp.sandbox;

import dev.codecomp.contracts.SandboxConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The real sandbox exists only in the worker; no other service has a way to run code. */
@Configuration
class SandboxBean {

    @Bean(destroyMethod = "close")
    Sandbox sandbox(SandboxConfig config) {
        return new DockerSandbox(config);
    }
}
