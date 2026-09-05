#!/bin/bash
# ExactLearner-LLM on Slurm: model server + learner in one job.
#
#   scripts/submit.sh <model> <config> [name=value ...]
#
# <model> names a file in scripts/models/. Account and GPUs come from that
# script's sbatch line, because sbatch reads the directives below first.
#
# Run from the repository root. Once per machine:
#   cp scripts/experiment.env.example scripts/experiment.env   # then edit it
#   module purge; module load Java/21.0.8 Maven/3.6.3
#   mvn -o -DskipTests compile
#   mvn -o dependency:build-classpath -Dmdep.outputFile=cp.txt
# experiment.env, cp.txt and data_paclo/ are gitignored; a pull will not bring
# them.
#SBATCH --account=ec30
#SBATCH --job-name=exactlearner
#SBATCH --partition=accel
# --nodes=1 and the a100: prefix are load-bearing: a bare --gpus=4 can split
# across nodes and hang vLLM, and accel's H100s have no kernel image for it.
#SBATCH --nodes=1
#SBATCH --gpus-per-node=a100:4
#SBATCH --cpus-per-task=8
# Fallback only, like --time below: submit.sh passes --mem from MEMORY in
# scripts/models/<model>.env when that is set.
#SBATCH --mem=128G
# Fallback only: submit.sh passes --time from WALLTIME in scripts/models/<model>.env,
# and a command-line option beats a directive. This is what a bare sbatch gets.
#SBATCH --time=24:00:00
#SBATCH --output=logs/%x-%j.log

set -euo pipefail

die()  { printf 'ERROR: %s\n'   "$*" >&2; exit 1; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }

# ----- personal configuration (untracked) ------------------------------------
# Sourced before the defaults below, so it beats them; an sbatch-line export
# beats it in turn. Relocate with EXACTLEARNER_ENV=/path/to/file.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXACTLEARNER_ENV="${EXACTLEARNER_ENV:-$SCRIPT_DIR/experiment.env}"
if [[ -f "$EXACTLEARNER_ENV" ]]; then
  set +u; . "$EXACTLEARNER_ENV"; set -u   # -u would abort on unset references
  echo "Personal config: $EXACTLEARNER_ENV"
else
  echo "Personal config: none at $EXACTLEARNER_ENV (cp scripts/experiment.env.example to create one)"
fi

# Sourced second, so a model's own settings beat personal preference. Set by
# scripts/submit.sh; a bare `sbatch run_experiment.sh` just skips it and falls
# back to MODEL_PATH and the defaults below.
if [[ -n "${EXACTLEARNER_MODEL_ENV:-}" && -f "$EXACTLEARNER_MODEL_ENV" ]]; then
  set +u; . "$EXACTLEARNER_MODEL_ENV"; set -u
  echo "Model config:    $EXACTLEARNER_MODEL_ENV"
fi
# Unconditional, not a fallback: a MODEL_PATH left in someone's personal config
# would otherwise outlive the model file and pair the wrong weights with a
# config the name check already passed.
if [[ -n "${MODEL_DIR:-}" ]]; then
  [[ -n "${MODEL_ROOT:-}" ]] || die "$EXACTLEARNER_MODEL_ENV sets MODEL_DIR but MODEL_ROOT is unset. Put it in $EXACTLEARNER_ENV."
  MODEL_PATH="$MODEL_ROOT/$MODEL_DIR"
fi

