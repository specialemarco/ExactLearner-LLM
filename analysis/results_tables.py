"""Builds the paper's LaTeX tables from the job logs; latex_table_builder.ipynb shows them.

Run logs:      logs/<model>/<sampler>_<precomp|noprecomp>/<baseset>-<prompt>/exactl*-<job>.log
Baseline logs: logs/baselines/<name>.log, from org.experiments.EvaluateEmptyTBox and
               EvaluateReasonerPrecomputation.

Entry points, in notebook order: load, read_baseline, precomputation_latex,
precomp_disagreement, precomp_comparison_latex, eps_column_latex,
model_comparison_latex, show.
"""

import base64, glob, re, statistics, subprocess, tempfile
from collections import defaultdict
from pathlib import Path

import pandas as pd
from IPython.display import HTML, display
from scipy.stats import sem, t


# --- Settings -------------------------------------------------------------------

LOGS = Path(__file__).resolve().parent.parent / "logs"
BASELINES = LOGS / "baselines"

MODELS = {"mistral": "Mistral-7b",
          "deepseekQwen-1.5b": "DeepSeekR1-Qwen-1.5B",
          "deepseek-r1-14b": "DeepSeekR1-Qwen-14B",
          "deepseek-r1-32b": "DeepSeekR1-Qwen-32B",
          "olmo-2-13b": "OLMo2-13B",
          "olmo3-7b-think": "OLMo3-7B-Think",
          "ministral-8b": "Ministral-8B"}
BASESETS = ["C1", "C2", "C3"]
SAMPLERS = ["weighted", "unweighted"]
# As PacloDataset.baseSetTag() names the dataset folders.
FOLDER_BASESET = {"class_names": "C1", "class_names_exists_thing": "C2",
                  "class_names_exists_partial": "C3"}

# Job id -> why its run is left out of every table. Kept here, not by deleting the
# log, which the next `scripts/sync.sh olivia pull` would bring back.
EXCLUDED_JOBS = {
    "2424865": "deepseekQwen-1.5b C1 seed 0 on the warm shared cache; rerun cold as 2426157",
}

QUALITY = ["Macro precision", "Macro recall", "Micro precision", "Micro recall"]
COST = ["counterexamples", "Total membership queries", "Total time (ms)"]
# Log label -> (scale, decimals shown).
COLUMNS = {"counterexamples": (1, 1),
           "Total membership queries": (1, 0),
           "Total time (ms)": (1 / 60000, 1),
           **{label: (1, 3) for label in QUALITY}}


# --- Reading the logs ----------------------------------------------------------

PAIRS = r"^PRECOMPUTATION.*?: (\d+) of (\d+) ordered class pairs"
AXIOM = re.compile(r"^\*\*\*Subclass Axiom\*\*\*\nSubclass: (.+)\nSuperclass: (.+)$", re.M)


def figures(text):
    """Each COLUMNS figure in text, scaled; None when absent."""
    out = {}
    for label, (scale, _) in COLUMNS.items():
        # The last hit: a precomp run evaluates after precomputation and again after learning.
        hits = re.findall(rf"^{re.escape(label)}: ([\d.]+)$", text, re.M)
        out[label] = float(hits[-1]) * scale if hits else None
    return out


def subclass_pairs(text):
    return set(AXIOM.findall(text))


