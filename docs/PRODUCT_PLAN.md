# Read the Code: product plan

Status: draft, 2026-11-17
Assumptions: self-serve product for individual developers, built by 1-3 people, lean infrastructure, first paying users in about 3 months.

## 1. The bet

Developers spend more time reading code than writing it, and with AI writing a growing share of it, **reading and reviewing** is becoming the core skill. Practice tools (LeetCode, Exercism, Codewars) train writing. Almost nothing trains reading: predicting behaviour, spotting bugs, reviewing a pull request.

**Read the Code** is a place to practise exactly that, with instant, honest feedback:
- every expected answer is verified by real execution
- every review is graded against known planted issues, not by vibes
- every fix is checked by hidden tests

If we are right, users will come back because they feel themselves getting faster and sharper at reviews and in interviews, not because of streak mechanics.

## 2. Who it is for

| Persona | Job to be done | What they need from us |
|---|---|---|
| **Interview prepper** (primary) | "I have code-review / debugging rounds coming up" | Realistic exercises, a sense of progress, difficulty that ramps |
| **Junior developer** | "I freeze when reviewing a senior's PR" | Explanations that teach *why*, gentle ramp, language-specific traps |
| **Mid-level developer** | "I want to catch what AI-generated code gets wrong" | Hard, realistic repos: concurrency, security, data integrity |

Out of scope for now: hiring assessments, team or L&D accounts, classrooms. They need anti-cheat, reporting and sales, which a tiny team can't support yet. The product should not paint itself into a corner for them (see section 8).

## 3. Where we are today

What exists and works (verified locally):

- **30 snippet exercises** (16 Java, 14 Python): predict output, trace a variable, spot the bug. Answers verified by execution at startup.
- **2 repo-review exercises** (Java): multi-file projects with 7 and 10 planted issues. The user writes a free-text review, Claude grades it, the issues are revealed, the user fixes the code, and hidden tests (8 and 11) confirm the fix. Startup self-check: tests fail on the original code and pass on the reference solution.
- **Hardened sandbox**: one throwaway Docker container per run (no network, read-only filesystem, non-root, CPU/memory/PID/time/output limits), results cached by hash.
- **Stack**: React + Monaco, a Spring Boot monolith, Postgres, Docker Compose.

Gaps that block a public launch:

| Gap | Why it matters |
|---|---|
| No accounts (anonymous UUID in localStorage) | Progress is lost when the browser is cleared. Can't charge anyone |
| Not under version control, no CI | Can't ship safely or collaborate |
| Review grading untested against the live API | The flagship feature has never run end to end |
| Fix mode is locked behind AI grading | Without an API key, or once a free quota runs out, users get stuck |
| `TIMEOUT` results are cached forever | A slow first run (image pull) poisons that code's result permanently |
| One failing existing test (`correctAnswerRevealsSolutionAndMarksSolved`) | Test and grader disagree about inner whitespace |
| Content volume (32 exercises, 2 repos) | Too little for anyone to stay a second week |
| No hosting | Nobody can use it |

## 4. Product principles

1. **Commit before you see.** The user always predicts or reviews *before* anything is revealed. This is the learning mechanic, so we don't dilute it.
2. **Ground truth over AI opinion.** Execution and hidden tests decide correctness wherever possible. The AI grader judges only what tests can't (did the review *explain* the problem?), and always against a fixed reference list.
3. **Explain, don't just score.** Every exercise ends with *why*. A score without a lesson isn't worth practising for.
4. **Cheap to run.** Every feature has a known cost per user. AI calls are metered and cached.
5. **Content is the product.** Features help, but people pay for good exercises.

## 5. MVP for launch (about 13 weeks)

**In:**
- Accounts (GitHub sign-in), with anonymous progress migrated on first sign-in
- Around 80 snippet exercises (Java + Python) and around 10 repo reviews (6 Java, 4 Python)
- Learning tracks: ordered sets by topic and difficulty (e.g. "Java concurrency traps", "Python mutability")
- Profile page: solved counts, review accuracy over time, weak categories
- Free and Pro tiers with Stripe checkout
- A production deployment with gVisor, backups and monitoring
- Landing page and public sample exercises (no sign-in needed)

