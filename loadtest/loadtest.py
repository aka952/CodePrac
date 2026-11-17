#!/usr/bin/env python3
"""Load test for the code sandbox: how many users running code can one server take?

Simulated users each have their own id. A user waits a few seconds ("think time"), runs code that has never been
seen before (so the result cache cannot help), and repeats. The number of users steps up stage by stage until the
sandbox saturates. No dependencies: Python 3.9+ standard library only.

    python3 loadtest/loadtest.py                              # default ramp against http://localhost:3100
    python3 loadtest/loadtest.py --stages 10,20,40 --seconds 20
    python3 loadtest/loadtest.py --stages 20 --hogs 4         # 20 normal users plus 4 users running infinite loops

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


def call(base, user, path, body=None, timeout=60):
    """Returns (status, seconds). Status 0 means the request itself failed (connection error or client timeout)."""
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method="GET" if body is None else "POST",
                                 headers={"X-User-Id": user, "Content-Type": "application/json"})
    started = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            payload = json.load(res)
            status = res.status
    except urllib.error.HTTPError as e:
        payload, status = None, e.code
    except Exception:
        payload, status = None, 0
    return status, time.monotonic() - started, payload


def run_code(base, user, kind, repo_file):
    nonce = random.getrandbits(48)
    if kind == "python":
        return call(base, user, "/runs", {"language": "python", "code": f"print({nonce} % 97)"})
    if kind == "java":
        code = f"public class Main {{ public static void main(String[] a) {{ System.out.println({nonce}L % 97); }} }}"
        return call(base, user, "/runs", {"language": "java", "code": code})
    path, content = repo_file
    return call(base, user, f"/repos/{REPO}/test-runs", {"files": {path: f"{content}// {nonce}\n"}})


def user_loop(base, think, stop_at, results, repo_file, hog):
    user = str(uuid.uuid4())
    time.sleep(random.uniform(0, think[1]))  # spread the users out instead of starting them all at once
    while time.monotonic() < stop_at:
        if hog:
            status, seconds, _ = call(base, user, "/runs", {"language": "python", "code": f"# {random.getrandbits(48)}\nwhile True: pass"})
            kind = "hog"
        else:
            kind = random.choice(WORKLOADS)
            status, seconds, _ = run_code(base, user, kind, repo_file)
        results.append((kind, status, seconds))
        time.sleep(random.uniform(*think))


def percentile(values, p):
    if not values:
        return 0.0
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(len(ordered) * p))]


def stage(base, users, hogs, seconds, think, repo_file):
    results = []
    stop_at = time.monotonic() + seconds
    threads = [threading.Thread(target=user_loop, args=(base, think, stop_at, results, repo_file, i < hogs), daemon=True)
               for i in range(hogs + users)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(seconds + 90)

    normal = [r for r in results if r[0] != "hog"]
    ok = [s for _, status, s in normal if status == 200]
    count = lambda code: sum(1 for _, status, _ in normal if status == code)
    other = len(normal) - len(ok) - count(503) - count(429)
    return {
        "users": users, "hogs": hogs, "requests": len(normal), "per_sec": len(ok) / seconds,
        "ok_pct": 100 * len(ok) / len(normal) if normal else 0.0,
        "busy_503": count(503), "limited_429": count(429), "other": other,
        "p50": statistics.median(ok) if ok else 0.0, "p95": percentile(ok, 0.95), "max": max(ok, default=0.0),
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", default="http://localhost:3100/api", help="API base URL")
    ap.add_argument("--stages", default="5,10,20,40,60,80", help="comma-separated numbers of simultaneous users")
    ap.add_argument("--seconds", type=int, default=30, help="length of each stage")
    ap.add_argument("--think", default="4,8", help="min,max seconds a user waits between runs (the app allows 20 runs a minute per user)")
    ap.add_argument("--hogs", type=int, default=0, help="extra users who only run infinite loops")
    args = ap.parse_args()
    think = tuple(float(x) for x in args.think.split(","))

    status, _, repo = call(args.base, str(uuid.uuid4()), f"/repos/{REPO}")
    if status != 200:
        raise SystemExit(f"Cannot reach {args.base} (status {status}). Is the load-test stack running?")
    cart = next(f for f in repo["files"] if f["path"].endswith("Cart.java"))
    repo_file = (cart["path"], cart["content"])

    average_think = sum(think) / 2
    print(f"target {args.base} | each stage {args.seconds}s | think time {think[0]:g}-{think[1]:g}s | mix 50% python, 30% java, 20% repo tests")
    print(f"{'users':>5} {'hogs':>4} {'runs':>5} {'ok/s':>6} {'ok %':>6} {'503':>5} {'429':>4} {'other':>5} {'p50 s':>6} {'p95 s':>6} {'max s':>6}")
    for users in (int(x) for x in args.stages.split(",")):
        r = stage(args.base, users, args.hogs, args.seconds, think, repo_file)
        print(f"{r['users']:>5} {r['hogs']:>4} {r['requests']:>5} {r['per_sec']:>6.1f} {r['ok_pct']:>6.1f} {r['busy_503']:>5} "
              f"{r['limited_429']:>4} {r['other']:>5} {r['p50']:>6.2f} {r['p95']:>6.2f} {r['max']:>6.2f}", flush=True)
        time.sleep(5)  # let the sandbox drain between stages
    print(f"\nEach user starts a run about every {average_think:g}s plus the time the run takes.")
    print("503 = all sandbox slots stayed busy for 3s. 429 = a user hit the per-minute rate limit. other = errors or timeouts.")


if __name__ == "__main__":
    main()