def parse(path):
    """One run's log as a row of figures."""
    text = open(path, errors="replace").read()
    model, arm, folder = Path(path).parent.parts[-3:]
    sampler, precomp = arm.split("_")
    baseset, prompt = folder.split("-", 1)
    eps = re.search(r"^epsilon = ([\d.]+)", text, re.M)
    row = {
        "baseset": baseset.upper(),
        # A *-eps<E> folder shares its prompt with the default one.
        "prompt": re.sub(r"-eps[\d.]+$", "", prompt),
        "epsilon": float(eps[1]) if eps else 0.2,
        "precomp": precomp == "precomp",
        "model": model,
        "sampler": sampler,
        "done": "Ontology learned successfully!" in text,
        **figures(text),
    }
    # Unanchored: vLLM's progress bar ends on \r, so a counterexample can share its line.
    row["counterexamples"] = len(re.findall(r"Counterexample \d+ at sample", text))
    axioms = re.search(r"^Hypothesis TBox logical axioms: (\d+)$", text, re.M)
    row["axioms"] = int(axioms[1]) if axioms else None

    # The run's totals include precomputation; subtract it so the rows show the loop alone.
    row["precomputation"] = None
    pairs = re.search(PAIRS, text, re.M)
    if pairs:
        # Logged since 2026-09-11; older runs' time cannot be split.
        pre_ms = re.search(r"^Precomputation time \(ms\): (\d+)$", text, re.M)
        eval_ms = re.search(r"^Precomputation evaluation time \(ms\): (\d+)$", text, re.M)
        scale = COLUMNS["Total time (ms)"][0]
        # "counterexamples" holds the axioms precomputation added, to share the column.
        pre = row["precomputation"] = {
            "counterexamples": int(pairs[1]),
            "Total time (ms)": int(pre_ms[1]) * scale if pre_ms else None}
        if row["Total membership queries"] is not None:
            row["Total membership queries"] -= int(pairs[2])
        if pre_ms and eval_ms and row["Total time (ms)"] is not None:
            row["Total time (ms)"] -= (int(pre_ms[1]) + int(eval_ms[1])) * scale
        evaluation = re.search(r"after precomputation(.*?)=== BARIS EVALUATION", text, re.S)
        if evaluation:
            pre.update({q: v for q, v in figures(evaluation[1]).items() if q in QUALITY})
            pre["pairs"] = subclass_pairs(evaluation[1])
    return row


def load(epsilon=0.2):
    """-> {(baseset, model, sampler, precomp): [finished runs]} at this epsilon."""
    results = defaultdict(list)
    for path in sorted(glob.glob(f"{LOGS}/*/*/*/exactl*-*.log")):
        if Path(path).stem.rsplit("-", 1)[-1] in EXCLUDED_JOBS:
            continue
        row = parse(path)
        if row["epsilon"] != epsilon:
            continue
        # Keyed even when no run finished (walltime), so the table shows dashes.
        runs = results[row["baseset"], row["model"], row["sampler"], row["precomp"]]
        if row["done"]:
            runs.append(row)
    return dict(results)


def read_baseline(name):
    """-> {baseset: figures} from logs/baselines/<name>.log; {} when absent."""
    try:
        text = open(BASELINES / f"{name}.log").read()
    except FileNotFoundError:
        return {}
    out = {}
    # A dataset's precomputation lines follow its load line, so split there.
    for folder, section in re.findall(r"^PACLO dataset \S*?owl2bench-1-el-(\S+?):(.*?)(?=^PACLO dataset|\Z)",
                                      text, re.M | re.S):
        row = figures(section)
        pairs = re.search(PAIRS, section, re.M)
        row["counterexamples"] = int(pairs[1]) if pairs else 0
        row["pairs"] = subclass_pairs(section)
        out[FOLDER_BASESET[folder]] = row
    return out


# --- Statistics ----------------------------------------------------------------

def mean_ci(values):
    """(mean, half-width of the 95% t interval), skipping None; no interval under two runs."""
    values = [v for v in values if v is not None]
    if not values:
        return None, None
    mean = statistics.mean(values)
    return mean, t.ppf(0.975, len(values) - 1) * sem(values) if len(values) > 1 else None


def f1_score(means):
    """Mean of macro and micro F1, from the mean precision and recall the table prints."""
    f1 = lambda p, r: 2 * p * r / (p + r) if p + r else 0.0
    return statistics.mean(f1(means[f"{kind} precision"], means[f"{kind} recall"])
                           for kind in ("Macro", "Micro"))


# --- Selecting runs ------------------------------------------------------------

def arm_order(arms, models=None):
    """(model, sampler) pairs sorted by models (default MODELS order), then SAMPLERS."""
    rank = lambda order, x: (order.index(x) if x in order else len(order), x)
    return sorted(arms, key=lambda ms: (rank(models or list(MODELS), ms[0]),
                                        rank(SAMPLERS, ms[1])))