# All overridable from experiment.env or the sbatch line. Two are not free to
# retune: MAX_NEW_TOKENS, because a query that hits the cap silently flips
# True -> False (at a 512 cap the measured p95 was 493 -- watch at_cap, not
# truncated), and TENSOR_PARALLEL, which must equal the GPU count and divide the
# attention head count.
MODEL_PATH="${MODEL_PATH:-}"                          # no default; checked in preflight
MAX_NEW_TOKENS="${MAX_NEW_TOKENS:-1024}"              # DeepSeek-R1's <think> ignores num_predict
SERVER_READY_TIMEOUT="${SERVER_READY_TIMEOUT:-5400}"  # cold 32B load can exceed 30 min
HEARTBEAT_SECONDS="${HEARTBEAT_SECONDS:-30}"
TENSOR_PARALLEL="${TENSOR_PARALLEL:-4}"
MAX_MODEL_LEN="${MAX_MODEL_LEN:-4096}"                # KV cache cap; prompts are ~50 tokens
USER_SITE="${USER_SITE:-0}"                           # 1 re-enables ~/.local (holds a py3.11 accelerate)
PORT="${PORT:-11434}"

CONFIG="${1:?usage: sbatch scripts/run_experiment.sh <config> [name=value ...]}"
shift

# Everything after the config is name=value; see scripts/run_args.sh. Parsed
# before the seed and budget exports below, because seed=/pacseed=/budget= set
# exactly those variables and have to win over the defaults there.
#
# Sourcing this one is mandatory, so it cannot rely on $SCRIPT_DIR alone: Slurm
# may hand the compute node a copy of this script in a node-local spool
# directory, where scripts/ does not exist. submit.sh exports the login-node
# path, and SLURM_SUBMIT_DIR is the repo root for a bare sbatch, since these
# scripts must be submitted from there anyway.
RUN_ARGS_LIB="${RUN_ARGS_LIB:-$SCRIPT_DIR/run_args.sh}"
[[ -f "$RUN_ARGS_LIB" ]] || RUN_ARGS_LIB="${SLURM_SUBMIT_DIR:-$PWD}/scripts/run_args.sh"
[[ -f "$RUN_ARGS_LIB" ]] || die "cannot find run_args.sh (looked in $SCRIPT_DIR and ${SLURM_SUBMIT_DIR:-$PWD}/scripts). Submit from the repository root, or with scripts/submit.sh."
. "$RUN_ARGS_LIB"

# Already a real path when submit.sh resolved it; this is for a bare sbatch.
CONFIG="$(resolve_config "$CONFIG")"
parse_run_args "$@"
resolve_cache_path   # cache=fresh needs $SLURM_JOB_ID, which only exists here
LEARNER_ARGS=("$CONFIG" "$EPSILON" "$DELTA" ${LEARNER_FLAG_ARGS[@]+"${LEARNER_FLAG_ARGS[@]}"})


module purge
module load Java/21.0.8 

# Ignore ~/.local: a stray `pip install --user` there silently outranks the
# module-provided torch/transformers/vllm, for every run, invisibly.
[[ "$USER_SITE" == "1" ]] || export PYTHONNOUSERSITE=1


# A project-private module tree: readable only to members of that project.
# Override MODULE_TREE in your personal config if you are in a different one.
module use -a "${MODULE_TREE:-/fp/projects01/ec30/software/easybuild/modules/all/}"
module load nlpl-pytorch/2.6.0-foss-2024a-cuda-12.6.0-Python-3.12.3
module load nlpl-accelerate/1.9.0-foss-2024a-Python-3.12.3
module load Transformers/4.57.1-gfbf-2024a
module load nlpl-vllm/0.8.2-foss-2024a-Python-3.12.3

# Missing curl is silent and expensive: the readiness loop never matches and the
# job burns the full SERVER_READY_TIMEOUT beside a loaded model. Also the only
# thing standing between a PATH with no /usr/bin and a scattered failure later.
command -v curl >/dev/null 2>&1 ||
  die "'curl' is not on PATH after module load. Usually PATH has lost /usr/bin, because a ~/.bashrc that is a DIRECTORY is skipped by bash (ls -ld ~/.bashrc)."