**Out (explicitly):** more languages (beyond Java and Python), social features and leaderboards, user-submitted exercises, team accounts, mobile app, reviewing users' own repositories.

## 6. Roadmap

Each phase has an exit check. We don't start the next phase until it passes.

### Phase 0: Foundations (weeks 1-2)

| Work | Exit check |
|---|---|
| `git init`, push to GitHub, CI running `mvn test` + `npm run build` on every PR | CI is green on `main` |
| Fix the `TIMEOUT`-caching bug (don't cache `TIMEOUT`, or only start accepting runs after the image pull finishes) | Regression test: a run that times out is retried, not served from cache |
| Fix or correct the failing whitespace test (decide the intended grading rule first) | `mvn test` fully green |
| Run review grading against the real API on 10 hand-written reviews (good, partial, wrong, prompt-injection attempt) | Grades match our own judgement on at least 9 of 10; injection is ignored |
| "Skip review" path: reveal the issues and unlock fix mode without AI grading | A user without a grading quota can still finish a repo exercise |

### Phase 1: Accounts and content engine (weeks 3-6)

| Work | Exit check |
|---|---|
| GitHub OAuth (Spring Security), `users` table, link existing `user_id` UUIDs on first sign-in | Progress survives sign-out, a new device and cleared storage |
| Content authoring tooling: `mvn -Pverify-content`, which runs every exercise and repo through the real sandbox (this already exists as tests; turn it into a one-command author check) | Adding an exercise is "write YAML, run one command" |
| **Content sprint**: draft exercises with Claude's help, then verify every one by execution and human review. Target 80 snippets and 6 repos | Every exercise passes verification, and each repo has at least 6 issues and at least 6 hidden tests |
| Python repo exercises (`python-project` sandbox language, same zip-and-run approach as Java) | 2 Python repos verified |
| Review-grading eval set: about 40 reviews with human-agreed verdicts, scored automatically | Grader agreement measured and logged per prompt/model change |

The content sprint is the critical path. With the verification checks in place, one person producing about 3 good repos a week is realistic. Snippets are faster.

### Phase 2: Habit and progress (weeks 7-10)

| Work | Exit check |
|---|---|
| Tracks: ordered exercise sets with a "next up" button | Each track is 8-15 exercises |
| Profile: accuracy by issue category (security, concurrency…), review score trend | User can name their weakest category |
| Re-review: submit an improved review after seeing feedback (counts against quota) | |
| "Disagree with grade" button, stored for eval-set growth | Disagreements are reviewed weekly |
| Daily exercise (one per day, same for everyone) and a shareable result card | |
| Product analytics events (section 9) | Funnel is visible in a dashboard |

### Phase 3: Launch and revenue (weeks 11-13)

| Work | Exit check |
|---|---|
| Free/Pro gating + Stripe Checkout + customer portal | Test-mode purchase upgrades the account, cancellation downgrades it |
| Production hosting (section 8), backups, uptime alerts, error tracking | Restore-from-backup rehearsed once |
| Landing page with 3 playable sample exercises, no sign-in | |
| Launch: Show HN, r/learnprogramming, r/cscareerquestions, dev.to post on "why you should practise reading code" | 500 sign-ups in the first 2 weeks (target, to be revisited) |

### Later (only if the metrics say so)

More languages (TypeScript and Go first), "review an AI-written PR" exercises, timed interview-simulation mode, team accounts, a separate executor service (section 8).

## 7. Pricing and unit economics

| | Free | Pro (about $9/month or $79/year) |
|---|---|---|
| Snippet exercises | All | All |
| Repo reviews | 3 per month with AI grading; "skip review" always available | Unlimited AI grading (fair-use cap, e.g. 200/month) |
| Tracks, profile insights | Basic | Full: category breakdown and trends |

**Cost per AI-graded review (estimate, to be measured in Phase 0):**
- Input is about 4-6k tokens: the project with line numbers, the issue list and the review.
- Output is about 2-4k tokens, including thinking.
- On `claude-opus-5-5` ($4 / $20 per million tokens) that's roughly **$0.02 input + $0.04-0.08 output, so $0.06-0.10 per review**.
- The project and issue list are identical for every review of the same exercise. Moving them into a cached prefix cuts most of the input cost.
- A heavy Pro user doing 50 reviews a month costs about $3-5, which works at $9.
- If the eval set shows a cheaper model grades just as well, that's a pricing decision we can make with data, not a guess.

**Sandbox cost:** effectively the VM. Snippet runs are cached, and repo test runs take about 1-2 seconds each.

**Abuse controls:** the per-user rate limit (already in place) plus a monthly grading quota stored in the database, and a hard cap on the global daily API spend with an alert.

## 8. Architecture plan

**Stay a monolith.** One Spring Boot app is the right size for this team and this load.

Changes needed for production:

| Area | Change | When |
|---|---|---|
| Auth | Spring Security + GitHub OAuth, session cookie; drop the trust-the-header `X-User-Id` | Phase 1 |
| Quotas | Monthly grading usage counted in Postgres (works across restarts and instances) | Phase 3 |
| Rate limiter | Move from in-memory to Postgres or Redis **only** when running more than one instance | When needed |
| Sandbox security | Run containers under gVisor (`SANDBOX_RUNTIME=runsc`), which the code already supports | Before launch |
| Grading | Prompt caching for the per-exercise prefix; log tokens and cost per review | Phase 0-1 |
| Hosting | A single VM (e.g. 4 vCPU / 8 GB) running Docker Compose; managed Postgres or a nightly `pg_dump` to object storage. Most PaaS platforms (Cloud Run, Vercel, Heroku) don't expose a Docker daemon, which the sandbox needs | Phase 3 |
| Observability | Structured logs, Sentry (or similar), uptime checks, a cost dashboard for API spend | Phase 3 |

**When to extract a separate executor service** (the README's "phase 2"): only when one of these is true:
- sandbox runs are making API latency worse for non-run requests
- a single VM can't handle peak concurrent runs
- we want to run untrusted code on a different machine from the database for security reasons, which may happen before launch if we get any security scare

When that happens: put runs on a queue (Postgres `SKIP LOCKED` first, Kafka only at real scale), with workers on separate VMs and results pushed back over SSE.

Not ruled out for later: team accounts and hiring assessments need organisation-level user records and an audit log. Keep `user_id` as the owner of every row so these can be added later.

## 9. How we measure it

| Metric | Definition | Early target |
|---|---|---|
| Activation | New user completes 1 exercise in the first session | 60% |
| Repo engagement | Activated users who submit at least 1 repo review | 30% |
| D7 retention | Returns and completes an exercise 7 days after sign-up | 20% |
| Free → Pro | Paid conversion within 30 days | 2-4% |
| Grader agreement | AI verdicts matching human verdicts on the eval set | ≥ 90% |
| Grade disputes | "Disagree" clicks per 100 graded reviews | < 5 |
| Cost per review | Measured API cost | < $0.10 |

## 10. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| **Content can't keep up**: users finish everything in a week | High | Content sprint is the Phase 1 critical path; authoring check makes each exercise cheap to verify; tracks reuse exercises; track content per week as a metric |
| **Users don't trust the AI grades** | Medium | Reference issue list shown after grading, "disagree" button, eval set, hidden tests decide fix correctness |
| **"Why practise when an AI can review for me?"** | Medium | Positioning: interviews, and catching what AI misses; the "review an AI-written PR" exercise type |
| **Sandbox escape** | Low, but severe | gVisor, no network, regular `SandboxEscapeTest` runs in CI, sandbox VM separate from the DB once revenue allows |
| **API cost blow-up / abuse** | Medium | Quotas, per-user rate limit, global daily spend cap with alert |
| **Hidden tests can be printed by user code** | Certain | Acceptable for self-practice; matters only for a future hiring product, where grading would run separately |

## 11. Open decisions

1. **Pricing:** $9/month and the free quota of 3 reviews/month are placeholders. Validate with 10-20 user interviews in Phase 2.
2. **Grading model:** stay on `claude-opus-5-5` or test a cheaper model against the eval set? Decide on measured agreement and cost, after Phase 1.
3. **Second language for repo reviews:** Python (planned) vs TypeScript (bigger web audience).
4. **Name and domain:** "Read the Code" is a working title.
5. **Open-source the engine?** It could attract contributors for content, but makes copying easier.