def arms_in(results, precomp, models=None):
    """The (model, sampler) pairs with logs at this precomp, in arm_order()."""
    found = {(m, s) for _, m, s, p in results if p == precomp and (not models or m in models)}
    return arm_order(found, models)


def runs_per_baseset(results, model, sampler, precomp, basesets=BASESETS):
    return [results.get((baseset, model, sampler, precomp)) for baseset in basesets]


# --- Cells and captions --------------------------------------------------------

def baseset_tex(baseset):
    return rf"$C_{{{baseset[1:]}}}$"


def cell(values, digits):
    """"mean ± ci" in LaTeX; "--" when no run logged the figure."""
    mean, ci = mean_ci(values)
    if mean is None:
        return "--"
    if ci is None:
        return str(mean) if isinstance(mean, int) else f"{mean:.{digits}f}"
    return rf"{mean:.{digits}f}\,{{\scriptsize $\pm${ci:.{digits}f}}}"


def compact_cells(runs):
    """Cost cells with intervals, scores as bare means (widest_score_ci() goes in the caption)."""
    means = [mean_ci([r[q] for r in runs])[0] for q in QUALITY]
    return ([cell([r[label] for r in runs], COLUMNS[label][1]) for label in COST]
            + ["--" if m is None else f"{m:.3f}" for m in means])


def widest_score_ci(groups):
    return max((mean_ci([r[q] for r in runs])[1] or 0 for _, per_baseset in groups
                for runs in per_baseset if runs for q in QUALITY), default=0)


def n_note(groups, basesets=BASESETS):
    """"n=10 runs per row", naming any row with fewer (a dead job)."""
    counts = {(title, baseset): len(runs)
              for title, per_baseset in groups for baseset, runs in zip(basesets, per_baseset) if runs}
    if not counts:
        return ""
    common = statistics.mode(counts.values())
    odd = [f"{title} {baseset_tex(baseset)} n={n}" for (title, baseset), n in counts.items() if n != common]
    return f"n={common} runs per row" + (f" ({'; '.join(odd)})" if odd else "")


def caption_for(groups, precomp, epsilons=None, basesets=BASESETS):
    """"nlp-advanced, $\\epsilon$=0.2, precomp=False, n=10 runs per row", from the runs
    in groups [(title, [runs per base set])]. precomp=None leaves it out."""
    runs = [r for _, per_baseset in groups for rs in per_baseset for r in rs or []]
    prompt = ", ".join(sorted({r["prompt"] for r in runs}))
    # Passed in when an arm may have lost every run at some epsilon.
    eps = ", ".join(f"{e:g}" for e in sorted(epsilons or {r["epsilon"] for r in runs}))
    caption = rf"{prompt}, $\epsilon$={eps}" + ("" if precomp is None else f", precomp={precomp}")
    if n_note(groups, basesets):
        caption += f", {n_note(groups, basesets)}"
    pre = [r for r in runs if r["precomp"]]
    if pre:
        timed = all(r["precomputation"] and r["precomputation"]["Total time (ms)"] is not None
                    for r in pre)
        lead = "Q" if len(pre) == len(runs) else "With precomputation, q"
        caption += (f". {lead}ueries{' and time' if timed else ''} are the learning loop's alone;"
                    r" precomputation is in Table~\ref{table:precomputation}")
    return caption


# --- LaTeX scaffolding ---------------------------------------------------------

SHORT = {"precision": "Prec.", "recall": "Rec."}
QUALITY_COLS = [(kind, SHORT[metric]) for kind, metric in (q.split() for q in QUALITY)]


def frame(rows, columns):
    """{row label (tuple for a grouped index): [cell strings]} -> DataFrame."""
    return pd.DataFrame(rows, index=pd.MultiIndex.from_tuples(columns)).T