# SLURM_SUBMIT_DIR is only meaningful inside a job. An Open OnDemand shell
# inherits a stale one -- the dashboard app's own directory, which is not even
# readable -- so honouring it outside a job cds somewhere unrelated. Under
# sbatch it is correct and is what makes `sbatch` work from anywhere.
if [[ -n "${SLURM_JOB_ID:-}" && -d "${SLURM_SUBMIT_DIR:-}" ]]; then
  cd "$SLURM_SUBMIT_DIR"
fi

# Everything below -- cp.txt, target/classes, the data_paclo paths in the config,
# results/ and statistics/ -- resolves relative to the repository root.
[[ -f pom.xml && -d src/main/java/org/experiments ]] ||
  die "this is not the ExactLearner-LLM repository root (pwd: $PWD). cd there and submit from it; paths in the config and the classpath are resolved relative to it."

mkdir -p logs results/ontologies statistics

# --- preflight: fail now, not hours later inside vLLM ------------------------
# Ordered cheapest and most portable first, so that
#   bash scripts/run_experiment.sh <config>
# on a login node validates paths, config and data before stopping at the first
# check that genuinely needs the GPUs.

[[ -f "$CONFIG" ]]      || die "no such config: $CONFIG"
[[ -f cp.txt ]]         || die "cp.txt missing. On a login node run: mvn -o dependency:build-classpath -Dmdep.outputFile=cp.txt"
[[ -d target/classes ]] || die "target/classes missing. Run: mvn -o -DskipTests compile"
[[ -n "$MODEL_PATH" ]]  || die "MODEL_PATH is not set. Copy scripts/experiment.env.example to scripts/experiment.env and set it there (or export it)."
[[ -d "$MODEL_PATH" ]]  || die "MODEL_PATH is not a directory: $MODEL_PATH"

# The ontology must sit beside initialOntology.owl and baseSet. If either is
# missing the learner silently falls back to uniform PAC sampling -- a different
# experiment, with no error. Datasets live in data_paclo/ (gitignored), read
# relative to the repository root, which is why submitting from there matters.
# Same `|| true` as in submit.sh: every config does name an ontology, so this is
# belt and braces, but a grep miss here would abort the job silently rather than
# reaching the "missing" die below that explains what to do.
ONTOLOGY=$(grep -A2 '^ontologies:' "$CONFIG" | grep -o '"[^"]*"' | head -1 | tr -d '"') || true
ONTOLOGY_DIR=$(dirname "$ONTOLOGY")
for required in "$ONTOLOGY" "$ONTOLOGY_DIR/initialOntology.owl" "$ONTOLOGY_DIR/baseSet"; do
  [[ -e "$required" ]] || die "missing (or broken symlink): $required. Copy the dataset folder into data_paclo/ -- it is deliberately not in the repository."
done
echo "Config: $CONFIG"
echo "Ontology: $ONTOLOGY"
echo "Data dir: $ONTOLOGY_DIR"
echo 

# --- from here down, everything needs the GPUs -------------------------------
command -v nvidia-smi >/dev/null 2>&1 ||
  die "'nvidia-smi' is not on PATH. Expected on a login node: everything above this line has passed, so submit the job for the rest."

[[ "${SLURM_JOB_NUM_NODES:-1}" == "1" ]] ||
  die "allocation spans ${SLURM_JOB_NUM_NODES} nodes. vLLM's mp executor sees only the local node, so TP=$TENSOR_PARALLEL would exceed the visible GPU count, fall back to Ray, and hang. Use --nodes=1."

n_gpus=$(nvidia-smi --query-gpu=index --format=csv,noheader 2>/dev/null | wc -l)
[[ "$n_gpus" -ge "$TENSOR_PARALLEL" ]] ||
  die "tensor-parallel size is $TENSOR_PARALLEL but only $n_gpus GPU(s) visible. vLLM would fall back to Ray and wait forever on a cluster that was never started."

