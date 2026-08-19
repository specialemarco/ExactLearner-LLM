#!/bin/bash
# ExactLearner-LLM on Slurm: model server + learner in one job.
#
#   scripts/submit.sh <config.yml> [epsilon] [delta]
#
# Run from the repository root. Once per machine:
#   cp scripts/experiment.env.example scripts/experiment.env   # then edit it
#   module purge; module load Java/21.0.8 Maven/3.6.3
#   mvn -o -DskipTests compile
#   mvn -o dependency:build-classpath -Dmdep.outputFile=cp.txt
# experiment.env and cp.txt are gitignored; git pull will not bring them.
#
# Overridden by SBATCH_ARGS in experiment.env, via scripts/submit.sh.
#SBATCH --account=ec30
#SBATCH --job-name=exactlearner
#SBATCH --partition=accel
# --nodes=1 and the a100: prefix are load-bearing: a bare --gpus=4 can split
# across nodes and hang vLLM, and accel's H100s have no kernel image for it.
#SBATCH --nodes=1
#SBATCH --gpus-per-node=a100:4
#SBATCH --cpus-per-task=8
#SBATCH --mem=128G
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

# Model weights. No default: differs per user, and a wrong guess wastes an
# allocation. Checked in preflight.
MODEL_PATH="${MODEL_PATH:-}"

# DeepSeek-R1 emits <think> before answering, so the client's num_predict:2 is
# ignored. Do NOT lower to buy speed: at 512 the p95 was 493 and queries hitting
# the cap silently flipped True -> False. Watch at_cap, not truncated.
MAX_NEW_TOKENS="${MAX_NEW_TOKENS:-1024}"

# Load budget only; a dead server is caught immediately either way. Cold 32B
# startup across 4 ranks can exceed 30 min (~/.cache/vllm speeds up later ones).
SERVER_READY_TIMEOUT="${SERVER_READY_TIMEOUT:-5400}"

# Server progress-line interval, in seconds.
HEARTBEAT_SECONDS="${HEARTBEAT_SECONDS:-30}"

# Must divide the attention head count (power of two) and equal --gpus above.
TENSOR_PARALLEL="${TENSOR_PARALLEL:-4}"

# Caps the KV cache reservation. Prompts are ~50 tokens plus the reasoning
# budget, so the model's full context would waste most of the GPU memory.
MAX_MODEL_LEN="${MAX_MODEL_LEN:-4096}"

# ~/.local excluded deliberately: it holds an accelerate 0.29.3 built for
# Python 3.11. Set to 1 only if you add a --user package this stack needs.
USER_SITE="${USER_SITE:-0}"
# ----------------------------------------------------------------------------

PORT="${PORT:-11434}"

CONFIG="${1:?usage: sbatch scripts/run_experiment.sh <config.yml> [epsilon] [delta]}"
EPSILON="${2:-0.2}"
DELTA="${3:-0.1}"


module purge
module load Java/21.0.8 

if [[ "$USER_SITE" != "1" ]]; then
  export PYTHONNOUSERSITE=1
fi

# Project-local EasyBuild tree, newer than the cluster-wide one (PyTorch 2.1.2 /
# foss-2023a). Change this if your project has its own.
module use -a /fp/projects01/ec30/software/easybuild/modules/all/

# All foss-2024a / Python 3.12.3: one site-packages generation. Do NOT mix in
# the cluster-wide PyTorch/2.1.2-foss-2023a (Python 3.11) -- mutually invisible.
module load nlpl-pytorch/2.6.0-foss-2024a-cuda-12.6.0-Python-3.12.3
module load nlpl-accelerate/1.9.0-foss-2024a-Python-3.12.3
module load Transformers/4.57.1-gfbf-2024a
module load nlpl-vllm/0.8.2-foss-2024a-Python-3.12.3

# `module purge` can leave PATH with only EasyBuild dirs and no /usr/bin, which
# fails in scattered ways (nvidia-smi, tee, ps all vanish). Appending is safe:
# module directories keep priority.
for d in /usr/bin /bin /usr/sbin /sbin; do
  [[ -d "$d" && ":$PATH:" != *":$d:"* ]] && PATH="$PATH:$d"
