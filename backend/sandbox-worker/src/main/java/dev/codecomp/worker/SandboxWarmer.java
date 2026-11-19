package dev.codecomp.worker;

import dev.codecomp.sandbox.Sandbox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Pulls the sandbox images at startup, so a user's first run does not time out waiting for a download.
 * Checking the exercise content is no longer done here (the worker cannot read the exercises): the content-tests
 * module checks it in CI, and repo-service measures each repo exercise's baseline through ordinary runs.
 */
@Component
class SandboxWarmer implements ApplicationRunner {

    private final boolean enabled;
    private final Sandbox sandbox;

    SandboxWarmer(@Value("${app.warm-on-startup}") boolean enabled, Sandbox sandbox) {
        this.enabled = enabled;
        this.sandbox = sandbox;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (enabled) Thread.ofVirtual().name("sandbox-warmer").start(sandbox::prepare);
    }
}
