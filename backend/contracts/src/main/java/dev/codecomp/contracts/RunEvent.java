package dev.codecomp.contracts;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.UUID;

/**
 * What happened to a run, published on the run-events topic. run-service and repo-service each read it from their own
 * queue. Delivery is at least once and not in order (Completed can arrive before Started), so every consumer must
 * ignore events it has already applied.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = RunEvent.Started.class, name = "started"),
        @JsonSubTypes.Type(value = RunEvent.Completed.class, name = "completed"),
        @JsonSubTypes.Type(value = RunEvent.Failed.class, name = "failed")
})
public sealed interface RunEvent {

    UUID jobId();

    record Started(UUID jobId, String worker) implements RunEvent {}

    /** {@code cached} is true when run-service answered from its result cache instead of a worker running the code. */
    record Completed(UUID jobId, RunResult result, boolean cached) implements RunEvent {}

    record Failed(UUID jobId, String error) implements RunEvent {}
}