done
export PATH
# Only curl. It comes from /usr/bin, which is what the PATH repair above exists
# to restore, and its absence is expensive but silent: the readiness loop never
# matches and the job burns the whole SERVER_READY_TIMEOUT beside a fully loaded
# model. java and python3 were checked here too until Lmod was measured
# returning 1 for an unknown module -- so `set -e` already aborts on a failed
# module load, long before this line. nvidia-smi is checked with the GPU block.
command -v curl >/dev/null 2>&1 ||
  die "'curl' is not on PATH after module load. Check that ~/.bashrc is a FILE (ls -ld ~/.bashrc)."

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
# experiment, with no error. Paths are relative to the repository root, which is
# why this script insists on being submitted from there.
ONTOLOGY=$(grep -A2 '^ontologies:' "$CONFIG" | grep -o '"[^"]*"' | head -1 | tr -d '"')
ONTOLOGY_DIR=$(dirname "$ONTOLOGY")
for required in "$ONTOLOGY" "$ONTOLOGY_DIR/initialOntology.owl" "$ONTOLOGY_DIR/baseSet"; do
  [[ -e "$required" ]] || die "missing (or broken symlink): $required"
done
echo "Config: $CONFIG | ontology: $ONTOLOGY | data dir: $ONTOLOGY_DIR"

# --- from here down, everything needs the GPUs -------------------------------
command -v nvidia-smi >/dev/null 2>&1 ||
  die "'nvidia-smi' is not on PATH. Expected on a login node: everything above this line has passed, so submit the job for the rest."

[[ "${SLURM_JOB_NUM_NODES:-1}" == "1" ]] ||
  die "allocation spans ${SLURM_JOB_NUM_NODES} nodes. vLLM's mp executor sees only the local node, so TP=$TENSOR_PARALLEL would exceed the visible GPU count, fall back to Ray, and hang. Use --nodes=1."

n_gpus=$(nvidia-smi --query-gpu=index --format=csv,noheader 2>/dev/null | wc -l)
[[ "$n_gpus" -ge "$TENSOR_PARALLEL" ]] ||
  die "tensor-parallel size is $TENSOR_PARALLEL but only $n_gpus GPU(s) visible. vLLM would fall back to Ray and wait forever on a cluster that was never started."

# Check the Python environment before the model load: seconds, not minutes.
python3 - <<'PYCHECK' || die "Python environment incomplete"
import sys
# HTTP is stdlib; these are the only third-party requirements.
missing = []
for mod in ("torch", "transformers", "vllm"):
    try:
        __import__(mod)
    except ImportError as e:
        missing.append(f"{mod} ({e})")
if missing:
    sys.exit("Missing Python packages:\n  " + "\n  ".join(missing) +
             "\nLoad the matching module on a LOGIN node, or pip install --user.")
import torch, transformers, vllm
print(f"python {sys.version.split()[0]} | torch {torch.__version__} | "
      f"transformers {transformers.__version__} | vllm {vllm.__version__}")
print(f"CUDA available: {torch.cuda.is_available()} | GPUs: {torch.cuda.device_count()}")
# CPU-only torch would not crash, just run ~100x slower. Refuse to start.
if not torch.cuda.is_available():
    sys.exit("torch reports no CUDA: a CPU-only PyTorch build, or no GPU allocated.")
PYCHECK

# --- model server ------------------------------------------------------------
export EXACTLEARNER_OLLAMA_URL="http://localhost:${PORT}/api/generate"

# Batches the 17,030 independent precomputation queries: measured 6.9x on
# 4xA100/8 cores, taking precomputation from ~69 h to ~10 h. Transport only --
# same questions, same cache keys. 0 disables.
export EXACTLEARNER_BATCH_SIZE="${EXACTLEARNER_BATCH_SIZE:-16}"

# The learner's own sweeps. Default ON here, not on the sbatch line: a job once
# burned 24 h on the sequential path because neither flag was set and the only
# symptom was a MISSING log line. Overrides still work.
#
#   DECOMPOSE  - decompose()'s signature scans; unconditionally independent, so
#                only *when* answers are fetched changes.
#   UNSATURATE - also unsaturateLeft/saturateRight, where the bottleneck now
#                sits. Only conditionally independent: watch "speculation
#                rounds=/restarts=" -- restarts nearing rounds means the
#                speculation is being discarded, so set this false.
export EXACTLEARNER_BATCH_DECOMPOSE="${EXACTLEARNER_BATCH_DECOMPOSE:-true}"
export EXACTLEARNER_BATCH_UNSATURATE="${EXACTLEARNER_BATCH_UNSATURATE:-true}"

echo "Batching: size=$EXACTLEARNER_BATCH_SIZE decompose=$EXACTLEARNER_BATCH_DECOMPOSE unsaturate=$EXACTLEARNER_BATCH_UNSATURATE"