# log python, torch, transformers, vllm versions
python3 -c '
import sys, torch, transformers, vllm
print(f"\npython {sys.version.split()[0]} | torch {torch.__version__} | transformers {transformers.__version__} | vllm {vllm.__version__}")
assert torch.cuda.is_available(), "torch reports no CUDA: a CPU-only build, or no GPU allocated"
' || die "Python environment unusable -- see above"

# --- model server ------------------------------------------------------------
export EXACTLEARNER_OLLAMA_URL="http://localhost:${PORT}/api/generate"

# Batching, all default ON here rather than on the sbatch line: a job once burned
# 24 h on the sequential path because the flags were unset and the only symptom
# was a MISSING log line. Transport only -- same questions, same cache keys.
# BATCH_UNSATURATE is the one to watch: it batches unsaturateLeft/saturateRight,
# which are only conditionally independent, so if "speculation rounds=/restarts="
# shows restarts nearing rounds the speculation is being thrown away, set false.
export EXACTLEARNER_BATCH_SIZE="${EXACTLEARNER_BATCH_SIZE:-16}"              # 0 disables; 6.9x measured
export EXACTLEARNER_BATCH_DECOMPOSE="${EXACTLEARNER_BATCH_DECOMPOSE:-true}"  # decompose() signature scans
export EXACTLEARNER_BATCH_UNSATURATE="${EXACTLEARNER_BATCH_UNSATURATE:-true}"

# ELK query-state unlocking, default ON. ELK registers a QueryState per
# isEntailed() and its own evictor can never reclaim one, because the guard is
# isLocked() and ElkReasoner.isEntailed() locks without unlocking. So the evictor
# rescans a growing candidate set on every query: JFR on job 4059565 put 86% of
# the learner's Java CPU in that scan and ~50 GB of live heap in the retained
# states. Unlocking after the answer is read lets ELK's own RecencyEvictor work.
# Measured locally over 20k queries via org.experiments.TestEntailmentQueryEvictor:
# per-query cost flat instead of climbing 5x, 2.85x less total time, heap flat.
# Correctness is unaffected -- an evicted query is only recomputed if re-asked,
# and decompose()'s queries are novel by construction. Set false to compare.
# Reaches ELK 0.6.0 internals by reflection and self-disables if they move; the
# "ELK query-state unlocking ON/OFF" line on stdout is the proof it took.
export EXACTLEARNER_ELK_UNLOCK="${EXACTLEARNER_ELK_UNLOCK:-true}"
export EXACTLEARNER_ELK_UNLOCK_INTERVAL="${EXACTLEARNER_ELK_UNLOCK_INTERVAL:-2000}"

# Default OFF, set with `resume=true`. It changes what a run *is*, not just its
# speed -- the numbers then come from several jobs -- so it stays opt-in until
# that is agreed, but C2 cannot finish without it: a 24 h job that starts with C
# counterexamples banked in the cache can add only (1440 - 4.5C)/7.9 more, which
# reaches zero at C = 320 against the ~381 a finished run needs.
#
# Set, the job continues from the previous one's checkpointed hypothesis, PAC
# counter, sampler position and metrics totals -- all in results/ontologies/,
# which is therefore NOT safe to wipe between jobs of the same run.
export EXACTLEARNER_RESUME="${EXACTLEARNER_RESUME:-false}"

# The model name the learner files its cache and results under. Exported from the
# same model file that supplies the weights, so the two cannot drift apart -- the
# name does NOT choose the weights (llm_server.py serves whatever MODEL_PATH it was
# started with), it only decides which cache rows are read and what the output is
# called. With this set a config need not name a model at all, which is what lets
# one config serve every model.
export EXACTLEARNER_MODEL="${EXACTLEARNER_MODEL:-${MODEL_NAME:-}}"
[[ -n "$EXACTLEARNER_MODEL" ]] ||
  die "MODEL_NAME is not set. It comes from scripts/models/<model>.env, so submit with
       scripts/submit.sh <model> <config>, or export EXACTLEARNER_MODEL yourself."

