package dev.codecomp.contracts;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * A queued code run as the browser sees it. run-service returns it; repo-service passes it through for test runs.
 * {@code status} is QUEUED, RUNNING, DONE or FAILED; {@code position} is set while QUEUED (1 = next);
 * {@code result} is the run's response once DONE; {@code id} is null for a snippet answered from the cache.
 */
public record JobView(UUID id, String status, Integer position, JsonNode result, String error) {}
