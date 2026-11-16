# Read the Code: code comprehension practice

Read a snippet, **commit to a prediction**, get graded, then **edit and run the code in a sandbox** to test your understanding.

- 3 exercise types: **predict the output**, **trace a variable**, **spot the bug** (click the line)
- **Repo reviews**: a small multi-file Java project full of planted problems. Write a free-text review (graded by Claude against the planted issues), then fix the code until the hidden tests pass
- 30 seed exercises (16 Java, 14 Python), every expected answer verified by real execution
- Code runs in throwaway Docker containers: no network, read-only FS, non-root, CPU/memory/PID/time/output limits
- Results cached by `sha256(language + code)`, so unchanged exercise code never hits the sandbox twice

## Architecture (MVP)

```
React + TS + Monaco (Vite) ── nginx ──/api──► Spring Boot 3.5 (Java 21, virtual threads)
                                                 ├─ Postgres (Flyway): exercises, attempts, run_cache
                                                 └─ DockerSandbox ──docker run──► python:3.12-alpine / temurin:21-jdk-alpine
                                                       (one fresh hardened container per run)
```

Runs are synchronous in the MVP, with a semaphore capping concurrent containers (`SANDBOX_MAX_CONCURRENT`) and a per-user sliding-window rate limit. Phase 2 swaps the direct call for a Kafka queue and separate executor workers; see the roadmap.

## Quick start

Requires Docker (Desktop or Engine).

```bash
docker pull python:3.12-alpine && docker pull eclipse-temurin:21-jdk-alpine   # optional, the backend pulls them on start
docker compose up --build
```

Open http://localhost:3000.

Repo review grading calls the Claude API. Export `ANTHROPIC_API_KEY` before `docker compose up` to enable it; without a key everything else works and `POST /api/repos/{slug}/reviews` returns `503`.

On startup the backend seeds exercises from `backend/src/main/resources/exercises/*.yml`, pulls the sandbox images, and **pre-runs every exercise**. That warms the cache and logs a WARN if any `predict_output` answer doesn't match real output.

### Local dev without Compose

```bash
cd backend && mvn spring-boot:run          # H2 file DB in ./data, uses your local Docker for runs
cd frontend && npm install && npm run dev  # http://localhost:5173, proxies /api to :8080
```

## API

All endpoints need an `X-User-Id: <uuid>` header (the frontend generates one in localStorage; real auth is phase 2).

| Method | Path | Body | Notes |
|---|---|---|---|
| GET | `/api/exercises?language=&difficulty=` | | summaries + `NEW/ATTEMPTED/SOLVED` |
| GET | `/api/exercises/{slug}` | | code + question; `solution` only if already solved |
| POST | `/api/exercises/{slug}/attempts` | `{answer}` or `{giveUp:true}` | solution revealed when correct, on give-up, or after 3 attempts |
| POST | `/api/runs` | `{language, code}` | `429` + `Retry-After` when rate limited, `503` when all slots busy |
| GET | `/api/languages` | | configured sandbox languages |
| GET | `/api/repos` | | repo exercise summaries + status (`SOLVED` = all hidden tests passed) |
| GET | `/api/repos/{slug}` | | project files + task; `issues` only after the user has submitted a review |
| POST | `/api/repos/{slug}/reviews` | `{review}` | Claude grades the review against the planted issues; reveals them. Counts toward the run rate limit |
| POST | `/api/repos/{slug}/test-runs` | `{files: {path: content}}` | edited files only; compiled with the hidden tests in the sandbox |

## Adding exercises

Append to any `exercises/*.yml` (files load alphabetically, entries in order):

```yaml
- slug: py-example            # unique, used in URLs
  title: Short title
  language: python            # must match a key under sandbox.languages
  type: predict_output        # predict_output | trace | spot_bug
  difficulty: medium          # easy | medium | hard
  tags: [lists, references]
  question: What does this program print?
  code: |
    print([1] * 2)
  answer: |                   # spot_bug: the 1-based line number, e.g. "4"
    [1, 1]
  explanation: >
    Why it behaves this way.
  hint: Optional nudge.
```

Grading: `predict_output` ignores trailing whitespace and line endings. `trace` ignores all whitespace and quote style. `spot_bug` compares line numbers. The seeder fails fast on duplicate slugs, unknown languages or out-of-range bug lines.

## Sandbox security model

Every run is `docker run --rm` with:

