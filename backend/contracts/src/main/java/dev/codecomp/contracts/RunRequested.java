package dev.codecomp.contracts;

import java.util.UUID;

/** A message on the run-requests queue: run-service asks a worker to run this code. */
public record RunRequested(UUID jobId, String language, String code) {}
