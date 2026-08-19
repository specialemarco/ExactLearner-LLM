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

CONFIG="${1:?usage: sbatch scripts/run_experiment.sh <config.yml> [epsilon] [delta]}"
EPSILON="${2:-0.2}"
DELTA="${3:-0.1}"


module purge
module load Java/21.0.8 

# Ignore ~/.local: a stray `pip install --user` there silently outranks the
# module-provided torch/transformers/vllm, for every run, invisibly.
[[ "$USER_SITE" == "1" ]] || export PYTHONNOUSERSITE=1


module use -a /fp/projects01/ec30/software/easybuild/modules/all/
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

# Versions go in the log for provenance. A missing package or a CPU-only torch
# fails here rather than as a server traceback a few seconds later.
python3 -c '
import sys, torch, transformers, vllm
print(f"python {sys.version.split()[0]} | torch {torch.__version__} | transformers {transformers.__version__} | vllm {vllm.__version__}")
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

# DRAFT, default OFF: never run on the cluster, and it changes what a run *is*,
# not just its speed. Set, a job resumes from the previous one's checkpointed
# hypothesis and sample position. Discuss with Baris before defaulting it on.
export EXACTLEARNER_RESUME="${EXACTLEARNER_RESUME:-false}"

echo "Batching: size=$EXACTLEARNER_BATCH_SIZE decompose=$EXACTLEARNER_BATCH_DECOMPOSE unsaturate=$EXACTLEARNER_BATCH_UNSATURATE | resume=$EXACTLEARNER_RESUME"

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
# unusable by the learner. GC log and JFR are both always-on diagnostics for the
# open question of where the run's time goes: GPUs sit idle ~72% of the wall
# clock at benchmark tok/s, so something Java-side is the bound. The GC log says
# whether it is collection (a small heap levels off after each full GC, a leak
# walks the floor upwards); JFR names the method if it is not. disk=true plus an
# explicit repository is deliberate -- walltime SIGKILL means dumponexit never
# fires, but completed chunks are already on disk.
#   jfr summary logs/jfr-<job>.jfr
#   jfr print --events ExecutionSample logs/jfr-<job>.jfr | head -100
JAVA_HEAP="${JAVA_HEAP:-64g}"
GC_LOG="${GC_LOG:-logs/gc-${SLURM_JOB_NAME:-exactlearner}-${SLURM_JOB_ID:-$$}.log}"
JFR_FILE="${JFR_FILE:-logs/jfr-${SLURM_JOB_NAME:-exactlearner}-${SLURM_JOB_ID:-$$}.jfr}"
JFR_REPO="${JFR_REPO:-logs/jfr-repo-${SLURM_JOB_ID:-$$}}"
mkdir -p "$(dirname "$GC_LOG")"

# ParallelGCThreads: the 8 cores are shared with the model server and G1 sizes
# its workers from the core count, so a 64g heap can stall every core in a pause.
JAVA_OPTS=(-Xmx"$JAVA_HEAP"
           "-Xlog:gc*,gc+heap=debug:file=${GC_LOG}:time,uptime:filecount=0"
           -XX:ParallelGCThreads="${PARALLEL_GC_THREADS:-4}")

# Opt-in: the dump can be the full heap landing on scratch at once, so this is
# for a run meant to catch the leak, not one meant to make progress.
if [[ "${JAVA_HEAP_DUMP:-0}" == "1" ]]; then
  HEAP_DUMP_DIR="${HEAP_DUMP_DIR:-${SCRATCH:-/tmp}}"
  mkdir -p "$HEAP_DUMP_DIR"
  JAVA_OPTS+=(-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath="$HEAP_DUMP_DIR")
  echo "Heap dump on OOM: $HEAP_DUMP_DIR (up to $JAVA_HEAP)"
fi

if [[ "${JFR:-1}" == "1" ]]; then
  mkdir -p "$(dirname "$JFR_FILE")" "$JFR_REPO"
  JAVA_OPTS+=("-XX:StartFlightRecording=settings=profile,disk=true,dumponexit=true,maxsize=${JFR_MAXSIZE:-2g},filename=${JFR_FILE}"
              "-XX:FlightRecorderOptions=repository=${JFR_REPO}")
fi

echo "JVM: heap=$JAVA_HEAP gc-log=$GC_LOG jfr=${JFR:-1}"

# Plain java, not `mvn exec:java`: exec-maven-plugin is not in pom.xml, so Maven
# would try to fetch it and fail on a compute node with no network.
echo "Starting learner at $(date)"
java "${JAVA_OPTS[@]}" -cp "target/classes:$(cat cp.txt)" \
  org.experiments.LaunchLLMLearnerAInduced "$CONFIG" "$EPSILON" "$DELTA"

echo "Finished at $(date)"
