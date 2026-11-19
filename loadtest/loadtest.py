#!/usr/bin/env python3
"""Load test for code runs: how many users running code can one server take, and how does it handle a spike?

Simulated users each have their own id. A user submits code that has never been seen before (so the result cache
cannot help), follows the run's Server-Sent Events until it finishes, waits a few seconds ("think time") and repeats.
The number of users steps up stage by stage. Response times are measured from submitting to the result arriving,
including any time spent waiting in the queue. No dependencies: Python 3.9+ standard library only.

    python3 loadtest/loadtest.py                              # default ramp against http://localhost:3100
    python3 loadtest/loadtest.py --stages 10,20,40 --seconds 20
    python3 loadtest/loadtest.py --stages 20 --hogs 4         # 20 normal users plus 4 users running infinite loops
    python3 loadtest/loadtest.py --spike 200                  # 200 users submit at the same instant, once

Run it against the isolated stack described in loadtest/README.md, not a database you care about: every run is stored.
"""
import argparse
import json
import random
import statistics
import threading
import time
import urllib.error
import urllib.request
import uuid

REPO = "java-checkout-service"
WORKLOADS = ["python"] * 5 + ["java"] * 3 + ["repo"] * 2  # 50% Python snippet, 30% Java snippet, 20% repo test run
WAIT_LIMIT = 300  # seconds to wait for one run, queueing included


def post(base, user, path, body):
    """Returns (http_status, json_body). Status 0 means the request itself failed."""
    req = urllib.request.Request(base + path, data=json.dumps(body).encode(), method="POST",
                                 headers={"X-User-Id": user, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as res:
            return res.status, json.load(res)
    except urllib.error.HTTPError as e:
        return e.code, None
    except Exception:
        return 0, None


def follow(base, user, job_id, deadline):
    """Reads the job's event stream until it is DONE or FAILED, reconnecting if the stream ends early."""
    while time.monotonic() < deadline:
        req = urllib.request.Request(f"{base}/jobs/{job_id}/events", headers={"X-User-Id": user, "Accept": "text/event-stream"})
        try:
            with urllib.request.urlopen(req, timeout=60) as res:
                for raw in res:
                    line = raw.decode().strip()
                    if line.startswith("data:"):
                        status = json.loads(line[5:])["status"]
                        if status in ("DONE", "FAILED"):
                            return status
        except Exception:
            time.sleep(0.5)
    return "TIMEOUT"


def submit(base, user, kind, repo_file, hog=False):
    """Submits one run and waits for its result. Returns (outcome, seconds from submit to result)."""
    nonce = random.getrandbits(48)
    if hog:
        path, body = "/runs", {"language": "python", "code": f"# {nonce}\nwhile True: pass"}
    elif kind == "python":
        path, body = "/runs", {"language": "python", "code": f"print({nonce} % 97)"}
    elif kind == "java":
        code = f"public class Main {{ public static void main(String[] a) {{ System.out.println({nonce}L % 97); }} }}"
        path, body = "/runs", {"language": "java", "code": code}
    else:
        path, content = repo_file
        path, body = f"/repos/{REPO}/test-runs", {"files": {path: f"{content}// {nonce}\n"}}

    started = time.monotonic()
    status, job = post(base, user, path, body)
    if status == 200:
        outcome = "ok"
    elif status == 202:
        final = follow(base, user, job["id"], started + WAIT_LIMIT)
        outcome = {"DONE": "ok", "FAILED": "failed"}.get(final, "timeout")
    else:
        outcome = {503: "503", 429: "429"}.get(status, "error")
    return outcome, time.monotonic() - started


def user_loop(base, think, stop_at, results, repo_file, hog):
    user = str(uuid.uuid4())
    time.sleep(random.uniform(0, think[1]))  # spread the users out instead of starting them all at once
    while time.monotonic() < stop_at:
        kind = "hog" if hog else random.choice(WORKLOADS)
        outcome, seconds = submit(base, user, kind, repo_file, hog)
        results.append((kind, outcome, seconds))
        time.sleep(random.uniform(*think))


def percentile(values, p):
    if not values:
        return 0.0
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(len(ordered) * p))]


