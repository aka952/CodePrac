# Load test

Measures how many users running code one server can take, and how it handles a spike. The sandbox is the bottleneck, so the test is about code runs: Python snippets, Java snippets and repo test runs, all with never-seen-before code so the result cache cannot help. Each simulated user submits a run and follows its Server-Sent Events until the result arrives, as the browser does; response times include time spent waiting in the queue.

## Run it

Start a separate copy of the stack so test data stays out of your normal database (it listens on port 3100):

```bash
docker compose -p codecomp-loadtest -f docker-compose.yml -f loadtest/docker-compose.loadtest.yml up -d --build
docker compose -p codecomp-loadtest -f docker-compose.yml -f loadtest/docker-compose.loadtest.yml logs worker | grep verified    # wait for 5 lines
```

Run the test (Python 3.9+, no packages needed):

```bash
python3 loadtest/loadtest.py                        # ramp: 5, 10, 20, 40, 60, 80 users, 30 seconds each
python3 loadtest/loadtest.py --stages 20 --hogs 4   # 20 normal users while 4 others run infinite loops
python3 loadtest/loadtest.py --spike 200            # 200 users submit one run each at the same instant
python3 loadtest/loadtest.py --help
```

Remove the copy afterwards, including its database:

```bash
docker compose -p codecomp-loadtest -f docker-compose.yml -f loadtest/docker-compose.loadtest.yml down -v
```

## Reading the output

| Column | Meaning |
|---|---|
| `users` | simulated users in this stage, each waiting 4-8 seconds between runs |
| `ok/s` | successful runs per second |
| `ok %` | share of runs that succeeded |
| `503` | runs refused because the queue was full |
| `429` | runs refused by the per-user rate limit (20 a minute) or because the user already had 2 runs unfinished |
| `other` | runs that failed or did not finish within 5 minutes |
| `p50 s` / `p95 s` | median and 95th-percentile time from submitting to the result, including queueing |

With the queue, overload shows up as growing `p95`, not as refusals. The server is past its comfortable limit where `p95` climbs past a few seconds.

## Results (Apple M4 laptop, 4 worker threads, 2026-11-17)

| Users | Runs/s | Succeeded | Median | 95th pct | Before the queue (succeeded, 95th pct) |
|---|---|---|---|---|---|
| 20 | 2.9 | 100% | 0.78 s | 1.55 s | 100%, 1.04 s |
| 40 | 4.8 | 100% | 2.60 s | 3.80 s | 100%, 3.12 s |
| 60 | 5.3 | 100% | 7.37 s | 8.71 s | 78.5% (44 refused), 4.17 s |
| 80 | 5.7 | 100% | 11.59 s | 14.30 s | 59.6% (107 refused), 4.38 s |

- **Spike of 200 runs at the same instant:** all 200 succeeded; the last finished after 48 s (median 26 s). Before, about 16 could be absorbed and the rest were refused after 3 seconds.
- **20 users plus 8 running infinite loops:** 100% succeeded (before: 77.5%), median 5 s.
- Throughput is unchanged at about 5.5 runs per second: the queue does not add capacity, it turns rejections into waiting. More capacity means more worker threads on a bigger machine, or more worker machines.

The test users run code far more often than real ones (every 4-8 seconds, against perhaps every 30-60), so one test user stands for several real ones. Convert through `ok/s`: the sustainable runs per second multiplied by how often a real user runs code.

To test a different capacity setting, set `SANDBOX_MAX_CONCURRENT` when starting the stack.
