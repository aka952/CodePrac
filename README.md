# Read the Code: code comprehension practice

Read a snippet, **commit to a prediction**, get graded, then **edit and run the code in a sandbox** to test your understanding.

- 3 exercise types: **predict the output**, **trace a variable**, **spot the bug** (click the line)
- **Repo reviews**: a small multi-file Java project full of planted problems. Write a free-text review (graded by Claude against the planted issues), then fix the code until the hidden tests pass
- Repo workspace: statement and file-tree tabs, editor tabs, edits and review draft saved in the browser, a history of previous test runs, and **Ask AI** for syntax-only questions (5 per exercise; the assistant never sees the exercise)
- 30 seed exercises (16 Java, 14 Python), every expected answer verified by real execution
- Code runs in throwaway Docker containers: no network, read-only FS, non-root, CPU/memory/PID/time/output limits
- Results cached by `sha256(language + code)`, so unchanged exercise code never hits the sandbox twice

## Architecture

Microservices split by business domain. Each service owns its tables in its own database; services talk only over HTTP and messages (SQS queues, an SNS topic).

```
React + TS + Monaco ── load balancer (nginx locally, CloudFront + ALB on AWS): routes /api/* by path
   │
   ├─ /api/auth/*                       identity-service   Google sign-in; issues the JWT the others check   [users]
   ├─ /api/exercises*, /points/snippets exercise-service   snippet exercises, grading                        [exercises, attempts]
   ├─ /api/repos*, /edits, /points/repos repo-service      reviews (Claude), Ask AI, inline edits,           [repo_exercises,
   │                                                        records and scores test runs                      repo_submissions, pending_runs]
   │                                         │ POST /internal/runs (inside the network only)
   │                                         ▼
   └─ /api/runs, /jobs/*, /languages    run-service        accepts runs, result cache, SSE to the browser    [run_jobs, run_cache]
                                             │ SQS run-requests
                                             ▼
                                        sandbox-worker     no database, no web server; the only one with Docker
                                             │ docker run (gVisor in production) → one fresh hardened container per run
                                             │ SNS run-events (Started / Completed / Failed)
                                             ├──► SQS run-events-run  → run-service  (job status, result cache)
                                             └──► SQS run-events-repo → repo-service (records and scores the test run)
```

**Sign-in is a token, not a session.** identity-service signs a JWT (RS256) after Google sign-in and puts it in an HTTP-only cookie. Every other service checks the signature itself against identity-service's public keys (`/api/auth/jwks`, fetched once and cached), so no request needs a call to identity-service and no server keeps a session.

**Code runs are queued on SQS.** `POST /api/runs` answers at once with `200` when the same code ran before (result cache). Otherwise run-service saves a `QUEUED` row, sends a message to the `run-requests` queue, and answers `202` with a job id. Workers ask the queue for as many messages as they have free threads. A worker publishes `Started`, runs the code, publishes `Completed` (or `Failed`), and only then deletes the message. If a worker dies mid-run, the message reappears after the queue's visibility timeout and another worker takes it; after 3 tries it goes to a dead-letter queue and run-service marks the run FAILED. The browser follows its job over Server-Sent Events (`GET /api/jobs/{id}/events`); each run-service instance checks its own database every 250 ms with one query for all the jobs its browsers are waiting on.

**Events are delivered at least once and out of order**, so every consumer is idempotent: run-service's state changes only move a job forward (a late `Started` after `Completed` changes nothing), and repo-service records a test run only for a job id it is waiting for, deleting that entry in the same transaction.

**Repo test runs cross two services.** repo-service saves a `pending_runs` row under a job id it chooses, then submits the bundle to run-service's internal endpoint. When the `Completed` event arrives, repo-service parses the test output and records the run. The browser follows the job on run-service, then fetches the scored result from repo-service (retrying briefly, since the event may still be on its way).

Backpressure: at most 2 unfinished runs per user, and new runs are refused with `503` when 500 are already queued. A spike becomes waiting time instead of errors: in the load test, 200 runs submitted at the same instant all completed (see `loadtest/README.md`; measured on the earlier, single-database version).

## Quick start

Requires Docker (Desktop or Engine).

```bash
docker pull python:3.12-alpine && docker pull eclipse-temurin:21-jdk-alpine   # optional, the backend pulls them on start
docker compose up --build
```

Open http://localhost:3000. Compose runs every service plus Postgres (one database per service), LocalStack (standing in for SQS and SNS) and nginx (standing in for the load balancer). Signing in needs Google credentials (see [Google sign-in](docs/DEPLOY_AWS.md#google-sign-in); add `http://localhost:3000/api/auth/oauth2/code/google` as a redirect URI). To try it locally without them, start it with `AUTH_DEV_HEADER=true docker compose up --build`.

