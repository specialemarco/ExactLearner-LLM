#!/usr/bin/env python3
"""
Time N real queries against a running llm_server.py and project the full run.

    python3 scripts/benchmark_queries.py --n 100

Why this exists: the only throughput evidence available otherwise is the single
warmup query, and hand-written probes use the wrong prompt entirely. The Java
side does NOT ask "Every X is a Y" -- it renders a SubClassOf axiom in
Manchester syntax and NLPLLMEngine.addExtraSemantic rewrites it as

    Can <A> be considered a subcategory of '<B>'?

Prompt length and phrasing drive how long the model reasons, so a projection
built on the wrong prompt is not worth much. This script reproduces the real
one, drawing class names from the same baseSet the learner uses.

It never touches cache.sqlite3 -- that is written by the Java side, keyed by
(model, system, query). Benchmarking here costs nothing but GPU time, and does
not poison a subsequent real run.

Sanity check worth reading, beyond the timings: random ordered pairs of classes
are almost never in a subsumption relation, so the answers should be
overwhelmingly False. A high True rate means the model is not discriminating,
and every downstream precision number would be measuring that rather than the
learner.
"""

import argparse
import json
import os
import random
import re
import statistics
import sys
import time
import urllib.error
import urllib.request

# The learner's own default: 131 classes -> 131*130 ordered pairs, run before
# the PAC loop starts and independent of epsilon.
PRECOMP_QUERIES = 17_030
PAC_QUERIES_AT_EPS = {0.2: 20_298, 0.5: 8_119, 1.0: 4_060}

DEFAULT_SYSTEM = (
    "You need to classify the following statements as True or False. The "
    "statement will be provided in either Manchester OWL syntax or natural "
    "language. Strictly follow these guidelines: 1. answer with only True or "
    "False; 2. entities with has part relation are not in a subclass "
    "relation; 3. take a deep breath before answering; 4. if you are unsure "
    "about the classification, answer with False."
)


def short_name(iri: str) -> str:
    """`<http://benchmark/OWL2Bench#Professor>` -> `Professor`."""
    iri = iri.strip().strip("<>")
    for sep in ("#", "/"):
        if sep in iri:
            iri = iri.rsplit(sep, 1)[-1]
    return iri