# DRAFT, default OFF: never run on the cluster, and it changes what a run *is*,
# not just its speed. Set, a job resumes from the previous one's checkpointed
# hypothesis and sample position; unset, loadHypothesisOntology() truncates.
# Every job so far has discarded its hypothesis at the walltime, so consecutive
# jobs repeat rather than accumulate. Discuss with Baris before defaulting it on.
export EXACTLEARNER_RESUME="${EXACTLEARNER_RESUME:-false}"
echo "Resume: $EXACTLEARNER_RESUME"

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
SERVER_CWD="${SERVER_CWD:-${SCRATCH:-/tmp}/exactlearner-server-${SLURM_JOB_ID:-$$}}"
mkdir -p "$SERVER_CWD"

# Absolute, because the server's CWD is SERVER_CWD rather than the repo root:
# TRACE_FILE is the full reasoning traces as JSONL, STATUS_FILE the machine
# readable server state rewritten every heartbeat (on the shared filesystem,
# since a login shell cannot reach the compute node's HTTP port). Override with
# an absolute path, or empty to disable either.
# ${VAR-...} not ${VAR:-...}: only an UNSET variable takes the default, so
# setting either to the empty string disables it.
TRACE_FILE="${TRACE_FILE-$REPO_DIR/logs/traces-${SLURM_JOB_ID:-local}.jsonl}"
STATUS_FILE="${STATUS_FILE-$REPO_DIR/logs/server-status-${SLURM_JOB_ID:-local}.json}"

echo "Server CWD: $SERVER_CWD"
[[ -n "$STATUS_FILE" ]] && echo "Status:     $STATUS_FILE"
echo
echo "To watch this job from a login shell:"
echo "  squeue -u \$USER"
[[ -n "$STATUS_FILE" ]] && echo "  cat $STATUS_FILE   # rewritten every heartbeat; stale file = dead process"
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
DEADLINE=$(( $(date +%s) + SERVER_READY_TIMEOUT ))
START_WAIT=$(date +%s)
LAST_PHASE=""
LAST_REPORT=0
PROBE='{"system":"Answer with only True or False.","options":{"num_predict":2},"stream":false,"prompt":"Is a dog an animal?"}'

while [[ $(date +%s) -lt $DEADLINE ]]; do
  kill -0 "$SERVER_PID" 2>/dev/null || {
    echo "ERROR: server process died during startup. Its traceback is above; \
look for the last 'phase:' line to see how far it got." >&2; exit 1; }

  # /health binds before the model loads and never takes the generation lock,
  # so it answers in milliseconds at every stage.
  HEALTH=$(curl -s -m 10 --noproxy '*' "http://localhost:${PORT}/health" 2>/dev/null || true)

  if [[ "$HEALTH" == *'"ready":true'* ]]; then
    # Prove the full path end to end, as the Java client drives it: catches a
    # model that loads fine but whose answers do not parse.
    RESPONSE=$(curl -s -m 300 --noproxy '*' -X POST "http://localhost:${PORT}/api/generate" \
                 -H 'Content-Type: application/json' -d "$PROBE" 2>/dev/null || true)
    if [[ "$RESPONSE" == *'"response":"'* ]]; then
      echo "Server ready after $(( $(date +%s) - START_WAIT ))s. Probe: $RESPONSE"
      READY=1
      break
    fi
    echo "ERROR: server reports ready but the probe did not parse: $RESPONSE" >&2
    exit 1
  fi

  NOW=$(date +%s)
  if [[ -n "$HEALTH" ]]; then
    PHASE=$(sed -n 's/.*"phase"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' <<<"$HEALTH")
    # Every phase change, else every 5 min: informative without a wall of text.
    if [[ "$PHASE" != "$LAST_PHASE" || $(( NOW - LAST_REPORT )) -ge 300 ]]; then
      echo "  [$(( NOW - START_WAIT ))s] server phase: ${PHASE:-unknown}"
      LAST_PHASE="$PHASE"; LAST_REPORT=$NOW
    fi
  elif [[ $(( NOW - LAST_REPORT )) -ge 300 ]]; then
    # Alive but not listening: normal for a few seconds, suspicious after.
    echo "  [$(( NOW - START_WAIT ))s] server up but /health not answering yet"
    LAST_REPORT=$NOW
  fi
  sleep 15
done

