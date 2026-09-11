 
# ExactLearner+LLM
 
This is a Java implementation of the **ExactLearner+LLM** tool proposed in the paper
_"Actively Learning EL Terminologies from Large Language Models"_, submitted to **ECAI 2025**.
In this same repository you can find the experiments and results presented in the paper.

## General information

This repository contains both the code of the ExactLearner+LLM tool and the code for the experiments.
The structure of the repository is as follows:
- `src/main/java/exactlearner` contains the code of the ExactLearner+LLM tool.
- `src/main/java/experiments` contains the code for the experiments.
- `src/main/resources/ontologies` contains the ontologies used in the experiments.
- `results/ontologies` contains the learnt ontologies.
- `analysis` contains the metrics computed over the learnt ontologies.

## How to use

> Requirements:
> - A JDK to build with: **17 or newer** (the cluster uses 21, and `.sdkmanrc`
>   pins 21 locally)
> - Maven
> - sqlite3

This is a Maven project. To run the ExactLearner+LLM tool, you need to have Maven installed.
To install Maven, follow the instructions in the official website: https://maven.apache.org/install.html

**Maven builds with whatever `JAVA_HOME` points at, not with the `java` on your
`PATH`.** If that is a JDK below 17 the build dies with `invalid flag: --release`
(or `invalid target release: 17`), which reads like a broken project but is a
broken shell. Check with `mvn -version`, whose `Java version:` line is the one
that matters.

For sdkman users the repository pins it — run this once per shell in the project
directory, or set `sdkman_auto_env=true` in `~/.sdkman/etc/config` to have it
applied automatically on `cd`:

```bash
sdk env          # or: sdk env install, the first time
mvn -version     # confirm "Java version: 21.x"
```

Otherwise export it yourself, e.g. `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`
on macOS, or `module load Java/21.0.8` on the cluster (`run_experiment.sh` already does).

The compiled bytecode targets **17**, not the build JDK, so `java -cp target/classes ...`
works on any JVM from 17 up.

> **Watch out:** `mvn compile` prints `BUILD SUCCESS` when it has nothing to do.
> If the classes are already up to date it never invokes the compiler, so a
> broken `JAVA_HOME` looks like a clean build. Use `mvn clean compile` when you
> want the build actually verified, and look for a `Compiling N source files`
> line.

To install the dependencies, run the following command in the root directory of the project:
```bash
mvn install
```
To compile the project, run the following command:
```bash
mvn compile
```

The project uses a cache system based on **sqlite3** to store the results of the queries.
To install sqlite3, follow the instructions in the official website: https://www.sqlite.org/download.html

## Running the experiments

### What changed (2026-08-27)

An experiment varies three things, and they are independent of each other:

| Axis | When it happens | Selected by |
|---|---|---|
| **precomputation** | *before* the learning loop | `precomp=` flag (Java: `skipPrecomputation`) |
| **sampler** | *inside* the loop | `sampler=` flag (which picks the launcher class) |
| **evaluation** | *after* the loop | `eval=` flag (Java: `evaluateAfterRun`) |

There used to be four launcher classes, two of which existed only to flip one
default. Those two are gone; the loop lives once, in `LaunchLLMLearner.runLearner()`,
and the two axes that are not the sampler are now command-line flags:

| Old class | Now |
|---|---|
| `LaunchLLMLearnerAInducedNoPre` | `LaunchLLMLearnerAInduced` with `precomp=false` |
| `LaunchLLMLearnerWithBarisEval` | `LaunchLLMLearner` with `eval=baris` |

`src/test/java/org/experiments/LauncherFlagMatrixTest.java` pins this: it asserts
the flags reproduce exactly what the deleted classes hard-coded.

### The launchers

| Class | Sampler | Default `precomp` | Default `eval` |
|---|---|---|---|
| `org.experiments.LaunchLLMLearner` | uniform PAC (`sampler=pac`) | on | off |
| `org.experiments.LaunchLLMLearnerAInduced` | ABox-induced, PACLO (`sampler=weighted\|unweighted`) | on | on |
| `org.experiments.LaunchExactLearner` | synthetic teacher (no LLM) | — | — |

`LaunchLLMLearnerAInduced` needs `initialOntology.owl` and `baseSet` beside the
target ontology. When they are missing it prints *"A-induced setup not available
… falling back to uniform PAC"* and silently becomes the uniform arm — that is a
different experiment, so check for that line.

### The main arm

**What we actually run is the PACLO (A-induced) sampler with precomputation off
and evaluation on:**