def load_class_names(base_set_path: str) -> list:
    names = []
    with open(base_set_path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            # '#' starts a comment only at the very beginning of a line; an IRI
            # legitimately contains one, so this must not be a blanket split.
            if not line or line.startswith("#"):
                continue
            # Skip existential restrictions in the C2/C3 base sets -- the
            # precomputation phase this projects is over concept names only.
            if " some " in line or " and " in line:
                continue
            names.append(short_name(line))
    return names


def load_system_prompt(config_path: str) -> str:
    """
    Pulls the `system:` folded block out of the experiment YAML.

    PyYAML if available; otherwise a small reader for the `key: >` folded
    scalar, since a login node may have no yaml module and this is the only
    construct in the file that matters here.
    """
    try:
        import yaml
        with open(config_path, encoding="utf-8") as fh:
            value = (yaml.safe_load(fh) or {}).get("system")
        if value:
            return " ".join(value.split())
    except Exception:
        pass

    lines, collecting, out = open(config_path, encoding="utf-8").readlines(), False, []
    for line in lines:
        if re.match(r"^system:\s*[|>]", line):
            collecting = True
            continue
        if collecting:
            if line.strip() and not line[0].isspace():
                break
            out.append(line.strip())
    return " ".join(" ".join(out).split()) if out else DEFAULT_SYSTEM


def make_query(a: str, b: str) -> str:
    """Exactly what NLPLLMEngine.addExtraSemantic produces for `A SubClassOf B`."""
    return f"Can {a} be considered a subcategory of '{b}'?"


def post(url: str, payload: dict, timeout: int):
    req = urllib.request.Request(
        url, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"})
    # No proxy, ever. Educloud sets http_proxy and urllib honours it, which
    # would bounce a localhost request off Squid and return an HTML error page.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode())


def get_health(url: str, timeout: int = 10):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(f"{url}/health", timeout=timeout) as resp:
            return json.loads(resp.read().decode())
    except Exception:
        return None


def wait_until_ready(url: str, timeout: int) -> None:
    """
    Blocks until the server reports ready, or gives up with a useful message.

    /api/generate returns 503 while the model loads, so without this the first
    query dies immediately after a 4-minute model load -- and the obvious
    reading of that error ("server is down") is wrong, since the server is up
    and answering /health throughout.
    """
    deadline = time.time() + timeout
    last_phase, announced = None, False
    while time.time() < deadline:
        h = get_health(url)
        if h is None:
            print(f"  waiting: no response from {url}/health yet ...")
        elif h.get("ready"):
            if announced:
                print("  server ready.\n")
            return
        else:
            phase = h.get("phase", "?")
            if phase != last_phase:
                print(f"  server not ready yet — phase: {phase} "
                      f"(model load takes several minutes)")
                last_phase, announced = phase, True
        time.sleep(10)

    h = get_health(url) or {}
    sys.exit(f"ERROR: server still not ready after {timeout}s "
             f"(phase: {h.get('phase', 'unknown')}).\n"
             f"       Check the server log, or raise --wait-timeout.")


def fmt_hours(seconds: float) -> str:
    h = seconds / 3600
    return f"{h:,.1f} h ({h / 24:,.1f} days)" if h >= 24 else f"{h:,.1f} h"


def project(sec_per_query: float) -> None:
    print("\n--- projection --------------------------------------------------")
    print(f"  precomputation only  {PRECOMP_QUERIES:>7,} queries  "
          f"{fmt_hours(PRECOMP_QUERIES * sec_per_query)}   <- floor, "
          f"epsilon cannot reduce this")
    for eps, pac in sorted(PAC_QUERIES_AT_EPS.items()):
        total = PRECOMP_QUERIES + pac
        print(f"  epsilon {eps:<4}         {total:>7,} queries  "
              f"{fmt_hours(total * sec_per_query)}")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--n", type=int, default=100,
                    help="Number of queries to time (default 100).")
    ap.add_argument("--url", default="http://localhost:11434",
                    help="Base URL of a running llm_server.py.")
    ap.add_argument("--base-set",
                    default="data_paclo/owl2bench-1-el-class_names/baseSet",
                    help="Class names, one IRI per line.")
    ap.add_argument("--config",
                    default="src/main/java/org/configurations/experiments/"
                            "mistral-owl2bench-c1-nlp-advanced.yml",
                    help="Experiment YAML, read for the system prompt.")
    ap.add_argument("--model", default="deepseek-r1-32b")
    ap.add_argument("--seed", type=int, default=0,
                    help="Fixed so repeated runs compare like with like.")
    ap.add_argument("--timeout", type=int, default=600)
    ap.add_argument("--batch-size", type=int, default=0,
                    help="If >0, ALSO send the same queries to /api/batch in "
                         "chunks of this size, to measure what a Java-side "
                         "concurrency change would buy. vLLM backend only.")
    ap.add_argument("--out", default=None,
                    help="Write per-query records here as JSONL.")
    ap.add_argument("--wait-timeout", type=int, default=1800,
                    help="Seconds to wait for the server to finish loading "
                         "before giving up (default 1800). 0 to not wait.")
    args = ap.parse_args()

    for path in (args.base_set, args.config):
        if not os.path.exists(path):
            sys.exit(f"ERROR: no such file: {path}\n"
                     f"       (run from the repository root)")

    system = load_system_prompt(args.config)
    names = load_class_names(args.base_set)
    if len(names) < 2:
        sys.exit(f"ERROR: found {len(names)} class names in {args.base_set}")

    random.seed(args.seed)
    pairs = []
    while len(pairs) < args.n:
        a, b = random.sample(names, 2)
        pairs.append((a, b))

    print(f"Classes in base set : {len(names)}")
    print(f"System prompt       : {system[:90]}...")
    print(f"Example query       : {make_query(*pairs[0])}")
    print(f"Timing {args.n} queries against {args.url}\n")

    if args.wait_timeout > 0:
        wait_until_ready(args.url, args.wait_timeout)

    gen_url = f"{args.url}/api/generate"
    records, t_start = [], time.time()

    for i, (a, b) in enumerate(pairs, 1):
        query = make_query(a, b)
        t0 = time.time()
        try:
            r = post(gen_url, {"model": args.model, "system": system,
                               "options": {"num_predict": 2}, "stream": False,
                               "prompt": query}, args.timeout)
        except urllib.error.HTTPError as exc:
            if exc.code == 503:
                # The readiness gate. The server is up and answering /health --
                # saying "is the server up?" here would send you looking in
                # entirely the wrong place.
                h = get_health(args.url) or {}
                sys.exit(f"\nERROR: server is up but not ready (HTTP 503, "
                         f"phase: {h.get('phase', 'unknown')}).\n"
                         f"       The model is still loading. Re-run; the "
                         f"benchmark waits by default.")
            sys.exit(f"\nERROR: request {i} failed: HTTP {exc.code} {exc}")
        except (urllib.error.URLError, OSError) as exc:
            sys.exit(f"\nERROR: request {i} failed: {exc}\n"
                     f"       Server unreachable. Check it is running, and "
                     f"note that curl/urllib need --noproxy on this cluster:\n"
                     f"       curl --noproxy '*' {args.url}/health")
        elapsed = time.time() - t0

        rec = {"n": i, "query": query, "answer": r.get("response"),
               # tokens/seconds come from the server; older builds omit them.
               "tokens": r.get("tokens"), "truncated": r.get("truncated"),
               # at_cap means the generation ran out of budget -- a different
               # and quieter failure than truncated. See the note by the summary.
               "at_cap": r.get("at_cap"),
               "seconds": round(elapsed, 2)}
        records.append(rec)

        if i <= 3 or i % 10 == 0 or i == args.n:
            done = time.time() - t_start
            eta = done / i * (args.n - i)
            print(f"  [{i}/{args.n}] {rec['answer']:<5} "
                  f"{str(rec['tokens'] or '?'):>4}tok {elapsed:5.1f}s "
                  f"| elapsed {done / 60:5.1f}m eta {eta / 60:5.1f}m")

    wall = time.time() - t_start

    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            for rec in records:
                fh.write(json.dumps(rec) + "\n")
        print(f"\nPer-query records written to {args.out}")

    secs = sorted(r["seconds"] for r in records)
    toks = sorted(r["tokens"] for r in records if r["tokens"] is not None)
    trues = sum(1 for r in records if r["answer"] == "True")
    trunc = sum(1 for r in records if r["truncated"])
    at_cap = sum(1 for r in records if r["at_cap"])

    def pct(sorted_vals, p):
        return sorted_vals[min(int(len(sorted_vals) * p), len(sorted_vals) - 1)]

    print("\n=== results ======================================================")
    print(f"  queries            {len(records)}")
    print(f"  wall clock         {wall:.1f}s  ({wall / 60:.1f} min)")
    print(f"  seconds/query      mean {statistics.mean(secs):.2f}  "
          f"median {statistics.median(secs):.2f}  "
          f"p95 {pct(secs, .95):.2f}  max {secs[-1]:.2f}")
    if toks:
        print(f"  tokens/query       mean {statistics.mean(toks):.0f}  "
              f"median {statistics.median(toks):.0f}  "
              f"p95 {pct(toks, .95):.0f}  max {toks[-1]}")
        print(f"  throughput         {sum(toks) / wall:.1f} tok/s")
    print(f"  truncated          {trunc}/{len(records)} "
          f"({100 * trunc / len(records):.0f}%)"
          f"{'   <- raise --max-new-tokens' if trunc else ''}")
    print(f"  at cap             {at_cap}/{len(records)} "
          f"({100 * at_cap / len(records):.0f}%)"
          f"{'   <- ANSWERS UNRELIABLE, raise --max-new-tokens' if at_cap else ''}")
    print(f"  answered True      {trues}/{len(records)} "
          f"({100 * trues / len(records):.0f}%)")
    if at_cap:
        # Worth more than a percentage: these answers get written to
        # cache.sqlite3 and are never recomputed, so a wrong one here is frozen
        # for the whole multi-day run.
        print("\n  WARNING: queries that exhausted the token budget had their "
              "reasoning cut\n  before its conclusion. The extracted True/False "
              "is whatever appeared in\n  the fragment. Raise --max-new-tokens "
              "and re-run before trusting any of\n  this -- answers are cached "
              "permanently.")
    if trues > len(records) * 0.35:
        print("     WARNING: random ordered class pairs are almost never in a")
        print("     subsumption relation, so this should be strongly False-")
        print("     dominated. A high True rate means the model is not")
        print("     discriminating, and precision/recall will reflect that")
        print("     rather than the learner.")

    project(statistics.mean(secs))

    if args.batch_size > 0:
        print(f"\n--- batched (/api/batch, size {args.batch_size}) -------------")
        queries = [make_query(a, b) for a, b in pairs]
        t0, total_tok, n_done = time.time(), 0, 0
        for i in range(0, len(queries), args.batch_size):
            chunk = queries[i:i + args.batch_size]
            r = post(f"{args.url}/api/batch",
                     {"model": args.model, "system": system, "prompts": chunk},
                     args.timeout * 4)
            if "error" in r:
                print(f"  batch failed: {r['error']}")
                break
            total_tok += sum(r["tokens"])
            n_done += r["n"]
            print(f"  [{n_done}/{len(queries)}] {r['seconds_per_query']:.2f}s/query  "
                  f"{r['tokens_per_s']:.0f} tok/s aggregate")
        else:
            batch_wall = time.time() - t0
            per_query = batch_wall / max(n_done, 1)
            speedup = statistics.mean(secs) / per_query if per_query else 0
            print(f"\n  batched seconds/query {per_query:.2f}  "
                  f"({total_tok / batch_wall:.0f} tok/s)")
            print(f"  SPEEDUP vs sequential {speedup:.1f}x")
            print("\n  Batched projection (what a concurrent Java client "
                  "would achieve):")
            project(per_query)

    print()


if __name__ == "__main__":
    main()