Repo review grading calls the Claude API. Export `ANTHROPIC_API_KEY` before `docker compose up` to enable it; without a key everything else works: reviews return `503` and users can skip straight to fixing.

On startup two one-off seed containers (the exercise and repo images with the `seed` profile) load `exercise-service/src/main/resources/exercises/*.yml` and `repo-service/src/main/resources/repos/*.yml` into their services' databases, then exit. Those services start only after their seed succeeds, so a broken exercise file stops the deploy. Whether the content is honest (every `predict_output` answer matches real output; hidden tests fail on the original code and pass on the solution) is checked in CI by the `content-tests` module, against the real sandbox.

### Frontend development

With the stack running in Compose, the Vite dev server gives hot reload against the real services:

```bash
cd frontend && npm install && npm run dev  # http://localhost:5173, proxies /api to the Compose nginx on :3000
```

After changing a service, rebuild just that one: `docker compose up -d --build exercise`.

## Deploying

AWS: the services on ECS (Fargate for the web services, an EC2 Auto Scaling group for the sandbox workers), RDS Postgres, SQS and SNS, an ALB behind CloudFront, defined in CDK and deployed by GitHub Actions: see [docs/DEPLOY_AWS.md](docs/DEPLOY_AWS.md). Not deployed yet; the stack is checked with `cdk synth` in CI.

## API

Every endpoint except `/api/auth/*` requires a signed-in user. People sign in with Google; identity-service then sets a signed token (JWT) in an HTTP-only cookie that every service checks, and `POST` requests must send the `XSRF-TOKEN` cookie's value back in an `X-XSRF-TOKEN` header. Unauthenticated calls get `401`. Signing out deletes the cookie; the token itself stays valid until it expires (7 days).

For local development and the test suite, `AUTH_DEV_HEADER=true` makes the services accept an `X-User-Id: <uuid>` header instead (the frontend then generates one in localStorage). Never set it on a public server.

The service in brackets is where the load balancer sends the request.

| Method | Path | Notes |
|---|---|---|
| GET | `/api/auth/me` | [identity] `{user, devHeader, googleConfigured}`; `user` is null when not signed in |
| GET | `/api/auth/oauth2/authorization/google` | [identity] starts Google sign-in (a browser redirect, not an API call) |
| POST | `/api/auth/logout` | [identity] deletes the sign-in cookie, `204` |
| GET | `/api/auth/jwks` | [identity] the public keys other services check tokens with |


| Method | Path | Body | Notes |
|---|---|---|---|
| GET | `/api/exercises?language=&difficulty=` | | [exercise] summaries + `NEW/ATTEMPTED/SOLVED` |
| GET | `/api/exercises/{slug}` | | [exercise] code + question; `solution` only if already solved |
| POST | `/api/exercises/{slug}/attempts` | `{answer}` or `{giveUp:true}` | [exercise] solution revealed when correct, on give-up, or after 3 attempts |
| GET | `/api/points/snippets` | | [exercise] `{points}` from snippet exercises. The page adds the two totals |
| POST | `/api/runs` | `{language, code}` | [run] `200` with the result if this code ran before, else `202` with a job (`{id, status, position, result, error}`). `429` when rate limited or the user already has 2 runs in progress; `503` when the queue is full |
| GET | `/api/jobs/{id}` | | [run] the job's current state; `404` unless it is the caller's |
| GET | `/api/jobs/{id}/events` | | [run] Server-Sent Events: one `job` event per change (QUEUED with position → RUNNING → DONE/FAILED with `result`), then the stream ends. Streams end after 45 s; reconnect if still running |
| GET | `/api/languages` | | [run] configured sandbox languages |
| POST | `/api/edits` | `{language, code, selection, instruction}` | [repo] inline AI edit: returns `{replacement, note}` for the selected text. Makes only the change described; `note` explains when nothing was changed. Rate limited; needs `ANTHROPIC_API_KEY` |
| GET | `/api/repos` | | [repo] repo exercise summaries + status (`SOLVED` = all hidden tests passed) |
| GET | `/api/repos/{slug}` | | [repo] project files + task; `issues` only once revealed (graded review or skip) |
| POST | `/api/repos/{slug}/reveal` | | [repo] skip the review: reveal the issues and unlock fixing, no AI call |
| POST | `/api/repos/{slug}/reviews` | `{review}` | [repo] Claude grades the review against the planted issues; reveals them. Rate limited |
| POST | `/api/repos/{slug}/test-runs` | `{files: {path: content}}` | [repo] edited files only; compiled with the hidden tests in the sandbox. Returns run-service's job (`200` if answered from the cache, else `202`) to follow on `/api/jobs/{id}/events`; its result is the raw run output |
| GET | `/api/repos/{slug}/test-runs/{jobId}` | | [repo] the scored test run once recorded: `{tests, passed, total, allPassed, points, maxPoints}`. `404` until repo-service has received the run's result event, usually well under a second after the job is DONE |
| GET | `/api/repos/{slug}/test-runs` | | [repo] the user's last 20 test runs for this exercise, newest first, with per-test results |
| POST | `/api/repos/{slug}/questions` | `{question}` | [repo] Ask AI: general language questions only. `429` once the 5 questions for this exercise are used; needs `ANTHROPIC_API_KEY` |
| GET | `/api/points/repos` | | [repo] `{points}` from repo exercises |