if [[ $READY -ne 1 ]]; then
  echo "ERROR: server did not become ready within ${SERVER_READY_TIMEOUT}s \
(last phase: ${LAST_PHASE:-unknown}). Dumping thread stacks before exit." >&2
  # SIGUSR1 -> faulthandler in llm_server.py: shows where every thread is,
  # which distinguishes "slow" from "deadlocked".
  kill -USR1 "$SERVER_PID" 2>/dev/null || true
  sleep 5
  echo "Raise SERVER_READY_TIMEOUT, or pass --enforce-eager to skip \
torch.compile, if the stacks show it was still making progress." >&2
  exit 1
fi

# --- run ---------------------------------------------------------------------
# A run once OOMed at -Xmx16g under --mem=128G after 23.6 h: vLLM's host side is
# ~50 GiB, so ~45 GiB of the allocation went unused. 64g leaves ~14 GiB slack.
JAVA_HEAP="${JAVA_HEAP:-64g}"

# GC logging, always on: a few MB over 24 h, and the only way to tell the two
# OOM explanations apart -- a merely-small heap levels off after each full GC,
# a leak walks the post-collection floor upwards over the run.
GC_LOG="${GC_LOG:-logs/gc-${SLURM_JOB_NAME:-exactlearner}-${SLURM_JOB_ID:-$$}.log}"
mkdir -p "$(dirname "$GC_LOG")"

JAVA_OPTS=(-Xmx"$JAVA_HEAP"
           "-Xlog:gc*,gc+heap=debug:file=${GC_LOG}:time,uptime:filecount=0")

# Opt-in: the dump can be the full heap (64 GiB) landing on scratch at once.
# For a run meant to catch the leak, not one meant to make progress.
if [[ "${JAVA_HEAP_DUMP:-0}" == "1" ]]; then
  HEAP_DUMP_DIR="${HEAP_DUMP_DIR:-${SCRATCH:-/tmp}}"
  mkdir -p "$HEAP_DUMP_DIR"
  JAVA_OPTS+=(-XX:+HeapDumpOnOutOfMemoryError
              -XX:HeapDumpPath="$HEAP_DUMP_DIR")
  echo "Heap dump on OOM: $HEAP_DUMP_DIR (up to $JAVA_HEAP)"
fi

# Flight Recorder, on by default (JFR=0 disables). Runs hit benchmark tok/s with
# GPUs idle ~72% of the wall clock, so they are bound by something Java-side that
# has never been profiled; the GC log says whether it is collection, this says
# what else. disk=true plus an explicit repository is deliberate: walltime SIGKILL
# means dumponexit never fires, but completed chunks are already on disk.
#
#   jfr summary logs/jfr-<job>.jfr
#   jfr print --events ExecutionSample logs/jfr-<job>.jfr | head -100
# or open it in JDK Mission Control for the flame graph.
if [[ "${JFR:-1}" == "1" ]]; then
  JFR_FILE="${JFR_FILE:-logs/jfr-${SLURM_JOB_NAME:-exactlearner}-${SLURM_JOB_ID:-$$}.jfr}"
  JFR_REPO="${JFR_REPO:-logs/jfr-repo-${SLURM_JOB_ID:-$$}}"
  mkdir -p "$(dirname "$JFR_FILE")" "$JFR_REPO"
  JAVA_OPTS+=("-XX:StartFlightRecording=settings=profile,disk=true,dumponexit=true,maxsize=${JFR_MAXSIZE:-2g},filename=${JFR_FILE}"
              "-XX:FlightRecorderOptions=repository=${JFR_REPO}")
  echo "JFR: $JFR_FILE (repository $JFR_REPO, maxsize ${JFR_MAXSIZE:-2g})"
else
  echo "JFR: off"
fi

# The 8 cores are shared with the model server, and G1 sizes its workers from the
# core count, so a heap this size can stall every core in a collection pause.
# ELK's pool is unaffected: it reads availableProcessors().
JAVA_OPTS+=(-XX:ParallelGCThreads="${PARALLEL_GC_THREADS:-4}")

echo "JVM: heap=$JAVA_HEAP gc-log=$GC_LOG"

# Plain java, not `mvn exec:java`: exec-maven-plugin is not in pom.xml, so Maven
# would try to fetch it and fail on a compute node with no network.
echo "Starting learner at $(date)"
java "${JAVA_OPTS[@]}" -cp "target/classes:$(cat cp.txt)" \
  org.experiments.LaunchLLMLearnerAInduced "$CONFIG" "$EPSILON" "$DELTA"

echo "Finished at $(date)"
