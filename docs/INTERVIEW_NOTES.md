# Interview notes: Read the Code

Study notes for explaining this project. Each section is a question I asked or a topic I worked through, with the answer and the reasoning behind it. New questions are added at the end.

How to answer in an interview: give the reason first, then the mechanism, then the limitation.

## Contents

1. [Architecture in one page](#1-architecture-in-one-page)
2. [Is this microservices?](#2-is-this-microservices)
3. [What happens when I click "Run hidden tests"](#3-what-happens-when-i-click-run-hidden-tests)
4. [Check questions on the test-run flow](#4-check-questions-on-the-test-run-flow)
5. [Could Redis replace the in-memory semaphore?](#5-could-redis-replace-the-in-memory-semaphore)
6. [Can at most 4 containers run?](#6-can-at-most-4-containers-run)
7. [How many users can it handle now?](#7-how-many-users-can-it-handle-now)
8. [Load test results](#8-load-test-results)
9. [What is a slug?](#9-what-is-a-slug)

---

## 1. Architecture in one page

```
Browser
  │
  ▼
frontend (nginx)            React + TypeScript + Monaco editor, built by Vite
  │  /api/*  → proxy
  ▼
backend (Spring Boot 3.5, Java 21, virtual threads)
  ├── web/        ApiController: REST endpoints, error mapping
  ├── exercise/   snippet exercises: seeding from YAML, attempts
  ├── repo/       repo exercises: reviews, test runs, Ask AI, inline AI edits
  ├── grading/    Grader (answer checking), Points (scoring rules)
  ├── run/        RunService (cache → rate limit → sandbox), RateLimiter, SandboxWarmer
  └── sandbox/    DockerSandbox: starts one container per run
  │                    │                       │
  ▼                    ▼                       ▼
Postgres          host Docker daemon      Claude API
(Flyway)          (throwaway containers)  (review grading, Ask AI, inline edits)
```

- **Three deployed services:** frontend, backend, database. Sandbox containers are short-lived processes, not services.
- **Database tables:** `exercises`, `attempts`, `run_cache`, `repo_exercises`, `repo_submissions`.
- **Content lives in YAML** and is loaded into the database at startup. At startup the backend also runs every exercise to check it: snippet answers must match real output, and repo hidden tests must fail on the original code and pass on the reference solution.

## 2. Is this microservices?

**No. It is a monolith with a separate frontend and database (three-tier).**

- `ExerciseService`, `RunService` and `RepoService` are packages inside one Spring Boot application. They call each other in-process, share one database and deploy as one jar.
- The frontend is static files served by nginx plus a reverse proxy.
- Sandbox containers have no API and no network. The backend starts each one, pipes code in, reads output, and the container is removed.

**Why a monolith is right here:** one small team, low load, one deployable. Splitting would add network calls, deployment work and failure modes without solving a real problem.

**Limits to name:**
- The rate limiter is in memory, so two backend instances would each count separately.
- Runs are synchronous: a request holds a thread until the container finishes.
- Capacity is 4 containers per backend.

**When to split:** when code execution needs to scale separately from the API. Then runs go onto a queue and separate worker processes execute them.

## 3. What happens when I click "Run hidden tests"

```
Browser → nginx → controller → RepoService → RunService → DockerSandbox → container
                                   │             │
                                Postgres      Postgres
                             (save the run)  (run cache)
```

| Step | Where | What happens | Why |
|---|---|---|---|
| 1 | `frontend/src/pages/RepoPage.tsx` (`runTests`) | The browser sends only the files that differ from the originals | Small request; the server already has the originals |
| 2 | `frontend/nginx.conf` | nginx forwards `/api/` to the backend | One origin, so no CORS; the backend is never exposed directly |
| 3 | `web/ApiController.java` | Reads the user id from `X-User-Id`, validates sizes, calls the service | Thin controller: rules live in services and are testable without HTTP |
| 4 | `repo/RepoService.java` (`runTests`, `bundle`) | Rejects unknown paths, merges edits over originals, adds the hidden tests, packs a zip as base64 text | The sandbox takes one piece of text on stdin. Files are sorted with a fixed timestamp so identical code gives identical text |
| 5 | `run/RunService.java` (`run`) | SHA-256 of language + code → look up `run_cache`. On a miss: rate limit, run, save | Cache hits are free and do not count against the rate limit |
| 6 | `sandbox/DockerSandbox.java` (`run`, `command`, `execute`) | Semaphore (4 at once, wait up to 3 s), then `docker run` with restrictions; code goes in on stdin; killed at the time limit | A fresh container per run means no leftover state and no effect on other users |
| 7 | `repo/RepoService.java` (`parseTests`) | Reads `PASS name` / `FAIL name: reason` lines from the output | No test framework needed: the container has no network to download one |
| 8 | `repo/RepoService.java` | Saves a `repo_submissions` row with pass count and per-test results | One row feeds solved status, the Previous runs list and points |

**Container restrictions:** no network, read-only filesystem with a small writable `/tmp`, 256 MB memory, 1 CPU, 64 processes, all Linux capabilities dropped, non-root user.

**Inside the container** (from `application.yml`, `sandbox.languages.java-project`): decode the zip, unpack it, compile every `.java` file with `javac`, run the `RepoTests` class.

**What is not cached:** timeouts and sandbox errors. See question 4 in the next section.

**Points are never stored.** They are calculated from the user's best run each time they are needed.

### Trade-offs

| Decision | Benefit | Cost |
|---|---|---|
| Synchronous run: the HTTP request waits for the container | Simple, no queue or polling | A thread is held for up to 20 s; capacity is 4 runs at a time |
| Backend controls Docker through the host's socket | No extra service | The backend effectively has root on the host |
| Hidden tests run in the same container as user code | One run, one result | User code could read the tests or print fake `PASS` lines |

## 4. Check questions on the test-run flow

**Q1. Why does the server reject a file path that is not part of the exercise?**

User files are merged with the hidden tests into one project. If any path were accepted, a user could:
- send their own `RepoTests.java` and replace the hidden tests with ones that always pass
- add extra files
- use a path like `../../x` to write outside the project folder when the zip is unpacked

Allowing only the exercise's own paths closes all three with one check.

**Q2. Two users submit identical code one second apart. How many containers start?**

Usually one. The cache key is a hash of language + code and does not include the user. If the first run has been saved, the second is a cache hit.

The race: if the first run is still going, the second also misses, so two containers start. Both try to save the same cache row; the second save fails on the primary key and the code catches and ignores that. The result is correct either way; the cost is one wasted run.

**Q3. A user's code contains an infinite loop. What happens?**

- The container starts and the loop spins, limited to one CPU.
- The backend waits for the time limit: 20 s for a repo project, 10 s for a Java snippet, 5 s for Python.
- Then it force-removes the container (`docker rm -f`) and marks the run `TIMEOUT`.
- The result is not cached.
- For a repo run, tests that printed before the loop still count, so the user sees partial results with a "timed out" label.

Cost: the run held one of the four slots for the full time limit.

**Q4. Why are timeouts not cached, when runtime errors are?**

A runtime error is a property of the code: the same code throws the same exception every time. A timeout can come from the environment, such as the sandbox image still downloading or an overloaded machine. Caching it would make that code return "timed out" forever.

This was a real bug in the project. On first start, a run timed out while the image was downloading, and the timeout was cached permanently. The fix: stop caching timeouts, and a database migration deleted the ones already cached.

Trade-off: a real infinite loop is re-run each time it is submitted. The per-user rate limit bounds that cost.

**Q5. What stops 100 simultaneous users from starting 100 containers?**

Three layers:
- **Semaphore:** 4 containers at once. A request waits up to 3 s, then gets a 503 and can retry.
- **Per-user rate limit:** 20 runs a minute.
- **Cache:** identical code does not start a container.

Limit to name: the rate limiter lives in memory in one backend process, so it does not work across several instances. See section 5.

## 5. Could Redis replace the in-memory semaphore?

**Short answer: Redis is the right tool for the rate limiter, but the semaphore is correct as it is.**

**What the semaphore protects:** how many containers run on one machine, so that machine does not run out of CPU and memory. Each backend uses the Docker daemon on its own host.

| Setup | Is a local semaphore right? |
|---|---|
| Two backends on two VMs | Yes. Each VM has its own capacity. A shared Redis limit of 4 would waste half of it |
| Two backends sharing one Docker host | No. The limit must be shared, and Redis fits |

**What does need sharing: the rate limiter.** The limit is per user, and a user's requests can reach any instance. With two instances each counting separately, a user gets double the limit. In Redis:
- a counter per user with a one-minute expiry (`INCR` + `EXPIRE`) for a fixed window, or
- a sorted set of timestamps per user for a sliding window, which is what the current code does in memory.

**If a Redis semaphore were needed,** it must be a counting semaphore (four holders), not a plain lock (one holder):

| Concern | Why it matters | Usual answer |
|---|---|---|
| Crash safety | A backend that dies holding a slot never returns it | Give each slot a lease with an expiry longer than the longest run |
| Atomic acquire | Check-and-take must be one step, or two requests take the last slot | A Lua script, or a library such as Redisson's `RSemaphore` |
| Waiting | Current code waits up to 3 s for a slot | Poll with a short sleep, or blocking list operations |

**The better design at scale: a queue with workers.** The backend puts each run on a queue. Worker processes on the sandbox machines each pull as many jobs as their machine can handle. Capacity grows by adding workers, and no distributed semaphore is needed.

**Interview answer:** "The container limit is per host, so a local semaphore is right for that. The per-user rate limit is the part that breaks with multiple instances, and I would move that to Redis. If run volume outgrew one machine, I would switch to a queue with worker processes instead of coordinating a global semaphore."

## 6. Can at most 4 containers run?

**Yes: at most 4 sandbox containers run at the same time per backend. It is a setting, not a hard limit.**

**Where it is set:** `backend/src/main/resources/application.yml`, under `sandbox:`

```yaml
max-concurrent: ${SANDBOX_MAX_CONCURRENT:4}
queue-wait-ms: 3000
```

The default is 4. The `SANDBOX_MAX_CONCURRENT` environment variable changes it without a code change. It is enforced by a fair `Semaphore` in `DockerSandbox`.

**What happens to the fifth request:**
- It waits up to 3 seconds (`queue-wait-ms`). The semaphore is fair, so requests are served in arrival order.
- If a slot frees up, it runs.
- If not, it gets a 503 "All sandbox slots are busy, retry in a moment".

**Why 4:** each container may use up to 1 CPU and 256 MB, so 4 at once means up to 4 CPUs and about 1 GB for sandboxes, on top of the backend and Postgres. That suits a small VM. Rule of thumb for a bigger machine: one slot per spare CPU core, as long as memory allows 256 MB per slot.

**Throughput:** "4 at a time" is not 4 users. Most runs finish in under a second (the repo tests take about 0.6 s), so 4 slots handle several runs per second, and cached results use no slot. The limit bites when many slow runs arrive together, such as several infinite loops each holding a slot for the full time limit.

**Details:**
- The limit is per backend process. Two instances would allow 4 each (see section 5 for why that is correct per host).
- The startup check that runs every exercise uses the same semaphore but runs one exercise at a time, so it holds one slot.

**Interview answer:** "It is capped at 4 concurrent containers by a fair semaphore, configurable by environment variable. A fifth request waits up to 3 seconds and then gets a 503. The number comes from the machine's capacity, since each container is limited to 1 CPU and 256 MB."

## 7. How many users can it handle now?

**Estimate: about 50-100 people practising at the same time on one small server, which is a few thousand registered users. This is calculated from measured run times, not load-tested.**

**How the number is derived.** The sandbox is the bottleneck.

| Step | Figure | Basis |
|---|---|---|
| Container slots | 4 | the configured limit (section 6) |
| Time per uncached run | about 1 s | measured 0.2-0.7 s on a laptop; a small cloud VM is slower |
| Sandbox throughput | about 4 runs per second | 4 slots / 1 s |
| How often an active user starts an uncached run | once every 30-60 s | assumption: people read and edit between runs |
| Users at full load | 120-240 | 4 runs/s x 30-60 s |
| Users with headroom for bursts | about 50-100 at once | planning for roughly half of full load |

If 2-5% of registered users are active at the peak, that is roughly 1,000-5,000 registered users.

**What breaks first, in order:**
1. **Slow runs holding slots.** An infinite loop keeps a slot for 5-20 s. Four at once block everyone, and other users get "sandbox busy" after a 3 s wait.
2. **Bursts.** About 16 simultaneous run requests can be absorbed (4 running plus those that get a slot within 3 s). The rest get a 503.
3. **AI features.** Review grading can take tens of seconds per call. The cap is the Anthropic account's rate limit and the budget, not the server.
4. **The `run_cache` table grows forever.** Nothing deletes old entries. A storage problem over months.
5. **Single instance.** No redundancy: a restart or deploy is downtime for everyone.

**What is not a bottleneck:** listing exercises, submitting answers and calculating points are small database queries. The backend uses virtual threads and holds database connections briefly, so those can run at hundreds of requests per second on the same machine.

**How to raise the ceiling:**

| Change | Effort | Effect |
|---|---|---|
| Bigger VM and a higher `SANDBOX_MAX_CONCURRENT` | config change | roughly linear: 16 cores gives about 4x |
| Shorter time limits or a per-user cap on concurrent runs | small | a few slow runs can no longer block everyone |
| Queue with worker machines | large | capacity grows by adding workers; runs stop holding API threads |
| Shared rate limiter (Redis) and a second backend | medium | removes the single point of failure |

**Interview answer:** "I estimate 50 to 100 concurrent active users on a small VM. The limit is the sandbox: 4 containers at about a second each gives roughly 4 runs a second. I have not load-tested it, so that is from measured run times and an assumption about how often users run code. The first failure would be slow runs holding the slots. The cheap fix is a bigger machine with more slots, and the real fix is a queue with workers."

Say that it is an estimate and what it is based on. Section 8 has the measured numbers from the load test that followed.

## 8. Load test results

**Measured on 2026-11-17: the sandbox sustains about 5 code runs per second. It is comfortable up to about 3 runs per second, and starts refusing runs above about 5.**

**Setup**
- Script: `loadtest/loadtest.py` (plain Python, no dependencies). How to run it: `loadtest/README.md`.
- Machine: Apple M4 laptop, Docker with 10 CPUs and 8 GB. One backend, 4 sandbox slots.
- A separate copy of the stack with its own database, so test data stayed out of the dev database.
- Each simulated user has its own id, waits 4-8 seconds, runs code never seen before (so the cache cannot help), and repeats.
- Mix: 50% Python snippets, 30% Java snippets, 20% repo test runs. Each stage lasted 30 seconds.

**Ramp**

| Users | Runs/s (ok) | Succeeded | Refused (503) | Median | 95th percentile |
|---|---|---|---|---|---|
| 5 | 0.9 | 100% | 0 | 0.26 s | 0.80 s |
| 10 | 1.5 | 100% | 0 | 0.34 s | 0.79 s |
| 20 | 3.0 | 100% | 0 | 0.57 s | 1.04 s |
| 40 | 5.2 | 100% | 0 | 1.62 s | 3.12 s |
| 60 | 5.4 | 78.5% | 44 | 3.31 s | 4.17 s |
| 80 | 5.3 | 59.6% | 107 | 3.48 s | 4.38 s |

**Reading it**
- **Up to 20 test users (3 runs/s): comfortable.** Everything succeeds and 95% of runs finish within about a second.
- **40 test users (5.2 runs/s): at the limit.** Nothing fails yet, but runs are queueing for a slot: the median triples and the 95th percentile passes 3 seconds.
- **60 and above: over the limit.** Throughput stays flat at about 5.3 runs/s and the extra requests are refused with a 503 after waiting 3 seconds.
- **It degrades cleanly.** There were no crashes, timeouts or other errors, and no rate-limit (429) responses. Overload shows up only as 503s, which is the designed behaviour.

**Converting to real users.** The test users run code every 6 seconds or so, far more often than real ones. Going through runs per second:

| | Runs/s | Real users, if each runs uncached code every 30-60 s |
|---|---|---|
| Comfortable | 3 | about 90-180 at the same time |
| Hard limit | 5.3 | about 160-320 at the same time |

The earlier estimate (section 7) was 50-100 with headroom on a small VM. The measured figure is somewhat higher, on a faster machine than a small cloud VM. A server with fewer or slower cores would land lower, so the test should be repeated on the real server.

**Infinite loops (the predicted first failure)**

20 normal users, plus extra users who only run infinite loops (each holds a slot for the 5-second Python time limit):

| Infinite-loop users | Normal runs succeeded | Median | 95th percentile |
|---|---|---|---|
| 0 | 100% | 0.57 s | 1.04 s |
| 4 | 100% | 0.95 s | 3.08 s |
| 8 | 77.5% | 2.73 s | 3.76 s |

Four such users triple the slow-end response time for everyone, and eight cause about a quarter of normal runs to be refused, at a load the server otherwise handles easily. This confirms that slow runs holding slots is the first real weakness. The fix would be a per-user cap on concurrent runs, so one user can hold at most one slot.

**Limits of this test**
- One machine, one 30-second pass per stage: the numbers are indicative, not precise.
- The load generator ran on the same laptop as the server, so they competed for CPU.
- Only code runs were tested. Listing, answering and the AI features were not.

**Interview answer:** "I load-tested it with a script that simulates users running uncached code. On my machine with 4 sandbox slots it sustains about 5 runs a second: clean up to 3, queueing at 5, and beyond that it sheds load with 503s rather than falling over. That is roughly 100 to 200 concurrent active users. The test also showed that a handful of users running infinite loops can degrade everyone, so the next change would be a per-user concurrency cap."

## 9. What is a slug?

**A slug is a short, readable, unique name for something, used in URLs in place of a numeric id.** Every exercise has one, such as `java-checkout-service` or `py-slicing`.

**Where it appears**
- **Exercise files:** each YAML entry starts with `slug: java-checkout-service`.
- **Page addresses:** `/repo/java-checkout-service`, `/exercise/py-slicing`.
- **API paths:** `POST /api/repos/java-checkout-service/test-runs`. In `ApiController`, `{slug}` in a path is the placeholder for it.
- **Database:** the `exercises` and `repo_exercises` tables have a `slug` column marked unique.

**Slug versus numeric id.** Each exercise also has an `id` generated by the database.

| | Slug | Numeric id |
|---|---|---|
| Example | `java-checkout-service` | `2` |
| Who chooses it | the exercise author, in YAML | the database, automatically |
| Same on every machine? | yes | no, it depends on insertion order |
| Used for | URLs and API paths | linking rows inside the database |

**Why the slug is the public identifier**
- **Readable URLs.** `/repo/java-checkout-service` says what the page is; `/repo/2` does not.
- **Stable across environments.** Id 2 on a laptop might be 5 on the server. The slug is the same everywhere, so links and tests keep working.
- **Idempotent seeding.** At startup the seeder (`ExerciseSeeder`, `RepoSeeder`) looks each exercise up by slug: update the row if it exists, insert if not. An exercise's text can be edited and the app restarted without losing anyone's progress.

Progress rows (`attempts`, `repo_submissions`) point at the numeric id, which is the conventional and efficient way to link tables.

**Rules that follow**
- **Slugs must be unique.** The seeder refuses to start if two exercises share one.
- **Do not rename a slug once people have used the exercise.** The seeder would treat the new name as a new exercise and insert a second row; the old row would remain with everyone's progress attached to it.

**Origin of the word:** newspaper publishing, where a "slug" was the short working name of a story.

**Interview answer:** "A slug is a human-readable unique key used in URLs. I use it as the public identifier because it is stable across environments and makes the seeding idempotent, while the auto-generated numeric id stays internal for foreign keys."