# Seeds. Both default to 0, which is what every run so far used, so leaving them
# alone reproduces previous runs exactly. Vary them to get an independent repeat
# of the same experiment -- a single run is one draw from a random process, which
# matters most when comparing the two arms on one dataset. The two streams are
# independent: SAMPLER_SEED drives A-induced, PAC_SEED the uniform sampler.
export EXACTLEARNER_SAMPLER_SEED="${EXACTLEARNER_SAMPLER_SEED:-0}"
export EXACTLEARNER_PAC_SEED="${EXACTLEARNER_PAC_SEED:-0}"

# Which reading of the PAC sampling budget the loop enforces. "global" (the
# default, and what every run so far has used) spends one pot of numberOfSamples
# candidates across the WHOLE run, so termination is guaranteed but the final
# hypothesis is certified only by whatever budget was left after the last
# counterexample. "per-round" gives each equivalence query a fresh full budget,
# which is the standard reading of the (epsilon, delta) guarantee, but stops only
# once a full budget has failed against the hypothesis as it then stands -- which
# may not happen before walltime. The two are NOT comparable; do not mix modes
# within an experiment. See MEETING-2026-08-18.md section 8.
export EXACTLEARNER_BUDGET_MODE="${EXACTLEARNER_BUDGET_MODE:-global}"

echo 
# One job is always one repeat. repeats=N is submit.sh's business -- it fires N of
# these -- so say so rather than letting the name imply this job does all N.
if [[ "${REPEATS:-1}" -gt 1 ]]; then
  warn "repeats=$REPEATS is handled by submit.sh, which submits that many jobs.
         This job runs ONE repeat, with the seeds below."
fi

# Names this repeat's outputs; empty for an ordinary single run, which then keeps
# the filenames it has always had.
export EXACTLEARNER_RUN_TAG="${EXACTLEARNER_RUN_TAG:-}"

echo "Model: $EXACTLEARNER_MODEL (weights: $MODEL_PATH)"
if [[ -n "$EXACTLEARNER_RUN_TAG" ]]; then
  echo "Run tag: $EXACTLEARNER_RUN_TAG (outputs carry this suffix)"
fi
echo "Run parameters: $RUN_ARGS_SUMMARY"
echo "Batching: size=$EXACTLEARNER_BATCH_SIZE decompose=$EXACTLEARNER_BATCH_DECOMPOSE unsaturate=$EXACTLEARNER_BATCH_UNSATURATE | resume=$EXACTLEARNER_RESUME"
echo "Sampling budget: $EXACTLEARNER_BUDGET_MODE"
echo "Seeds: sampler=$EXACTLEARNER_SAMPLER_SEED pac=$EXACTLEARNER_PAC_SEED"
echo "ELK unlock: $EXACTLEARNER_ELK_UNLOCK every $EXACTLEARNER_ELK_UNLOCK_INTERVAL queries"

# Educloud sets http_proxy and curl honours it even for localhost, so probing
# our own server returns a Squid "Access Denied" page, the readiness loop never
# matches, and the job times out beside a loaded model. The curl calls also pass
# --noproxy, since the variable's casing is not consistently honoured. Java is
# unaffected.
export no_proxy="localhost,127.0.0.1,::1,${no_proxy:-}"
export NO_PROXY="$no_proxy"

SERVER_ARGS=(--tensor-parallel-size "$TENSOR_PARALLEL"
             --max-model-len "$MAX_MODEL_LEN")
# Default ON here: A100-PCIE with no NVLink, so the custom all-reduce kernel
# buys little while its P2P probe can spin forever. 0 restores the kernel.
[[ "${DISABLE_CUSTOM_ALL_REDUCE:-1}" == "1" ]] &&
  SERVER_ARGS+=(--disable-custom-all-reduce)
[[ "${ENFORCE_EAGER:-0}" == "1" ]] && SERVER_ARGS+=(--enforce-eager)