def styled_tabular(frame, css=None, corner=None):
    """A frame of LaTeX cells as a booktabs tabular. Grouped labels become \\multirow and
    \\multicolumn; added here: bold heads, a \\cmidrule under each grouped head and
    \\addlinespace between \\multirow groups. corner: head over the last index column."""
    frame = frame.copy()
    frame.index.names = [None] * frame.index.nlevels
    frame.columns.names = [None] * (frame.columns.nlevels - 1) + [corner]
    styler = frame.style.map_index(lambda v: "textbf:--rwrap;" if v else "", axis="columns")
    if css is not None:
        styler = styler.apply(lambda _: css, axis=None)
    cols = "c" * (frame.index.nlevels - 1) + "l" + "r" * frame.columns.size
    tex = styler.to_latex(hrules=True, column_format=f"@{{}}{cols}@{{}}",
                          multirow_align="c", multicol_align="c")
    out, in_header, groups = [], True, 0
    for line in tex.splitlines():
        if line.startswith(r"\midrule"):
            in_header = False
        if not in_header and line.startswith(r"\multirow"):
            groups += 1
            if groups > 1:
                out.append(r"\addlinespace")
        out.append(line)
        if in_header and r"\multicolumn" in line:
            rules, col = [], 1
            for c in line.rstrip(" \\").split(" & "):
                m = re.match(r"\\multicolumn\{(\d+)\}\{\w+\}\{(.*)\}$", c)
                span = int(m[1]) if m else 1
                if span > 1 and m[2]:
                    rules.append(rf"\cmidrule(lr){{{col}-{col + span - 1}}}")
                col += span
            out.append(" ".join(rules))
    return "\n".join(out)


def float_env(tabular, caption, label, small=False):
    """A table* around a tabular. small: \\small tabular, \\normalsize caption."""
    return "\n".join([r"\begin{table*}[htbp]", r"\centering", r"\setlength{\tabcolsep}{4pt}",
                      *([r"\small"] if small else []), tabular.strip(),
                      *([r"\normalsize"] if small else []),
                      rf"\caption{{{caption}}}", rf"\label{{{label}}}", r"\end{table*}"])


PREAMBLE = r"""\documentclass[border=6pt,varwidth=40cm]{standalone}
\usepackage{booktabs,caption,multirow,threeparttable}
\usepackage[table]{xcolor}
% standalone cannot hold a float; threeparttable sets the caption as wide as the table.
\renewenvironment{table*}[1][]{\begin{threeparttable}}{\end{threeparttable}}
\begin{document}
"""


def show(src):
    """Render a table inline as SVG; print the LaTeX if it does not compile."""
    with tempfile.TemporaryDirectory() as tmp:
        (Path(tmp) / "t.tex").write_text(PREAMBLE + src + "\n\\end{document}\n")
        ok = lambda *cmd: subprocess.run(cmd, cwd=tmp, capture_output=True).returncode == 0
        # --no-fonts: browsers do not render SVG fonts.
        if (ok("latex", "-interaction=nonstopmode", "-halt-on-error", "t.tex")
                and ok("dvisvgm", "--exact-bbox", "--bbox=4pt", "--no-fonts",
                       "--zoom=1.5", "t.dvi", "-o", "t.svg")):
            svg = base64.b64encode((Path(tmp) / "t.svg").read_bytes()).decode()
            # An <img> keeps each SVG's glyph ids apart; white keeps dark themes legible.
            display(HTML(f'<img src="data:image/svg+xml;base64,{svg}" style="background:#fff">'))
            return
    print(src)


# --- 1. Precomputation ---------------------------------------------------------

def precomp_cells(runs):
    """[axioms added, *scores] of the model's precomputation; None without one."""
    ps = [r["precomputation"] for r in runs or [] if r["precomputation"]]
    if not ps:
        return None
    # Every run replays the same precomputation: identical values count once.
    return [cell(v if len(set(v)) > 1 else v[:1], COLUMNS[label][1])
            for label in ["counterexamples", *QUALITY] for v in [[p[label] for p in ps]]]


def baseline_cells(row):
    """[axioms added, *scores] of a read_baseline() row; None without one."""
    return [cell([row[label]], COLUMNS[label][1]) for label in ["counterexamples", *QUALITY]] if row else None


