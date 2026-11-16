package dev.codecomp.run;

import dev.codecomp.sandbox.RunResult;
import dev.codecomp.sandbox.RunResult.RunStatus;
import dev.codecomp.sandbox.Sandbox;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunServiceTest {

    final Sandbox sandbox = mock(Sandbox.class);
    final RunCacheRepository cache = mock(RunCacheRepository.class);
    final RunService runs = new RunService(sandbox, cache, new RateLimiter(100));
    final UUID user = UUID.randomUUID();

    RunServiceTest() {
        when(sandbox.languages()).thenReturn(Set.of("python"));
        when(cache.findById(anyString())).thenReturn(Optional.empty());
    }

    static RunResult result(RunStatus status) { return new RunResult(status, "", "", 0, 10, false); }

    @Test
    void timeoutsAreNotCachedSoASlowFirstRunCanBeRetried() {
        // e.g. the first run timed out because the sandbox image was still being pulled
        when(sandbox.run("python", "print(1)")).thenReturn(result(RunStatus.TIMEOUT), result(RunStatus.OK));

        assertThat(runs.run(user, "python", "print(1)").status()).isEqualTo(RunStatus.TIMEOUT);
        assertThat(runs.run(user, "python", "print(1)").status()).isEqualTo(RunStatus.OK);

        verify(sandbox, times(2)).run("python", "print(1)");
        verify(cache, times(1)).save(any());
    }

    @Test
    void sandboxErrorsAreNotCached() {
        when(sandbox.run("python", "x")).thenReturn(result(RunStatus.SANDBOX_ERROR));
        runs.run(user, "python", "x");
        verify(cache, never()).save(any());
    }

    @Test
    void deterministicOutcomesAreCached() {
        for (var status : new RunStatus[] {RunStatus.OK, RunStatus.RUNTIME_ERROR, RunStatus.MEMORY_LIMIT, RunStatus.OUTPUT_LIMIT}) {
            assertThat(result(status).cacheable()).as(status.name()).isTrue();
        }
    }
}
