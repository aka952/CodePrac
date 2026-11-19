package dev.codecomp.sandbox;

import dev.codecomp.contracts.RunResult;
import dev.codecomp.contracts.RunResult.RunStatus;
import dev.codecomp.contracts.SandboxConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public final class DockerSandbox implements Sandbox, AutoCloseable {

    private static final Logger log = Logger.getLogger(DockerSandbox.class.getName());

    private final SandboxConfig cfg;
    private final Semaphore slots;
    private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();

    public DockerSandbox(SandboxConfig cfg) {
        this.cfg = cfg;
        this.slots = new Semaphore(cfg.maxConcurrent(), true);
    }

    @Override
    public void prepare() {
        cfg.languages().values().stream().map(SandboxConfig.Language::image).distinct().forEach(image -> {
            if (exec(List.of(cfg.docker(), "image", "inspect", image), 30) == 0) return;
            log.info(() -> "Pulling sandbox image " + image);
            if (exec(List.of(cfg.docker(), "pull", image), 900) != 0) log.warning(() -> "Failed to pull " + image);
        });
    }

    @Override
    public RunResult run(String language, String code) {
        var lang = cfg.languages().get(language);
        if (lang == null) throw new UnsupportedLanguageException(language);
        try {
            if (!slots.tryAcquire(cfg.queueWaitMs(), MILLISECONDS)) throw new BusyException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusyException();
        }
        try {
            return execute(lang, code);
        } finally {
            slots.release();
        }
    }

    List<String> command(String name, SandboxConfig.Language lang) {
        var c = new ArrayList<>(List.of(cfg.docker(), "run", "--rm", "-i", "--name", name, "--label", "codecomp.run=true",
                "--network", "none", "--read-only",
                "--tmpfs", "/tmp:rw,nosuid,nodev,size=%dm,mode=1777".formatted(cfg.tmpfsMb()),
                "--memory", cfg.memoryMb() + "m", "--memory-swap", cfg.memoryMb() + "m",
                "--cpus", String.valueOf(cfg.cpus()), "--pids-limit", String.valueOf(cfg.pidsLimit()),
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--user", "65534:65534", "--ulimit", "nofile=256:256", "--workdir", "/tmp",
                "--env", "HOME=/tmp", "--log-driver", "none"));
        if (cfg.runtime() != null && !cfg.runtime().isBlank()) c.addAll(List.of("--runtime", cfg.runtime()));
        c.addAll(List.of(lang.image(), "/bin/sh", "-c", "cat > /tmp/" + lang.file() + " && " + lang.command()));
        return c;
    }

    private RunResult execute(SandboxConfig.Language lang, String code) {
        var name = "cc-run-" + UUID.randomUUID();
        var overflow = new AtomicBoolean();
        long start = System.nanoTime();
        Process p;
        try {
            p = new ProcessBuilder(command(name, lang)).start();
        } catch (IOException e) {
            return RunResult.sandboxError("Cannot start docker: " + e.getMessage(), 0);
        }
        Runnable onOverflow = () -> { if (overflow.compareAndSet(false, true)) io.submit(() -> kill(name)); };
        var out = io.submit(() -> drain(p.getInputStream(), onOverflow));
        var err = io.submit(() -> drain(p.getErrorStream(), onOverflow));
        try (var stdin = p.getOutputStream()) {
            stdin.write(code.getBytes(UTF_8));
        } catch (IOException ignored) {
        }
        boolean finished = await(p, lang.timeoutMs());
        if (!finished) {
            kill(name);
            p.destroyForcibly();
            await(p, 5_000);
        }
        var o = collect(out);
        var e = collect(err);
        long ms = (System.nanoTime() - start) / 1_000_000;
        int exit = p.isAlive() ? -1 : p.exitValue();
        var status = !finished ? RunStatus.TIMEOUT : overflow.get() ? RunStatus.OUTPUT_LIMIT : statusFor(exit);
        if (status == RunStatus.SANDBOX_ERROR) log.warning(() -> "Sandbox failure exit=" + exit + " stderr=" + e.text());
        return new RunResult(status, o.text(), e.text(), exit, ms, o.truncated() || e.truncated());
    }

    static RunStatus statusFor(int exit) {
        return switch (exit) {
            case 0 -> RunStatus.OK;
            case 137 -> RunStatus.MEMORY_LIMIT;
            case 125, 126, 127 -> RunStatus.SANDBOX_ERROR;
            default -> RunStatus.RUNTIME_ERROR;
        };
    }

    private record Captured(String text, boolean truncated) {}

    private Captured drain(InputStream in, Runnable onOverflow) throws IOException {
        var buf = new ByteArrayOutputStream();
        var chunk = new byte[8192];
        boolean truncated = false;
        try (in) {
            for (int n; (n = in.read(chunk)) != -1; ) {
                int room = cfg.outputLimitBytes() - buf.size();
                if (room > 0) buf.write(chunk, 0, Math.min(n, room));
                if (n > room && !truncated) {
                    truncated = true;
                    onOverflow.run();
                }
            }
        }
        return new Captured(buf.toString(UTF_8), truncated);
    }

    private static Captured collect(Future<Captured> f) {
        try {
            return f.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            f.cancel(true);
            return new Captured("", false);
        }
    }

    private static boolean await(Process p, long ms) {
        try {
            return p.waitFor(ms, MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void kill(String name) {
        exec(List.of(cfg.docker(), "rm", "-f", name), 15);
    }

    private static int exec(List<String> cmd, long timeoutSec) {
        try {
            var p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            return p.exitValue();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    @Override
    public void close() { io.shutdownNow(); }
}