def precomputation_latex(results, models=None, empty=None, reasoner=None, basesets=BASESETS):
    """Table 1: precomputation alone, between the empty T-box and the reasoner's."""
    sources = {}
    if empty:
        sources["Empty T-box"] = [baseline_cells(empty.get(b)) for b in basesets]
    arms = arms_in(results, True, models)
    for model in dict.fromkeys(m for m, _ in arms):
        # The sampler plays no part in precomputation, so pool both.
        sources[MODELS.get(model, model)] = [
            precomp_cells(sum((results.get((b, model, s, True)) or [] for m, s in arms if m == model), []))
            for b in basesets]
    if reasoner:
        sources["ELK reasoner"] = [baseline_cells(reasoner.get(b)) for b in basesets]

    rows = {(source, baseset_tex(b)): cells or ["--"] * 5
            for source, per_baseset in sources.items() for b, cells in zip(basesets, per_baseset)}
    table = frame(rows, [("", "Axioms"), *QUALITY_COLS])
    caption = ("Just precomputation, before any learning: one membership query per ordered class pair."
               " One learned hypothesis evaluated on each base set.")
    return float_env(styled_tabular(table), caption, "table:precomputation")


def precomp_disagreement(results, model, reasoner, baseset="C1"):
    """(wrong, missed): subsumptions the model added that the reasoner did not, and the
    reverse. Every base set asks the same class pairs, so one decides it."""
    llm = next(r["precomputation"]["pairs"] for (c, m, _, p), runs in results.items()
               if (c, m, p) == (baseset, model, True)
               for r in runs if r["precomputation"] and "pairs" in r["precomputation"])
    truth = reasoner[baseset]["pairs"]
    return sorted(llm - truth), sorted(truth - llm)


# --- 2. With and without precomputation ----------------------------------------

def printed_f1(runs):
    """f1_score() of the means as the table prints them, so ties stay ties."""
    return round(f1_score({q: round(mean_ci([r[q] for r in runs])[0], 3) for q in QUALITY}), 3)


def precomp_comparison_latex(results, model="mistral", sampler="unweighted", basesets=BASESETS):
    """Table 2: each base set with and without precomputation, the higher-F1 row shaded."""
    rows, shaded = {}, []
    for b in basesets:
        both = {precomp: results.get((b, model, sampler, precomp)) for precomp in (False, True)}
        for precomp, runs in both.items():
            rows[baseset_tex(b), "yes" if precomp else "no"] = compact_cells(runs) if runs else ["--"] * 7
        if all(both.values()):
            f1 = {precomp: printed_f1(runs) for precomp, runs in both.items()}
            shaded += [(baseset_tex(b), "yes" if p else "no") for p in f1 if f1[p] == max(f1.values())]
    table = frame(rows, [("Learning cost", "", h) for h in ("CEs", "Mem. queries", "Time (min)")]
                  + [("Learned quality", *c) for c in QUALITY_COLS])
    css = pd.DataFrame("", index=table.index, columns=table.columns)
    css.loc[shaded, table.columns.get_level_values(0) == "Learned quality"] = "cellcolor:{gray!15};"

    groups = [(f"{sampler.capitalize()} {'with' if precomp else 'without'} precomp.",
               runs_per_baseset(results, model, sampler, precomp, basesets)) for precomp in (False, True)]
    caption = f"{MODELS.get(model, model)}, {sampler} sampling, " + caption_for(groups, None, basesets=basesets)
    caption += (". Shaded is the setting with the higher mean of its macro and micro F1,"
                " each the harmonic mean of the precision and recall shown")
    caption += rf". Every score's 95\% t interval is within $\pm${widest_score_ci(groups):.3f}"
    return float_env(styled_tabular(table, css, corner=r"\textbf{Precomp.}"),
                     caption, "table:precomp-comparison", small=True)


# --- 3. Sampler and epsilon ----------------------------------------------------