# The server must NOT run from the repo root: vLLM's `python3 -m` subprocesses
# put CWD first on sys.path, so a repo directory can shadow a stdlib module --
# statistics/ did, surfacing as "Model architectures ['Qwen2ForCausalLM'] failed
# to be inspected". Cheap insurance. The Java process still runs from the root.
REPO_DIR="$PWD"

# One directory per config and model, so a batch of repeats does not bury the
# folder. submit.sh sets and creates it; a bare sbatch keeps the flat logs/.
LOG_DIR="${EXACTLEARNER_LOG_DIR:-logs}"
mkdir -p "$LOG_DIR"
SERVER_CWD="${SERVER_CWD:-${SCRATCH:-/tmp}/exactlearner-server-${SLURM_JOB_ID:-$$}}"
mkdir -p "$SERVER_CWD"

# Absolute, because the server's CWD is SERVER_CWD rather than the repo root:
# TRACE_FILE is the full reasoning traces as JSONL, STATUS_FILE the machine
# readable server state rewritten every heartbeat (on the shared filesystem,
# since a login shell cannot reach the compute node's HTTP port). Override with
# an absolute path, or empty to disable either.
# ${VAR-...} not ${VAR:-...}: only an UNSET variable takes the default, so
# setting either to the empty string disables it.
TRACE_FILE="${TRACE_FILE-$REPO_DIR/$LOG_DIR/traces-${SLURM_JOB_ID:-local}.jsonl}"
STATUS_FILE="${STATUS_FILE-$REPO_DIR/$LOG_DIR/server-status-${SLURM_JOB_ID:-local}.json}"

echo "Server CWD: $SERVER_CWD"
[[ -n "$STATUS_FILE" ]] && echo "Status:     $STATUS_FILE"
echo

# TP workers must not fork from a process holding a CUDA context. llm_server.py
# sets this too; here it also covers manual invocations of the server.
export VLLM_WORKER_MULTIPROC_METHOD=spawn

# REQUIRED: PCIe P2P is broken here and nothing detects it -- canAccessPeer
# returns true, NCCL reports "Init COMPLETE", then the first all-reduce spins at
# 100% forever with no error or timeout. Measured: NCCL_P2P_LEVEL=PHB also hangs,
# so only disabling outright works. Setting this to 0 reintroduces a silent,
# unrecoverable hang.
export NCCL_P2P_DISABLE="${NCCL_P2P_DISABLE:-1}"

# setsid gives the server its own process group so the trap can take its TP
# workers down with it -- killing the parent alone leaves orphans holding GPU
# memory and the next job fails to allocate for no visible reason. exec keeps $!
# on the python process rather than a wrapper.
LAUNCH=(python3 "$REPO_DIR/scripts/llm_server.py"
        --model "$MODEL_PATH"
        --port "$PORT"
        --max-new-tokens "$MAX_NEW_TOKENS"
        --heartbeat-seconds "$HEARTBEAT_SECONDS"
        "${SERVER_ARGS[@]}")
[[ -n "$TRACE_FILE"  ]] && LAUNCH+=(--trace-file  "$TRACE_FILE")
[[ -n "$STATUS_FILE" ]] && LAUNCH+=(--status-file "$STATUS_FILE")
if command -v setsid >/dev/null 2>&1; then
  ( cd "$SERVER_CWD" && exec setsid "${LAUNCH[@]}" ) &
else
  ( cd "$SERVER_CWD" && exec "${LAUNCH[@]}" ) &
fi
SERVER_PID=$!

# $! is the python PID either way: a background subshell here is not a process
# group leader, so setsid execs in place rather than forking a wrapper.
cleanup_server() {
  # Negative PID targets the process group (== server PID, via setsid), reaching
  # vLLM's workers. Falls back to the bare PID when setsid was unavailable.
  kill -TERM -"$SERVER_PID" 2>/dev/null ||
    kill -TERM "$SERVER_PID" 2>/dev/null || true
}
# TERM/INT as well as EXIT: Slurm sends SIGTERM at walltime and bash runs no EXIT
# trap for an untrapped fatal signal, leaving workers holding all four GPUs.
trap cleanup_server EXIT TERM INT

