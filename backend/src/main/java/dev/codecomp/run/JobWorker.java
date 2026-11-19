package dev.codecomp.run;

import dev.codecomp.repo.RepoService;
import dev.codecomp.sandbox.RunResult;
import dev.codecomp.sandbox.Sandbox;
import dev.codecomp.web.Api.RunResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Takes jobs from the queue and runs them, at most {@code app.worker.threads} at a time. Runs in the worker process
 * (APP_ROLE=worker), or inside the API process when APP_ROLE=all (local development, tests).
 */
@Component
@ConditionalOnExpression("'${app.role}' == 'all' or '${app.role}' == 'worker'")
class JobWorker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobService jobs;
    private final RunService runs;
    private final RepoService repos;
    private final TransactionTemplate tx;
    private final int threads;
    private final long pollMs;
    private final String name;
    private final Semaphore slots;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;

    JobWorker(JobService jobs, RunService runs, RepoService repos, PlatformTransactionManager transactions,
              @Value("${app.worker.threads}") int threads, @Value("${app.worker.poll-ms}") long pollMs) {
        this.jobs = jobs;
        this.runs = runs;
        this.repos = repos;
        this.tx = new TransactionTemplate(transactions);
        this.threads = threads;
        this.pollMs = pollMs;
        this.slots = new Semaphore(threads);
        this.name = hostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread.ofVirtual().name("job-dispatcher").start(this::dispatch);
        log.info("Worker {} started with {} threads", name, threads);
    }

    /** Claims a job whenever a thread is free; sleeps briefly when the queue is empty. */
    private void dispatch() {
        while (!stopping) {
            slots.acquireUninterruptibly();
            RunJob job = null;
            try {
                job = jobs.claimNext(name).orElse(null);
            } catch (RuntimeException e) {
                log.warn("Could not claim a job: {}", e.getMessage());
            }
            if (job == null) {
                slots.release();
                sleep(pollMs);
                continue;
            }
            var claimed = job;
            running.add(claimed.getId());
            pool.submit(() -> {
                try {
                    process(claimed);
                } finally {
                    running.remove(claimed.getId());
                    slots.release();
                }
            });
        }
    }

    private void process(RunJob job) {
        try {
            var hit = runs.cached(job.getLanguage(), job.getCode());
            RunResult result;
            try {
                result = hit.orElseGet(() -> runs.execute(job.getLanguage(), job.getCode()));
            } catch (Sandbox.BusyException e) {
                // every sandbox slot is taken (for example by the startup warm-up): try again later
                jobs.release(job.getId(), name);
                return;
            }
            var response = RunResponse.of(result, hit.isPresent());
            // recording the test run and completing the job happen together, so a crash cannot record it twice
            tx.executeWithoutResult(status -> {
                Object payload = switch (job.getKind()) {
                    case SNIPPET -> response;
                    case REPO_TEST -> repos.completeTestRun(job.getUserId(), job.getRepoSlug(), response);
                };
                if (!jobs.complete(job.getId(), name, payload)) {
                    status.setRollbackOnly();
                    log.warn("Job {} was reassigned before it finished; result discarded", job.getId());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Job {} failed: {}", job.getId(), e.toString());
            jobs.fail(job.getId(), name, "The run could not be completed: " + e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 10_000)
    void heartbeat() {
        try {
            jobs.heartbeat(List.copyOf(running), name);
        } catch (RuntimeException e) {
            log.warn("Heartbeat failed: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    void recoverStale() {
        int requeued = jobs.recoverStale();
        if (requeued > 0) log.warn("Requeued {} jobs from workers that stopped responding", requeued);
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    void deleteOldFinished() {
        jobs.deleteOldFinished();
    }

    @PreDestroy
    void stop() throws InterruptedException {
        stopping = true;
        pool.shutdown();
        // anything still running after this is requeued by another worker once its heartbeat goes stale
        pool.awaitTermination(25, TimeUnit.SECONDS);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "worker";
        }
    }
}
