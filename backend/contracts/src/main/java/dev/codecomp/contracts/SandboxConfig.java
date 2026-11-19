package dev.codecomp.contracts;

import java.util.Map;

/**
 * The {@code sandbox.*} settings in sandbox.yml. The worker uses all of it; the other services only need the language
 * names (to validate requests and exercise files).
 */
public record SandboxConfig(String docker, String runtime, double cpus, int memoryMb, int pidsLimit, int tmpfsMb,
                            int outputLimitBytes, int maxConcurrent, long queueWaitMs, Map<String, Language> languages) {

    public record Language(String image, String file, String command, long timeoutMs) {}

    public SandboxConfig {
        docker = docker == null || docker.isBlank() ? "docker" : docker;
        if (languages == null || languages.isEmpty()) throw new IllegalArgumentException("sandbox.languages must not be empty");
        languages = Map.copyOf(languages);
    }
}