| Control | Flag | Stops |
|---|---|---|
| No network | `--network none` | exfiltration, crypto pools, cloud metadata (`169.254.169.254`) |
| Immutable FS | `--read-only` + `--tmpfs /tmp:size=64m,nosuid,nodev` | persistence, filling disk |
| Unprivileged | `--user 65534:65534 --cap-drop ALL --security-opt no-new-privileges` | privilege escalation |
| Memory | `--memory 256m --memory-swap 256m` | memory bombs (→ `MEMORY_LIMIT`) |
| CPU | `--cpus 1` | noisy neighbours |
| Processes | `--pids-limit 64` | fork bombs |
| Wall clock | 5 s Python / 10 s Java, then `docker rm -f` | infinite loops (→ `TIMEOUT`) |
| Output | 64 KB cap, container killed on overflow | output floods (→ `OUTPUT_LIMIT`) |
| Files | `--ulimit nofile=256:256` | FD exhaustion |

Code is piped via stdin, never interpolated into the shell command.

`SandboxEscapeTest` runs real hostile programs against these limits (fork bomb, memory bomb, infinite loop, output flood, network, metadata endpoint, rootfs write, tmpfs fill, uid check). It is skipped automatically when Docker isn't available.

**Stronger isolation:** install [gVisor](https://gvisor.dev/docs/user_guide/install/) and start with `SANDBOX_RUNTIME=runsc docker compose up`. Every container then runs against gVisor's user-space kernel instead of the host kernel.

### ⚠️ Know the trade-off

The backend container mounts `/var/run/docker.sock`, which is effectively root on the host. That is fine for local and portfolio use. Before exposing this publicly:

1. move execution into a separate **executor service** on its own nodes (Phase 2), so the API never holds the socket, and
2. run executors with gVisor (`RuntimeClass: gvisor` on K8s) or Firecracker.

## Adding repo exercises

Append to `repos/*.yml`. Each entry has `slug`, `title`, `language` (needs a `<language>-project` sandbox entry), `difficulty`, `tags`, `description` and:

- `files`: the project as `{path, content}`; this is what the user sees and edits
- `tests`: hidden sources. `RepoTests.java` (default package) runs and prints one `PASS <name>` or `FAIL <name>: <why>` line per test. Use only public constructors and methods so a refactoring fix still compiles
- `issues`: `{id, file, line, category, title, detail}`, the reference list reviews are graded against
- `solution`: replacement files that fix every tested issue

The project, edits and tests are zipped and run as one sandbox job (`sandbox.languages.java-project`). At startup the backend checks that the hidden tests fail on the original code and pass on the solution, and logs a WARN if not; `RepoExercisesTest` asserts the same.

## Tests

```bash
cd backend && mvn test
```

- `GraderTest`: normalization rules
- `ApiIntegrationTest`: full Spring context on H2 with a fake sandbox and fake review grader: listing, hidden answers, reveal-after-3, give-up, caching, rate limiting, error codes, repo review/test-run endpoints
- `RepoExercisesTest`: every repo exercise against real Docker: hidden tests fail on the original code, pass on the reference solution
- `SandboxEscapeTest`: hostile programs against real Docker. Use `SANDBOX_PYTHON_IMAGE`, `SANDBOX_JAVA_IMAGE` and `SANDBOX_RUNTIME` to point it at other images or gVisor.

## Configuration

| Env var | Default |
|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | H2 file DB |
| `SANDBOX_RUNTIME` | Docker default (`runc`); set `runsc` for gVisor |
| `SANDBOX_PYTHON_IMAGE` / `SANDBOX_JAVA_IMAGE` | `python:3.12-alpine` / `eclipse-temurin:21-jdk-alpine` |
| `SANDBOX_MAX_CONCURRENT` | `4` |
| `RUNS_PER_MINUTE` | `20` per user |
| `WARM_ON_STARTUP` | `true` |
| `ANTHROPIC_API_KEY` | unset (repo review grading disabled) |
| `REVIEW_MODEL` | `claude-opus-5-5` |

Limits (memory, pids, timeouts, output cap) live in `application.yml` under `sandbox:`.

## Roadmap

1. **Async execution**: `POST /runs` → Kafka `code-runs` → executor workers → `run-results` → SSE to the browser. Scale workers on consumer lag (KEDA).
2. **Hardening**: executors on a tainted EKS node group, gVisor RuntimeClass, NetworkPolicy deny-all, no IAM role, escape tests in CI.
3. **Auth**: replace `X-User-Id` with real accounts (Spring Security + JWT/OAuth); Redis-backed rate limits.
4. **Learning features**: spaced repetition, streaks, topic progress, "what-if" exercises that require editing code.
5. **AI layer**: LLM grading of "explain this function" answers, generated hints.
