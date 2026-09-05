#!/usr/bin/env python3
"""Mean and 95% CI of the evaluation metrics across repeat seeds.

    scripts/collect-repeats.py [logs/exactlearner-*.log]

Reads the finished runs out of the job logs and groups them by (config, model).
A run that did not reach the evaluation is skipped and named, so a timed-out
repeat cannot quietly drag a mean down.
"""
import glob, re, statistics, sys
from collections import defaultdict

# Longest first: class_names is a prefix of the other two.
CONFIGS = [("class_names_exists_thing", "C2"),
           ("class_names_exists_partial", "C3"),
           ("class_names", "C1")]

METRICS = ["Macro precision", "Macro recall", "Micro precision", "Micro recall"]

# Two-sided 95% t, by n. Falls back to the normal value for larger samples.
T95 = {2: 12.706, 3: 4.303, 4: 3.182, 5: 2.776, 6: 2.571, 7: 2.447,
       8: 2.365, 9: 2.306, 10: 2.262, 11: 2.228, 12: 2.201, 15: 2.145, 20: 2.093}


def parse(path):
    text = open(path, errors="replace").read()
    ont = re.search(r"^Ontology: (.+)$", text, re.M)
    if not ont:
        return None
    config = next((tag for key, tag in CONFIGS if key in ont.group(1)), "?")
    row = {"config": config,
           "model": (re.search(r"^model = (\S+)", text, re.M) or [None, "?"])[1],
           "seed": (re.search(r"PAC seed = (\d+)", text) or [None, "?"])[1],
           "ces": len(re.findall(r"^Counterexample \d+ at sample", text, re.M)),
           "done": "Ontology learned successfully!" in text}
    for m in METRICS:
        hit = re.search(rf"^{m}: ([\d.]+)$", text, re.M)
        row[m] = float(hit.group(1)) if hit else None
    return row


def summarise(values):
    n = len(values)
    mean = statistics.mean(values)
    if n < 2:
        return f"{mean:.4f}  (n=1)"
    sd = statistics.stdev(values)
    t = T95.get(n, 1.96)
    return f"{mean:.4f} +/- {t * sd / n**0.5:.4f}  (sd {sd:.4f}, n={n})"


def main():
    # Recursive: submit.sh groups logs under logs/<config>-<model>/, and
    # "**" also matches zero directories, so flat logs/ still works.
    paths = sys.argv[1:] or sorted(
        glob.glob("logs/**/exactlearner-*.log", recursive=True))
    groups, skipped = defaultdict(list), []
    for p in paths:
        row = parse(p)
        if row is None or not row["done"]:
            skipped.append(p)
            continue
        groups[(row["config"], row["model"])].append(row)

    for (config, model), rows in sorted(groups.items()):
        seeds = sorted(r["seed"] for r in rows)
        print(f"\n=== {config}  {model}  seeds: {' '.join(seeds)}")
        print(f"  {'counterexamples':<18} {summarise([r['ces'] for r in rows])}")
        for m in METRICS:
            vals = [r[m] for r in rows if r[m] is not None]
            if vals:
                print(f"  {m:<18} {summarise(vals)}")

    if skipped:
        print(f"\nskipped {len(skipped)} incomplete run(s):")
        for p in skipped:
            print(f"  {p}")


if __name__ == "__main__":
    main()