```bash
scripts/submit.sh <model> <config> precomp=false
```

Precomputation off is the point of this arm: `learner.precomputation()` sweeps
every atomic `A ⊑ B` pair before the loop starts, absorbing the easy subsumptions,
so with it on you cannot tell what the ABox-induced sampler found by itself.
Evaluation on gives the Macro/Micro Precision/Recall report at the end.

**This is not what you get by leaving the flags off.** Both LLM launchers default
`precomp` to *on*, because each kept the behaviour of the class it replaced, so
the main arm has to ask for `precomp=false` every time. `eval` is already on for
`LaunchLLMLearnerAInduced` and needs nothing. The defaults are pinned by
`LauncherFlagMatrixTest` — changing them is a deliberate decision, not an edit.

### Running on the cluster (the normal path)

```bash
scripts/submit.sh <model> <config> [name=value ...]
```

`<model>` is a file in `scripts/models/` without the `.env` (`deepseek-r1-32b`,
`mistral-7b`). `<config>` is a name under
`src/main/java/org/configurations/experiments` — the directory and the `.yml`
are both optional. Submit from the repository root.

Everything after the config is `name=value`, in any order, all optional:

| Parameter | Meaning |
|---|---|
| `precomp=true\|false\|reuse` | run `learner.precomputation()` before the loop; `reuse` runs it once and replays it across repeats |
| `eval=baris\|none` | Macro/Micro Precision/Recall after the loop |
| `cache=shared\|fresh\|<path>` | query cache; `fresh` gives the job its own file |
| `sampler=weighted\|unweighted\|pac` | where the loop's candidate axioms come from (default `weighted`) |
| `budget=global\|per-round` | how the PAC sample budget is spent |
| `resume=true\|false` | continue from the previous job's checkpoint (default off) |
| `seed=N` `pacseed=N` | A-induced sampler seed, uniform PAC sampler seed |
| `repeats=N` | submit N jobs, seeds `seed`..`seed+N-1`, for a confidence interval |

```bash
# THE MAIN ARM — A-induced sampler, no precomputation, evaluation on
scripts/submit.sh deepseek-r1-32b owl2bench/c2-nlp-advanced precomp=false

# five repeats for a confidence interval — five jobs, seeds 1..5
scripts/submit.sh deepseek-r1-32b owl2bench/c2-nlp-advanced precomp=false repeats=5

# with precomputation, to measure what it contributes
scripts/submit.sh deepseek-r1-32b owl2bench/c2-nlp-advanced precomp=true

# a run whose timings must stand alone: its own cache, so it pays for every query
scripts/submit.sh deepseek-r1-32b owl2bench/c2-nlp-advanced precomp=false cache=fresh

# ten repeats sharing ONE precomputation, each with its own cache, scored twice
scripts/submit.sh deepseek-r1-32b owl2bench/c2-nlp-advanced precomp=reuse cache=fresh repeats=10

# the sampler axis: the same experiment under each of the three candidate sources
scripts/submit.sh deepseek-r1-32b owl2bench/c2-nlp-advanced precomp=false sampler=weighted
scripts/submit.sh deepseek-r1-32b owl2bench/c2-nlp-advanced precomp=false sampler=unweighted
scripts/submit.sh deepseek-r1-32b owl2bench/c2-nlp-advanced precomp=false sampler=pac
```

#### The three samplers