# --- wait until it answers, rather than sleeping and hoping ------------------
#   process gone             -> fail now, do not wait out the budget
#   loading, phase advancing -> keep waiting, and say what it is doing
#   budget exhausted         -> dump thread stacks before killing it
READY=0
START=$(date +%s)
DEADLINE=$(( START + SERVER_READY_TIMEOUT ))
LAST_PHASE=""; LAST_REPORT=0
PROBE='{"system":"Answer with only True or False.","options":{"num_predict":2},"stream":false,"prompt":"Is a dog an animal?"}'

while [[ $(date +%s) -lt $DEADLINE ]]; do
  kill -0 "$SERVER_PID" 2>/dev/null ||
    die "server died during startup; its traceback is above (last phase: ${LAST_PHASE:-unknown})"

  # /health binds before the model loads and never takes the generation lock, so
  # it answers in milliseconds at every stage, reporting a phase.
  HEALTH=$(curl -s -m 10 --noproxy '*' "http://localhost:${PORT}/health" 2>/dev/null || true)

  if [[ "$HEALTH" == *'"ready":true'* ]]; then
    # Loaded is not the same as usable: drive the full path exactly as the Java
    # client will, to catch a model whose answers do not parse.
    RESPONSE=$(curl -s -m 300 --noproxy '*' -X POST "http://localhost:${PORT}/api/generate" \
                 -H 'Content-Type: application/json' -d "$PROBE" 2>/dev/null || true)
    [[ "$RESPONSE" == *'"response":"'* ]] ||
      die "server reports ready but the probe did not parse: $RESPONSE"
    echo "Server ready after $(( $(date +%s) - START ))s. Probe: $RESPONSE"
    READY=1; break
  fi

  # One line per phase change, otherwise every 5 min. An empty HEALTH means the
  # process is up but not listening yet, which is normal only for a few seconds.
  PHASE=$(sed -n 's/.*"phase"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' <<<"$HEALTH")
  NOW=$(date +%s)
  if [[ "$PHASE" != "$LAST_PHASE" || $(( NOW - LAST_REPORT )) -ge 300 ]]; then
    echo "  [$(( NOW - START ))s] server phase: ${PHASE:-not answering yet}"
    LAST_PHASE="$PHASE"; LAST_REPORT=$NOW
  fi
  sleep 15
done

# SIGUSR1 is wired to faulthandler in llm_server.py: the stacks separate "slow"
# from "deadlocked", and the NCCL P2P hang is otherwise completely silent.
[[ "$READY" == 1 ]] || {
  kill -USR1 "$SERVER_PID" 2>/dev/null || true
  sleep 5
  die "server not ready within ${SERVER_READY_TIMEOUT}s (last phase: ${LAST_PHASE:-unknown}). Stacks above: if they show progress, raise SERVER_READY_TIMEOUT or set ENFORCE_EAGER=1 to skip torch.compile."
}

# --- run ---------------------------------------------------------------------
# Heap and instrumentation. 64g because a run OOMed at -Xmx16g under --mem=128G
# after 23.6 h -- vLLM's host side is ~50 GiB, so most of the allocation was
# unusable by the learner.
#
# JFR and the verbose GC log were always-on while "where does the run's time go"
# was open. It is not any more: JFR put 86% of Java CPU in the ELK evictor scan
# (fixed by the unlocking above) and the rest in decompose(), and the GC log put
# collection at ~1.6% of wall clock, ruling it out. Both are opt-in now -- see
# JFR=1 below and GC_LOG_DETAIL for the verbose form.
JAVA_HEAP="${JAVA_HEAP:-64g}"
GC_LOG="${GC_LOG:-$LOG_DIR/gc-${SLURM_JOB_NAME:-exactlearner}-${SLURM_JOB_ID:-$$}.log}"
JFR_FILE="${JFR_FILE:-$LOG_DIR/jfr-${SLURM_JOB_NAME:-exactlearner}-${SLURM_JOB_ID:-$$}.jfr}"
JFR_REPO="${JFR_REPO:-$LOG_DIR/jfr-repo-${SLURM_JOB_ID:-$$}}"
mkdir -p "$(dirname "$GC_LOG")"