def summarize(results, users, hogs, seconds):
    normal = [r for r in results if r[0] != "hog"]
    ok = [s for _, outcome, s in normal if outcome == "ok"]
    count = lambda name: sum(1 for _, outcome, _ in normal if outcome == name)
    return {
        "users": users, "hogs": hogs, "requests": len(normal), "per_sec": len(ok) / seconds if seconds else 0.0,
        "ok_pct": 100 * len(ok) / len(normal) if normal else 0.0,
        "r503": count("503"), "r429": count("429"), "other": count("failed") + count("timeout") + count("error"),
        "p50": statistics.median(ok) if ok else 0.0, "p95": percentile(ok, 0.95), "max": max(ok, default=0.0),
    }


def stage(base, users, hogs, seconds, think, repo_file):
    results = []
    stop_at = time.monotonic() + seconds
    threads = [threading.Thread(target=user_loop, args=(base, think, stop_at, results, repo_file, i < hogs), daemon=True)
               for i in range(hogs + users)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(seconds + WAIT_LIMIT)
    return summarize(results, users, hogs, seconds)


def spike(base, users, repo_file):
    """Every user submits one run at the same instant."""
    results = []
    go = threading.Event()

    def one():
        user = str(uuid.uuid4())
        kind = random.choice(WORKLOADS)
        go.wait()
        results.append((kind, *submit(base, user, kind, repo_file)))

    threads = [threading.Thread(target=one, daemon=True) for _ in range(users)]
    for t in threads:
        t.start()
    time.sleep(0.5)
    started = time.monotonic()
    go.set()
    for t in threads:
        t.join(WAIT_LIMIT + 30)
    took = time.monotonic() - started
    return summarize(results, users, 0, took), took


HEADER = f"{'users':>5} {'hogs':>4} {'runs':>5} {'ok/s':>6} {'ok %':>6} {'503':>5} {'429':>4} {'other':>5} {'p50 s':>6} {'p95 s':>6} {'max s':>6}"


def row(r):
    return (f"{r['users']:>5} {r['hogs']:>4} {r['requests']:>5} {r['per_sec']:>6.1f} {r['ok_pct']:>6.1f} {r['r503']:>5} "
            f"{r['r429']:>4} {r['other']:>5} {r['p50']:>6.2f} {r['p95']:>6.2f} {r['max']:>6.2f}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", default="http://localhost:3100/api", help="API base URL")
    ap.add_argument("--stages", default="5,10,20,40,60,80", help="comma-separated numbers of simultaneous users")
    ap.add_argument("--seconds", type=int, default=30, help="length of each stage")
    ap.add_argument("--think", default="4,8", help="min,max seconds a user waits between runs (the app allows 20 runs a minute per user)")
    ap.add_argument("--hogs", type=int, default=0, help="extra users who only run infinite loops")
    ap.add_argument("--spike", type=int, default=0, help="instead of a ramp: this many users submit one run each at the same instant")
    args = ap.parse_args()
    think = tuple(float(x) for x in args.think.split(","))

    req = urllib.request.Request(f"{args.base}/repos/{REPO}", headers={"X-User-Id": str(uuid.uuid4())})
    try:
        with urllib.request.urlopen(req, timeout=10) as res:
            repo = json.load(res)
    except Exception as e:
        raise SystemExit(f"Cannot reach {args.base}: {e}. Is the load-test stack running?")
    cart = next(f for f in repo["files"] if f["path"].endswith("Cart.java"))
    repo_file = (cart["path"], cart["content"])

    if args.spike:
        print(f"target {args.base} | spike: {args.spike} users submit one run each at the same instant")
        r, took = spike(args.base, args.spike, repo_file)
        print(HEADER)
        print(row(r))
        print(f"\nAll {args.spike} runs settled in {took:.1f}s. Times include waiting in the queue.")
    else:
        print(f"target {args.base} | each stage {args.seconds}s | think time {think[0]:g}-{think[1]:g}s | mix 50% python, 30% java, 20% repo tests")
        print(HEADER)
        for users in (int(x) for x in args.stages.split(",")):
            print(row(stage(args.base, users, args.hogs, args.seconds, think, repo_file)), flush=True)
            time.sleep(5)  # let the queue drain between stages
        print(f"\nEach user starts a run about every {sum(think) / 2:g}s plus the time the run takes. Times include waiting in the queue.")
    print("503 = refused because the queue was full. 429 = rate limit or too many runs in progress for one user. "
          "other = failed or timed-out runs.")


if __name__ == "__main__":
    main()