`weighted` and `unweighted` are [paclo](https://github.com/sertkaya/paclo)'s two
ABox-induced samplers, ported into one class and selected by the flag:

| `sampler=` | paclo class | Premise individual drawn from |
|---|---|---|
| `weighted` (default) | `WeightedABoxInducedSubsumptionSampler` | individuals with ≥1 base-set type, with probability ∝ 2^\|C(a,K₀)\| |
| `unweighted` | `ABoxInducedSubsumptionSampler` | **every** individual in the signature, uniformly |
| `pac` | — (`Pac.getRandomStatement()`) | nothing; it samples the signature, never the ABox |

The two ABox arms differ in the population as well as the weights, because
upstream does. Under `unweighted`, drawing an untyped individual leaves the
premise empty, so the candidate is `owl:Thing ⊑ X` — that is the plain sampler's
behaviour, not a defect, and it is the reason the weighted one exists. Expect a
large share of `⊤` premises on a dataset where most individuals carry no
base-set type.

What they *do* share: the p=0.5 filter on the drawn individual's types, the
strict-subset retry, and the rarity weighting of the conclusion. One thing is
deliberately **not** reproduced — paclo's plain class indexes its conclusion
weights by position in a `HashSet` difference while the counts are indexed by
position in the base set, so the weight applied to a concept is another
concept's. Both upstream classes intend the same quantity and the weighted one
keys it correctly; reproducing the misalignment would make the arms differ in a
second, accidental way. See the `Weighting` javadoc in
`src/main/java/org/sampler/ABoxInducedSubsumptionSampler.java`.

Also not ported: upstream's `uniformConclusions` constructor flag, present on
both classes, which replaces the rarity weighting with a uniform pick. That is a
third axis and nothing asks for it yet.

Two caveats when comparing against `sampler=pac`:

- It is a different launcher class, and only the A-induced one batches its
  candidates (`EXACTLEARNER_BATCH_SIZE` prompts per LLM call). A `pac` run
  queries one at a time and is much slower per sample. That is the arm, not a
  regression.
- Its randomness is `pacseed=`, not `seed=` — the two streams are independent.

Outputs are kept apart automatically: a non-default arm puts its own name in
`EXACTLEARNER_RUN_TAG` and in the log folder, so `sampler=unweighted seed=3`
does not overwrite `sampler=weighted seed=3` in `results/ontologies/`.

#### Reusing one precomputation across repeats

**The use case:** you want precomputation *on*, but the pass done *once* — then
ten independent learning runs off it, each with its own seed and cache, and
precision/recall at both ends.

```bash
scripts/submit.sh mistral-7b owl2bench/c2-nlp-advanced precomp=reuse cache=fresh repeats=10
```

What that gives you:

| Job | What it does |
|---|---|
| Repeat 1 | runs the precomputation, records it, evaluates, learns, evaluates again |
| Repeats 2–10 | wait for repeat 1, replay its record, evaluate, learn under their own seed, evaluate again |

One precomputation, ten independent learning runs, two evaluations per job.

It works because `precomputation()` walks every ordered pair of classes — 131
classes on the OWL2Bench targets, so 17,030 pairs, each an ELK entailment on H
and a query on T — with no randomness in it. Every repeat would otherwise
recompute an identical result.

Two things to weigh before running exactly that command:

- **`cache=fresh` and `precomp=reuse` interact.** Repeat 1 pays 17,030 *cold*
  LLM calls for the precomputation; repeats 2–10 pay none, because they replay.
  Their learning phases are still cold and comparable with each other, but
  repeat 1's total time is not comparable with theirs. Drop `cache=fresh` if you
  want the ten wall-clock figures to mean the same thing.
- **Precomputation charges all 17,030 pairs to the membership count.** The C2
  mistral sweep spent ~31,880 membership queries with `precomp=false`, so turning
  it on roughly doubles the reported query cost — and that half is identical
  across all ten repeats. Worth deciding whether you want it in the denominator
  of your rates.

The first repeat to finish the pass writes
`results/ontologies/precomp_<config>_<model>_<format>_<system>.txt` and the rest
replay it. That path carries **no run tag** — being shared across the repeats is
the whole point — and the file is written to a temp name and moved into place, so
repeats starting together cannot read a half-written record.

What replays is the *step sequence*, not a saved hypothesis. Precomputation also
builds the `ConceptRelation` that `decompose()` and `AxiomSimplifier` read, and
nothing else populates it; restoring a hypothesis `.owl` alone would leave it
empty and quietly change how the loop decomposes.
`PrecomputationReplayTest` pins both halves.

With `repeats=N`, `submit.sh` chains repeats 2..N behind repeat 1 with
`--dependency=afterany`, because otherwise the flag mostly does nothing: the
record is written by whichever job finishes precomputation first, and every job
that started before that computes the whole pass itself. Submitted flat, ten
repeats can perform ten precomputations.

```
Submitting 10 repeats, seeds 1..10
  precomp=reuse: repeat 1 records the precomputation and repeats 2..10 wait for it
  seed=1 ... -> Submitted batch job 4200001
  seed=2 ... -> Submitted batch job 4200002 (after 4200001)
```

Three things about the chain:

- The dependents wait on repeat 1 **alone**, so they run in parallel with each
  other — but they wait for the whole of it, not just its precomputation phase.
  Slurm cannot depend on a phase. That serialisation is the price, and it is why
  the chain is tied to `precomp=reuse` rather than applied to every sweep.
- `afterany`, not `afterok`, deliberately. Repeat 1 records within its first phase
  but runs for hours afterwards, and a job killed at walltime exits non-zero —
  under `afterok` that would cancel all nine dependents over a run that had
  already done the one thing they were waiting for. Under `afterany` they start
  regardless: replaying if the record is there, computing if it is not, which is
  exactly the unchained behaviour.
- `scancel` on repeat 1 does **not** cancel the dependents; they start on its
  termination and each recompute. Cancel them too.

Off by default, and worth knowing why: it makes one repeat's output an input to
the others. The record is fingerprinted against the target's class IRIs and a
mismatch recomputes rather than replaying, but if you change the model or the
target, delete the file or run one repeat with `precomp=true` first.

#### Evaluating before and after the loop

When `eval` is on and precomputation runs, the Baris evaluation now runs
**twice** — once straight after precomputation, once after the loop:

```
=== BARIS EVALUATION (Macro/Micro Precision/Recall) after precomputation (A-induced), hypothesis has N logical axioms ===
...
=== BARIS EVALUATION (Macro/Micro Precision/Recall) after learning (A-induced), hypothesis has M logical axioms ===
```

The pair separates what the exhaustive pass already knew from what the sampler
went on to add — previously recoverable only by running `TestPrecomputationOnly`
as a second job against the same target. With `precomp=false` there is nothing to
report at the first point, so only the second line appears.

`scripts/run_args.sh` is the parser and the reference. Both `submit.sh` and the
job source it, so a misspelled parameter fails at submission rather than 20
minutes later on a compute node. `scripts/run_experiment.sh` picks the launcher class from `sampler=`.

> `precomp` is the readable direction of the Java flag, which is
> `skipPrecomputation`. `precomp=false` is what makes it **skip**. The script
> does the inversion for you.

#### Resuming a run that hit the walltime

A C2 run does not fit in one 24 h job, and a job killed at the walltime keeps
everything it learned: `checkpointHypothesis()` writes after **every**
counterexample, into `results/ontologies/`:

```
expertOntology_<...>.owl                  the hypothesis as of the last counterexample
expertOntology_<...>-trajectory/0001.owl  a numbered copy per counterexample
expertOntology_<...>-run-state.properties counterExamples, providedSamples, samplerDraws,
                                          and the metrics totals
```

`resume=true` continues from those instead of starting empty:

```bash
scripts/submit.sh deepseek-r1-32b owl2bench/c2-nlp-advanced precomp=false resume=true
```

The hypothesis is loaded rather than re-derived, `Pac.providedSamples` is put
back, and the sampler is fast-forwarded by replaying that many `sample()` calls
— purely local, no reasoner, no model, no cache — so the next candidate is the
one an uninterrupted run would have drawn. Restart costs seconds.

> ⚠️ **`resume=false` deletes the checkpoint.** `loadHypothesisOntology()` calls
> `hypoFile.delete()` whenever the flag is off, so a plain resubmit in the same
> directory destroys the previous job's hypothesis before it starts. Copy
> `results/ontologies/` aside if you are not certain.

**Why the query cache is not enough on its own.** The cache makes the *questions*
free but not the Java re-derivation, which is what fills the wall clock when
nothing is waiting on the GPU: replaying a counterexample costs ~4.5 min against
~7.9 min for a novel one. A 24 h job that starts with `C` counterexamples banked
can therefore add only `(1440 − 4.5C)/7.9` more, which reaches zero at
**C = 320** — against the ~381 that job 4110003's sampling rate puts a finished
C2 run at. Chaining jobs without `resume=true` converges short of the finish
line no matter how many jobs it gets.

Read these lines to confirm a resume took:

```
RESUMING from results/ontologies/... (771 logical axioms, counterexamples=190 ...)
  resumed at counterexample 190, 5201/10661 of the budget already spent
```

The axiom count must match the last `Checkpointed hypothesis` line of the run
being continued, and the first new counterexample must be numbered 191, not 1.

**What resume changes about the numbers.** The run's results then come from
several jobs rather than one. The hypothesis and the sample stream are exact;
the metrics totals (`membCount`, `equivCount`, the largest counterexample) are
carried in the state file so the statistics still describe the whole run. A
checkpoint written before those fields existed resumes fine but restarts its
query counts from zero — the launcher prints a warning saying so.

#### Repeats and confidence intervals

```bash
scripts/submit.sh deepseek-r1-32b owl2bench/c2-nlp-advanced precomp=false repeats=5
```

**How it works.** `submit.sh` calls `sbatch` **N times instead of once**. Repeat
*i* is submitted with `seed=<base+i>` and `pacseed=<base+i>` appended to whatever
you typed — the base is your own `seed=` if you gave one, otherwise 1 — and with
`EXACTLEARNER_RUN_TAG=seed<n>` exported into its environment. Slurm forwards that
variable to the job, and the launcher appends it to every output name:

```
results/ontologies/expertOntology_c2_deepseek-r1-32b_nlp_advanced_seed1.owl
                                                     ...          _seed2.owl
statistics/expertOntology.owl_deepseek-r1-32b_nlp_advanced_seed1
```

That tag is the whole reason repeats can run at once. Without it all five jobs
write the same hypothesis, the same `-trajectory/`, the same
`-run-state.properties` and the same statistics file, and race on the shared
target copy — which one job rewrites while another reads it, taking the wrong
concept and role counts into its statistics.

Because they are separate jobs they queue independently, run in parallel on
different nodes, and one failing leaves the rest alone. Each is an ordinary single
run that knows nothing about the others: `repeats=` is acted on only by
`submit.sh`, and `run_experiment.sh` warns if it ever sees it rather than letting
the name imply one job does all N.

The spread across those files is the interval. `repeats=3 seed=10` starts the
count at 10, so you can add repeats later without colliding with the first batch —
the tag is named for the seed, not for the repeat's position, so re-running seed 3
overwrites seed 3 rather than landing beside it as a spurious fourth sample.

**What this does and does not measure.** It measures **the sampler's randomness**:
which candidate axioms got proposed, and in what order. That is what the seeds
change.

It does **not** measure variation in the model's answers, and no flag can, because
`llm_server.py` decodes at `temperature=0.0` — greedy. **The same question returns
the same answer whether it is re-asked or replayed from the cache.** Disabling the
cache would buy you N× the GPU cost for identical answers.

This is also why repeats should keep the **shared** cache rather than `cache=fresh`:
later seeds re-ask many of the same questions and get them back for free, so five
repeats cost far less than five times one.

If you ever do want an interval over the model's answers, greedy decoding has to go
first: `generate_batch()` in `scripts/llm_server.py` would need `temperature` and
`seed` plumbed through, and each repeat would then need its own cache. Be aware that
a non-zero temperature makes the teacher **self-inconsistent** — the learner can get
contradictory answers to the same question inside a single run, which is a different
experiment, not a noisier version of this one.

### Running locally

Build once, then invoke the launcher directly. The flags are **positional**:

```
<config> [skipPrecomputation] [evaluateAfterRun]
```

PAC epsilon and delta come from the config (`epsilon:` and `delta:`, 0.2 and 0.1
when left out). They size the sample budget for every sampler, not just uniform PAC.

```bash
mvn -DskipTests compile
mvn dependency:build-classpath -Dmdep.outputFile=cp.txt

export EXACTLEARNER_MODEL=deepseek-r1-32b   # owl2bench/ configs name no model
CP="target/classes:$(cat cp.txt)"
CFG=src/main/java/org/configurations/experiments/owl2bench

# THE MAIN ARM — A-induced, precomputation off, evaluation on.
# The 2nd argument is skipPrecomputation, so "off" is spelled true.
java -cp "$CP" org.experiments.LaunchLLMLearnerAInduced $CFG/c2-nlp-advanced.yml true true

# A-induced with precomputation, to measure what it contributes
java -cp "$CP" org.experiments.LaunchLLMLearnerAInduced $CFG/c2-nlp-advanced.yml false true

# uniform PAC baseline, with the Baris evaluation
java -cp "$CP" org.experiments.LaunchLLMLearner $CFG/c2-nlp-advanced.yml false true
```

`EXACTLEARNER_MODEL` is required for the `owl2bench/` configs, since they name no
model; the run stops immediately and says so if it is unset. The flat legacy
configs still carry their own and need nothing.

Because the arguments are positional, **`evaluateAfterRun` cannot be given
without `skipPrecomputation` ahead of it**. Pass `false` for it if you only want
to set the evaluation. Note the 2nd argument is `skipPrecomputation`, not
`precomp` — the inversion only exists in the shell scripts. Omitting an argument
leaves the launcher's own default, which is not the same for both classes.

`mvn exec:java` also works when you have network access, but `exec-maven-plugin`
is not declared in `pom.xml`, so Maven has to fetch it — the `java -cp` form
above is what the cluster scripts use.

The synthetic-teacher run is unchanged and takes only `<config> [epsilon] [delta]`:

```bash
java -cp "target/classes:$(cat cp.txt)" org.experiments.LaunchExactLearner \
    src/main/java/org/configurations/experiments/nlp-advanced.yml
```

### Checking that you got the run you asked for

The launcher echoes its configuration on startup. Read these lines before
trusting a run:

```
model = deepseek-r1-32b (from EXACTLEARNER_MODEL; the config names none)
skipPrecomputation = true
evaluateAfterRun = true
cache = cache.sqlite3 (existing)          # (existing) means answers may be replayed
Running experiment (A-induced) for ...    # the label is empty for the uniform arm
SKIPPING precomputation() — the loop starts from an empty hypothesis.
```

Those are the lines of the main arm. Check `model =` names the weights you meant:
it is the cache key, so the wrong name reads and writes another model's answers.
If `skipPrecomputation` says `false`, or the `SKIPPING` line is absent,
precomputation ran and the run is the other experiment. If the `(A-induced)`
label is missing, or you see *"falling back to uniform PAC"*, the PACLO data was
not found beside the target ontology.

The Slurm script additionally echoes `Run parameters: ...`, the batching flags,
the budget mode and both seeds.

### Configurations

`src/main/java/org/configurations/experiments/` holds the YAML configs:

```
experiments/
  owl2bench/     c{1,2,3}-{nlp,manchester}-{simple,advanced}.yml   -- name no model
  <flat files>   the paper's small-ontology configs, and the pre-2026-09-01
                 OWL2Bench ones, which hard-code their model
```

The `owl2bench/` configs **do not name a model.** The model comes from the first
argument to `submit.sh`, so one config serves every model:

```bash
scripts/submit.sh deepseek-r1-32b owl2bench/c2-nlp-advanced precomp=false
scripts/submit.sh mistral-7b      owl2bench/c2-nlp-advanced precomp=false
```

Everything in that folder is OWL2Bench, so the name carries only what varies: the
dataset (`c1` = `class_names`, `c2` = `exists_thing`, `c3` = `exists_partial`), the
query format, and the prompt.

#### What the model name is for

It is a **cache key and a label — it does not select the weights.** `llm_server.py`
serves whatever `--model` path it was started with and merely echoes the name back,
so on the cluster `MODEL_PATH` chooses the weights and the name decides only which
cache rows are read and written and what the output file is called. The two must
agree, which is why they now come from the same place: `run_experiment.sh` exports
`EXACTLEARNER_MODEL` from `MODEL_NAME` in `scripts/models/<model>.env`, the very
file that supplies the weights.

`EXACTLEARNER_MODEL` overrides whatever a config names, and says so on startup:

```
model = deepseek-r1-32b (from EXACTLEARNER_MODEL; the config names none)
model = mistral (from EXACTLEARNER_MODEL, OVERRIDING the config's [mistral, mixtral, llama2:13b, llama3])
models = [deepseek-r1-32b] (from the config; set EXACTLEARNER_MODEL to override)
```

To run a launcher **locally** against a model-agnostic config, set it yourself —
unset, the run fails immediately with that instruction rather than starting:

```bash
EXACTLEARNER_MODEL=deepseek-r1-32b java -cp "target/classes:$(cat cp.txt)" \
    org.experiments.LaunchLLMLearnerAInduced \
    src/main/java/org/configurations/experiments/owl2bench/c2-nlp-advanced.yml 0.2 0.1 true true
```

#### `models:` as a list

A config may still name several models, and `run()` then loops over them, learning
a separate hypothesis per model. That dates from a shared Ollama server hosting
many models at once; the paper's `nlp-advanced` and `manchester-*` configs use it.

**It cannot work against the cluster setup**, where one job serves exactly one
model: the second and later entries would be answered by the first one's weights
and filed under their own cache key. `EXACTLEARNER_MODEL` collapses the list to one
entry for that reason, and prints what it dropped.

#### Legacy configs

The flat names still work and are untouched. Be aware they mislead: every
`mistral-owl2bench-*.yml` at the top level actually serves **deepseek-r1-32b** —
the `mistral-` prefix predates the model change. Prefer `owl2bench/`.

The OWL2Bench/PACLO configs read from `data_paclo/`, which is gitignored and must
be staged by hand.

> **Note:** the LLM service URLs live in `src/main/java/org/exactlearner/connection`;
> `EXACTLEARNER_OLLAMA_URL` overrides the endpoint at runtime.

> **Note:** a full run takes a long time — hours to days, depending on the
> ontology, the PAC budget and the model.
