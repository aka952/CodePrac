package dev.codecomp.run;

import dev.codecomp.contracts.RunResult;
import dev.codecomp.contracts.RunResult.RunStatus;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunCacheTest {

    final RunCacheRepository rows = mock(RunCacheRepository.class);
    final RunCache cache = new RunCache(rows);

    RunCacheTest() {
        when(rows.findById(anyString())).thenReturn(Optional.empty());
    }

    static RunResult result(RunStatus status) { return new RunResult(status, "", "", 0, 10, false); }

    @Test
    void timeoutsAreNotCachedSoASlowFirstRunCanBeRetried() {
        // e.g. the first run timed out because the sandbox image was still being pulled
        cache.put("python", "print(1)", result(RunStatus.TIMEOUT));
        verify(rows, never()).save(any());
        cache.put("python", "print(1)", result(RunStatus.OK));
        verify(rows, times(1)).save(any());
    }

    @Test
    void sandboxErrorsAreNotCached() {
        cache.put("python", "x", result(RunStatus.SANDBOX_ERROR));
        verify(rows, never()).save(any());
    }

    @Test
    void deterministicOutcomesAreCached() {
        for (var status : new RunStatus[] {RunStatus.OK, RunStatus.RUNTIME_ERROR, RunStatus.MEMORY_LIMIT, RunStatus.OUTPUT_LIMIT}) {
            assertThat(result(status).cacheable()).as(status.name()).isTrue();
        }
    }

    @Test
    void theKeyDependsOnLanguageAndCode() {
        assertThat(RunCache.hash("python", "print(1)")).isEqualTo(RunCache.hash("python", "print(1)"))
                .isNotEqualTo(RunCache.hash("python", "print(2)"))
                .isNotEqualTo(RunCache.hash("java", "print(1)"));
    }
}