# ParallelGCThreads: the 8 cores are shared with the model server and G1 sizes
# its workers from the core count, so a 64g heap can stall every core in a pause.
#
# The GC log stays on, because it is what would show the -Xmx16g OOM coming back
# and it costs a line per collection. What went is gc+heap=debug and
# filecount=0: a per-GC heap breakdown into one file that grows all run. One
# line per GC, rotated, is enough to see the floor walk upwards. GC_LOG_DETAIL=1
# restores the old verbose form for a run meant to look at the heap.
if [[ "${GC_LOG_DETAIL:-0}" == "1" ]]; then
  GC_LOG_OPT="-Xlog:gc*,gc+heap=debug:file=${GC_LOG}:time,uptime:filecount=0"
else
  GC_LOG_OPT="-Xlog:gc:file=${GC_LOG}:time,uptime:filecount=5,filesize=20m"
fi

JAVA_OPTS=(-Xmx"$JAVA_HEAP"
           "$GC_LOG_OPT"
           -XX:ParallelGCThreads="${PARALLEL_GC_THREADS:-4}")

# Opt-in: the dump can be the full heap landing on scratch at once, so this is
# for a run meant to catch the leak, not one meant to make progress.
if [[ "${JAVA_HEAP_DUMP:-0}" == "1" ]]; then
  HEAP_DUMP_DIR="${HEAP_DUMP_DIR:-${SCRATCH:-/tmp}}"
  mkdir -p "$HEAP_DUMP_DIR"
  JAVA_OPTS+=(-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath="$HEAP_DUMP_DIR")
  echo "Heap dump on OOM: $HEAP_DUMP_DIR (up to $JAVA_HEAP)"
fi

# Opt-in since 2026-08-27: settings=profile samples continuously and leaves up to
# JFR_MAXSIZE of .jfr plus a jfr-repo-<jobid>/ directory per job, and nothing
# reads them now that decompose() is the known bottleneck. JFR=1 for a run whose
# point is to profile:
#   jfr summary logs/jfr-<job>.jfr
#   jfr print --events ExecutionSample logs/jfr-<job>.jfr | head -100
# disk=true plus an explicit repository is deliberate -- walltime SIGKILL means
# dumponexit never fires, but completed chunks are already on disk.
if [[ "${JFR:-0}" == "1" ]]; then
  mkdir -p "$(dirname "$JFR_FILE")" "$JFR_REPO"
  JAVA_OPTS+=("-XX:StartFlightRecording=settings=profile,disk=true,dumponexit=true,maxsize=${JFR_MAXSIZE:-2g},filename=${JFR_FILE}"
              "-XX:FlightRecorderOptions=repository=${JFR_REPO}")
fi

echo "JVM: heap=$JAVA_HEAP gc-log=$GC_LOG detail=${GC_LOG_DETAIL:-0} jfr=${JFR:-0}"

# Plain java, not `mvn exec:java`: exec-maven-plugin is not in pom.xml, so Maven
# would try to fetch it and fail on a compute node with no network.
echo "Starting learner at $(date)"
java "${JAVA_OPTS[@]}" -cp "target/classes:$(cat cp.txt)" \
  org.experiments.LaunchLLMLearnerAInduced "${LEARNER_ARGS[@]}"

echo "Finished at $(date)"
