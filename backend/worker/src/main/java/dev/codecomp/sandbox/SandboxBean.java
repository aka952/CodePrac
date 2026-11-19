package dev.codecomp.sandbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The real sandbox exists only in the worker; the API and the seed job have no way to run code. */
@Configuration
class SandboxBean {

    @Bean(destroyMethod = "close")
    Sandbox sandbox(SandboxConfig config) {
        return new DockerSandbox(config);
    }
}