Internal, not reachable through the load balancer: `POST /internal/runs` on run-service (`{id, userId, language, code}`), used by repo-service.

## Points

Points are derived from stored attempts and test runs (nothing extra is saved), so the rules can change without a migration. Each service scores its own: `exercise-service` in `grading/Points.java`, `repo-service` in `repo/RepoPoints.java`.

| | Easy | Medium | Hard |
|---|---|---|---|
| Snippet exercise | 10 | 20 | 30 |
| Repo fix | 50 | 100 | 150 |

- **Snippets:** full points for a correct first attempt, 70% on the second, 40% on the third. Nothing once the answer has been revealed (after giving up or three wrong attempts).
- **Repo fixes:** 80% is shared equally across the hidden tests the user makes pass, and the remaining 20% is a bonus for passing all of them. Tests that already pass on the untouched code (the exercise's baseline) earn nothing. repo-service measures each baseline by running the untouched code through run-service; until then, only a full pass scores. The user's best run counts.
- Reviews and Ask AI do not affect points.

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

**Stronger isolation:** install [gVisor](https://gvisor.dev/docs/user_guide/install/) and start with `SANDBOX_RUNTIME=runsc docker compose up`. Every container then runs against gVisor's user-space kernel instead of the host kernel. On AWS the worker hosts install it at boot and use it by default.

### ⚠️ Know the trade-off

The **worker** container mounts `/var/run/docker.sock`, which is effectively root on its host. On AWS that host runs nothing else: the worker has no database credentials and no network path to the database, and its IAM role can only take run requests and publish run events. Someone who escapes the sandbox and the worker gets an EC2 host that can read and write those two queues, not the users' data. gVisor stays on; Firecracker microVMs would be the next step.

## Adding repo exercises

Append to `repos/*.yml`. Each entry has `slug`, `title`, `language` (needs a `<language>-project` sandbox entry), `difficulty`, `tags`, `description` and:

- `files`: the project as `{path, content}`; this is what the user sees and edits
- `tests`: hidden sources. `RepoTests.java` (default package) runs and prints one `PASS <name>` or `FAIL <name>: <why>` line per test. Use only public constructors and methods so a refactoring fix still compiles
- `issues`: `{id, file, line, category, title, detail}`, the reference list reviews are graded against
- `solution`: replacement files that fix every tested issue

The project, edits and tests are zipped and run as one sandbox job (`sandbox.languages.java-project`). `RepoExercisesTest` (in `content-tests`) checks in CI that the hidden tests fail on the original code and pass on the solution.

## Tests

```bash
cd backend && mvn test
```

Each service is tested on its own: other services are fakes, and messages are handed to the handlers directly (a recording sender stands in for SQS and SNS), so no test needs AWS or LocalStack.

- identity-service `AuthIntegrationTest`: Google sign-in sets a signed token cookie; tampered tokens mean signed out; the JWKS has no private parts; logout needs the CSRF token
- exercise-service `ExerciseIntegrationTest`: listing, hidden answers, reveal-after-3, give-up, points; tokens signed by another key, issuer or expired are rejected; cookie-authenticated POSTs need the CSRF token. Plus `GraderTest`, `PointsTest`
- run-service `RunApiTest`: queueing, cache hits, duplicate and out-of-order events, the dead-letter queue, resending a run whose message never left, rate limits, the per-user cap, job ownership, internal runs. `JobFlowIntegrationTest`: Server-Sent Events on a real server. `RunCacheTest`
- repo-service `RepoIntegrationTest`: reviews, skip, Ask AI quota, inline edits, test runs recorded and scored from events (once each, unknown jobs ignored), baselines, run-service refusals passed on. `RepoPointsTest`
- sandbox-worker `JobWorkerTest`: the worker loop with an in-memory queue: events, busy sandbox, failures, never holding more messages than threads. `SandboxEscapeTest`: hostile programs against real Docker. Use `SANDBOX_PYTHON_IMAGE`, `SANDBOX_JAVA_IMAGE` and `SANDBOX_RUNTIME` to point it at other images or gVisor
- content-tests `SnippetAnswersTest`, `RepoExercisesTest`: every exercise in the real sandbox

The Docker-backed tests are skipped automatically when Docker isn't available.

## Load test

`loadtest/loadtest.py` simulates users running uncached code against an isolated copy of the stack, following each run's events until it finishes. It steps the user count up, or (`--spike N`) has N users submit at the same instant. See [loadtest/README.md](loadtest/README.md). On an Apple M4 laptop with 4 worker threads: about 5.5 runs per second, every run succeeding at all loads tested (overload shows up as waiting time), and a spike of 200 simultaneous runs fully absorbed in 48 s.

## Backend modules

One Maven build (`backend/pom.xml`), one runnable jar and one Docker image per service. A service never depends on another service's module, only on the shared libraries:

| Module | What it is | Docker image |
|---|---|---|
| `contracts` | Library: what services exchange (run messages and events, `JobView`, `RunResult`, the sandbox languages). Fields may be added, never renamed or removed | none |
| `messaging` | Library: send to SQS, publish to SNS, listen to a queue | none |
| `platform` | Library: JWT check, CSRF, `@CurrentUser`, rate limiting, error responses. No business logic | none |
| `identity-service` | Google sign-in, users, signs tokens | `--target identity` |
| `exercise-service` | Snippet exercises; seeds its own content (`seed` profile) | `--target exercise` |
| `repo-service` | Repo exercises, Claude features, records and scores test runs; seeds its own content | `--target repo` |
| `run-service` | Accepts runs, result cache, live updates. Runs no code | `--target run` |
| `sandbox-worker` | Runs code in sandbox containers. No database, web server, sign-in or Claude code | `--target worker` (the only one with the docker CLI) |
| `content-tests` | Tests only: every exercise in the real sandbox. Never deployed | none |

## Configuration

| Env var | Default |
|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | H2 file DB, per service |
| `JWKS_URI` | `http://localhost:8081/api/auth/jwks`: where the services fetch identity-service's public keys |
| `JWT_PRIVATE_KEY` | identity-service only. Unset: a temporary key, so everyone is signed out on restart (local development) |
| `RUN_SERVICE_URL` | repo-service only. `http://localhost:8084` |
| `AWS_REGION` / `AWS_ENDPOINT` | `us-east-1` / unset. `AWS_ENDPOINT` points the SQS and SNS clients at LocalStack |
| `RUN_REQUESTS_QUEUE_URL`, `RUN_REQUESTS_DLQ_URL`, `RUN_EVENTS_TOPIC_ARN`, `RUN_EVENTS_QUEUE_URL` | the queues and topic, per service (see `docker-compose.yml`) |
| `SANDBOX_RUNTIME` | Docker default (`runc`); set `runsc` for gVisor |
| `SANDBOX_PYTHON_IMAGE` / `SANDBOX_JAVA_IMAGE` | `python:3.12-alpine` / `eclipse-temurin:21-jdk-alpine` |
| `SANDBOX_MAX_CONCURRENT` | `4`; also the default for `WORKER_THREADS` |
| `WORKER_THREADS` | runs a worker executes at once |
| `QUEUE_MAX` | `500` queued runs, then new runs get `503` |
| `QUEUE_MAX_PER_USER` | `2` unfinished runs per user, then `429` |
| `RUNS_PER_MINUTE` | `20` per user, counted separately by run-service (runs) and repo-service (AI calls), and per instance (see `platform/RateLimiter`) |
| `WARM_ON_STARTUP` | `true`: the worker pulls the sandbox images at startup |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | unset: nobody can sign in. See [Google sign-in](docs/DEPLOY_AWS.md#google-sign-in) |
| `AUTH_DEV_HEADER` | `false`. `true` accepts an `X-User-Id` header instead of sign-in: local development only |
| `ANTHROPIC_API_KEY` | unset (review grading, Ask AI and inline AI edits disabled) |
| `REVIEW_MODEL` | `claude-opus-5-5` |
| `ASSISTANT_MODEL` | `claude-opus-5-5` (Ask AI and inline AI edits) |

Limits (memory, pids, timeouts, output cap) live in `contracts/src/main/resources/sandbox.yml` under `sandbox:`.

## Roadmap

1. **Scaling execution**: done (SQS queue, workers on their own hosts scaling on queue depth, Server-Sent Events). Next: deploy it; measure the end-to-end latency the extra hops add.
2. **Hardening**: Firecracker microVMs instead of containers, mutual TLS between services instead of trusting the private network for `/internal`, tracing across services (OpenTelemetry).
3. **Auth**: done (Google sign-in, stateless JWTs). Remaining: a shared rate limiter (Redis) so limits hold across instances, and token revocation (short-lived tokens plus refresh).
4. **Learning features**: spaced repetition, streaks, topic progress, "what-if" exercises that require editing code.
5. **AI layer**: LLM grading of "explain this function" answers, generated hints.
