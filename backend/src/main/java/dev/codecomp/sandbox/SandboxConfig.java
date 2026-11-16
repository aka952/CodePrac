package dev.codecomp.sandbox;

import java.util.Map;

public record SandboxConfig(String docker, String runtime, double cpus, int memoryMb, int pidsLimit, int tmpfsMb,
                            int outputLimitBytes, int maxConcurrent, long queueWaitMs, Map<String, Language> languages) {

    public record Language(String image, String file, String command, long timeoutMs) {}

    public SandboxConfig {
        docker = docker == null || docker.isBlank() ? "docker" : docker;
        if (languages == null || languages.isEmpty()) throw new IllegalArgumentException("sandbox.languages must not be empty");
        languages = Map.copyOf(languages);
    }
}