def eps_scores(by_eps, model, samplers, basesets):
    """-> (means, cis): rows (base set, epsilon), columns (sampler, Macro/Micro, Prec./Rec.)."""
    means, cis = {}, {}
    for baseset in basesets:
        for epsilon, results in by_eps.items():
            for sampler in samplers:
                runs = results.get((baseset, model, sampler, False)) or []
                for q, col in zip(QUALITY, QUALITY_COLS):
                    key = (baseset_tex(baseset), f"{epsilon:g}", sampler.capitalize(), *col)
                    means[key], cis[key] = mean_ci([r[q] for r in runs])
    as_frame = lambda d: pd.Series(d, dtype=float).unstack([2, 3, 4], sort=False)
    return as_frame(means), as_frame(cis)


def eps_column_latex(by_eps, model="mistral", basesets=BASESETS):
    """Table 3: quality per base set and epsilon, a column group per sampler.
    by_eps: {epsilon: load(epsilon)}, rows in its order. Bold is each metric's best,
    shaded the highest f1_score(), both compared as printed so ties share it."""
    samplers = [s for s in SAMPLERS if any((b, model, s, False) in results
                                           for results in by_eps.values() for b in basesets)]
    means, cis = eps_scores(by_eps, model, samplers, basesets)
    printed = means.round(3)

    # Best per base set and metric, over both samplers and every epsilon.
    best = printed.T.groupby(level=[1, 2]).transform("max").T.groupby(level=0).transform("max")
    f1 = pd.DataFrame({s: printed[s].apply(
        lambda row: round(f1_score(dict(zip(QUALITY, row))), 3) if row.notna().all() else None, axis=1)
        for s in printed.columns.levels[0]})
    shaded = f1.eq(f1.max(axis=1).groupby(level=0).transform("max"), axis=0)
    css = pd.DataFrame({col: shaded[col[0]].map({True: "cellcolor:{gray!15};", False: ""})
                             + printed[col].eq(best[col]).map({True: "textbf:--rwrap;", False: ""})
                        for col in printed.columns})
    table = printed.map(lambda v: "--" if pd.isna(v) else f"{v:.3f}")

    groups = [(f"{MODELS.get(model, model)}-{sampler}-eps{epsilon:.2f}",
               runs_per_baseset(results, model, sampler, False, basesets))
              for sampler in samplers for epsilon, results in by_eps.items()]
    caption = f"{MODELS.get(model, model)}, " + caption_for(groups, False, epsilons=by_eps, basesets=basesets)
    caption += (". Bold is each metric's best score within a base set; shaded is the"
                " setting with the highest mean of its macro and micro F1, each the"
                " harmonic mean of the precision and recall shown")
    caption += rf". Every 95\% t interval is within $\pm${cis.max().max():.3f}"
    return float_env(styled_tabular(table, css, corner=r"$\epsilon$"),
                     caption, "table:quality-by-eps", small=True)


# --- 4. Models compared --------------------------------------------------------

def model_comparison_latex(results, baseset="C2", sampler="unweighted", precomp=False,
                           models=None, empty=None):
    """Table 4: one row per model at one setting, under the empty T-box's scores."""
    models = [m for m, s in arms_in(results, precomp, models)
              if s == sampler and results.get((baseset, m, s, precomp))]
    rows, groups = {}, []
    if empty and baseset in empty:
        rows["Empty T-box"] = ["0"] + [cell([empty[baseset][q]], 3) for q in QUALITY]
    for model in models:
        runs = results[baseset, model, sampler, precomp]
        groups.append((MODELS.get(model, model), [runs]))
        rows[MODELS.get(model, model)] = ([cell([r["axioms"] for r in runs], 1)]
                                          + [cell([r[q] for r in runs], 3) for q in QUALITY])
    table = frame(rows, [("", "Axioms"), *QUALITY_COLS])
    caption = caption_for(groups, precomp, basesets=[baseset])
    caption = f"{baseset_tex(baseset)} base set, {sampler} sampling, {caption}"
    return float_env(styled_tabular(table), caption, f"table:models-{baseset.lower()}-{sampler}")
