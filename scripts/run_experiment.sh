#!/bin/bash

#------------------------- SLURM Job Script ------------------------------------------

# This is a SLURM job script for running ExactLearner-LLM on Fox or Olivia. One job
# starts a model server (vLLM, via scripts/llm_server.py) on the GPUs and then runs
# the Java learner against it, with the LLM as the teacher.
#
# Do not sbatch this file directly. It is submitted by scripts/submit.sh, which
# passes the account, GPUs, time, memory and log folder, and the run parameters as
# EXACTLEARNER_* variables.
#
# Each machine has its own scripts/experiment.env, which sets the cluster-specific
# defaults for the job. The model file (scripts/models/<model>.env) sets the
# model-specific defaults. 
#
# Run the rebuild script (scripts/rebuild.sh) if you change the Java code or the 
# Python server.
#   scripts/rebuild.sh

#------------------------- SLURM Job Configuration -----------------------------------

#SBATCH --job-name=exactlnr          # Name of the job (visible in the job queue)
#SBATCH --partition=accel            # GPU partition
#SBATCH --nodes=1                    # vLLM hangs if the GPUs are split across nodes
#SBATCH --cpus-per-task=8            # Shared by the model server and the learner

# GPUs, time, memory and account come from submit.sh.

#------------------------- Safety Settings -------------------------------------------

set -euo pipefail # Exit on any error or unset variable, and on a failure in a pipe

die() { echo "ERROR: $*" >&2; exit 1; }

#------------------------- Load the Experiment and Model Settings --------------------

# The machine settings (scripts/experiment.env), then the model settings
# (scripts/models/<model>.env).
set +u
source "$EXACTLEARNER_ENV"
source "$EXACTLEARNER_MODEL_ENV"
set -u

# A non-zero temperature samples, so its answers must not share the query cache
# with greedy ones, whose key is (model, system prompt, question): it runs under
# its own model name, which also names its logs and results. temperature= on the
# command line beats TEMPERATURE in the model file.
TEMPERATURE="${EXACTLEARNER_TEMPERATURE:-${TEMPERATURE:-0}}"
[[ "$TEMPERATURE" =~ ^[0-9]*\.?[0-9]+$ ]] || die "temperature=$TEMPERATURE is not a number"
if awk -v t="$TEMPERATURE" 'BEGIN { exit !(t + 0 != 0) }'; then
  MODEL_NAME="$MODEL_NAME-t$TEMPERATURE"
fi

#------------------------- Notify and Clean Up on Exit -------------------------------

# One message however the job ends: finished, failed (a die below, a Java crash), or
# killed by Slurm at walltime or by scancel. TERM and INT go through exit, as bash
# skips the EXIT trap on a fatal signal. cleanup_server exists once the server does.
# How often a thinking model ran out of budget, for this job's model calls (cached
# answers are not asked again). Read live before the server stops; the status file,
# at most one heartbeat old, if the server is already gone.
report_answers() {
  local payload
  payload=$(curl -s -m 10 --noproxy '*' "http://localhost:${PORT:-0}/health" 2>/dev/null)
  [[ "$payload" == *'"requests"'* ]] || payload=$(cat "${STATUS_FILE:-/nonexistent}" 2>/dev/null)
  [[ "$payload" == *'"requests"'* ]] || return 0
  python3 -c '
import json, sys
s, budget = json.loads(sys.argv[1]), sys.argv[2]
n = s["requests"]
def pct(k):
    return "%d (%.1f%%)" % (s[k], 100.0 * s[k] / n) if n else str(s[k])
avg = " | avg %.0f tokens/answer" % (s["tokens"] / n) if n and "tokens" in s else ""
print("LLM answers this job : %d | thinking cut at %s tokens, answer forced: %s"
      " | no True/False, stored as False: %s%s" % (n, budget, pct("truncated"), pct("unparsed"), avg))
' "$payload" "${MAX_NEW_TOKENS:-?}" 2>/dev/null || true
}

on_exit() {
  local status=$? how=finished
  if declare -F cleanup_server >/dev/null; then report_answers; cleanup_server; fi
  rm -rf "${JOB_CLASSES:-/nonexistent}"
  if [[ $status -ne 0 && -s "${SERVER_LOG:-}" ]]; then
    echo "----- last 30 lines of $SERVER_LOG -----"
    tail -n 30 "$SERVER_LOG"
  fi
  if [[ -n "${KILLED:-}" ]]; then how="KILLED (walltime or scancel)"
  elif [[ $status -ne 0 ]]; then how="FAILED (exit $status)"; fi
  curl -s -m 10 -d "Experiment $how: $MODEL_NAME $(basename "$1" .yml) ${EXACTLEARNER_RUN_TAG:-} job ${SLURM_JOB_ID:-}" \
    https://ntfy.sh/exact-llm >/dev/null || true
}
trap 'on_exit "$1"' EXIT
trap 'KILLED=1; exit 143' TERM INT

#------------------------- Paths and Limits ------------------------------------------

CONFIG="$1"
LOG_DIR="$EXACTLEARNER_LOG_DIR"
REPO_DIR="$PWD"
MODEL_PATH="${MODEL_ROOT%/}/$MODEL_DIR"
SERVER_READY_TIMEOUT="${SERVER_READY_TIMEOUT:-5400}"   # a cold 32B load can take over 30 min
MAX_MODEL_LEN="${MAX_MODEL_LEN:-4096}"
# The heap is the job's memory less 32 GiB for the model server and the JVM's own
# overhead (~8 GiB measured). Its need follows how much the model learns, not the
# model's size: 24g ran out on C2 for deepseekQwen-1.5b (job 2419580), which says
# True to nearly everything, while Mistral never used more than 15 GiB. Hence one
# generous MEMORY for every model; a model file can still set JAVA_HEAP.
[[ "$MEMORY" =~ ^[0-9]+G$ ]] || die "MEMORY=$MEMORY: give it in whole GiB, e.g. 128G"
JAVA_HEAP="${JAVA_HEAP:-$(( ${MEMORY%G} - 32 ))g}"

#------------------------- Query Cache -----------------------------------------------

# cache=fresh: a cache of this job's own, so it pays for every query.
if [[ "${EXACTLEARNER_CACHE:-}" == fresh ]]; then
  export EXACTLEARNER_CACHE="cache-fresh-$SLURM_JOB_ID.sqlite3"
fi

#------------------------- Learner Settings ------------------------------------------

# The model name is the cache key; the weights come from MODEL_PATH.
export EXACTLEARNER_MODEL="$MODEL_NAME"
export EXACTLEARNER_BATCH_SIZE
export EXACTLEARNER_BATCH_DECOMPOSE="${EXACTLEARNER_BATCH_DECOMPOSE:-true}"
# Set false if "speculation rounds=/restarts=" shows restarts nearing rounds.
export EXACTLEARNER_BATCH_UNSATURATE="${EXACTLEARNER_BATCH_UNSATURATE:-true}"
# Lets ELK evict finished queries: without it, 86% of Java CPU went to rescanning them.
export EXACTLEARNER_ELK_UNLOCK="${EXACTLEARNER_ELK_UNLOCK:-true}"
export EXACTLEARNER_ELK_UNLOCK_INTERVAL="${EXACTLEARNER_ELK_UNLOCK_INTERVAL:-2000}"
export EXACTLEARNER_RESUME="${EXACTLEARNER_RESUME:-false}"
export EXACTLEARNER_BUDGET_MODE="${EXACTLEARNER_BUDGET_MODE:-global}"
export EXACTLEARNER_PRECOMP="${EXACTLEARNER_PRECOMP:-false}"
export EXACTLEARNER_PRECOMP_REUSE="${EXACTLEARNER_PRECOMP_REUSE:-false}"
export EXACTLEARNER_SEED="${EXACTLEARNER_SEED:-0}"   # 0 reproduces earlier single runs

#------------------------- Learner Selection -----------------------------------------

# The A-induced launcher runs both ABox samplers; pac is the plain launcher's loop.
LEARNER_MAIN_CLASS=org.experiments.LaunchLLMLearnerAInduced
if [[ "$EXACTLEARNER_SAMPLER" == pac ]]; then
  LEARNER_MAIN_CLASS=org.experiments.LaunchLLMLearner
fi

# evaluate is left off when unset, as the two launchers default it differently.
LEARNER_ARGS=("$CONFIG" ${EXACTLEARNER_EVAL:+"$EXACTLEARNER_EVAL"})

#------------------------- Load Required Modules -------------------------------------

# This cluster's stack (scripts/modules.sh, CLUSTER in scripts/experiment.env)
source scripts/modules.sh
load_modules run

#------------------------- Pre-flight Checks -----------------------------------------

# Checked before the model loads, so a broken setup fails in seconds, not after the
# GPUs have been held for the load.
command -v curl >/dev/null ||
  die "curl is not on PATH after module load; a ~/.bashrc that is a directory drops /usr/bin"
[[ -f "$CONFIG" ]]      || die "no such config: $CONFIG"
[[ -f cp.txt ]]         || die "cp.txt missing: mvn -o dependency:build-classpath -Dmdep.outputFile=cp.txt"
[[ -d target/classes ]] || die "target/classes missing: mvn -o -DskipTests compile"
[[ -f "$MODEL_PATH/config.json" ]] ||   # the folder can outlive its files: Olivia cleans old files
  die "no model at $MODEL_PATH; download it again with scripts/fetch_model.sh"

#------------------------- Private Copy of the Classes -------------------------------

# Java loads a class from disk the first time it is used, some only hours in (the
# evaluation). rebuild.sh deletes and rewrites target/classes, so a job reading it
# directly could crash mid-rebuild or run a mix of old and new code. Each job runs
# its own copy instead, and target/classes can be rebuilt at any time.
JOB_CLASSES="${SCRATCH:-/tmp}/exactlearner-classes-$SLURM_JOB_ID"
rm -rf "$JOB_CLASSES"
cp -r target/classes "$JOB_CLASSES"
[[ -f "$JOB_CLASSES/${LEARNER_MAIN_CLASS//.//}.class" ]] ||
  die "the copy of target/classes has no $LEARNER_MAIN_CLASS; was a rebuild running? Resubmit"
CLASSES_BUILT=$(date -r "$JOB_CLASSES/${LEARNER_MAIN_CLASS//.//}.class" '+%Y-%m-%d %H:%M')

#------------------------- GPU Check -------------------------------------------------

# vLLM falls back to Ray and waits forever when tensor parallelism exceeds the GPUs.
n_gpus=$(nvidia-smi --query-gpu=index --format=csv,noheader | wc -l)
[[ "$n_gpus" -ge "$TENSOR_PARALLEL" ]] ||
  die "tensor parallelism is $TENSOR_PARALLEL but only $n_gpus GPU(s) are visible"

#------------------------- Software Versions -----------------------------------------

# The versions the modules gave us, for the header; and torch must see the GPUs.
VERSIONS=$(python3 -c '
import sys, torch, transformers, vllm
assert torch.cuda.is_available(), "torch sees no CUDA"
print(f"python {sys.version.split()[0]} | torch {torch.__version__} | transformers {transformers.__version__} | vllm {vllm.__version__}")
' 2>&1 | tail -1) || die "the Python stack failed its check: $VERSIONS"
GPU_NAMES=$(nvidia-smi --query-gpu=name --format=csv,noheader | sort | uniq -c | sed 's/^ *//; s/ / x /' | paste -sd, -)

#------------------------- Output Folders --------------------------------------------

# Where the learner writes the learned ontologies. The statistics are in the job log.
mkdir -p results/ontologies

#------------------------- Server Port -----------------------------------------------

# One port per job, as a batch of repeats lands on one node together; step past
# any port still held, e.g. by an orphaned server.
PORT=$(( 20000 + SLURM_JOB_ID % 10000 ))
while (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; do
  echo "port $PORT is in use; trying $(( PORT + 1 ))"
  PORT=$(( PORT + 1 ))
done
export EXACTLEARNER_LLM_URL="http://localhost:$PORT/api/generate"   # Read by the learner

#------------------------- Run Summary -----------------------------------------------

# vLLM's startup output and the heartbeat go to their own file; this log keeps the
# run's settings, the server's state and the learner.
SERVER_LOG="$LOG_DIR/server-$SLURM_JOB_ID.log"
BANNER="*********************************************************************************"
echo "$BANNER"
echo "ExactLearner-LLM: $MODEL_NAME on $(basename "$CONFIG" .yml)"
echo "$BANNER"
echo "Config              : $CONFIG"
echo "Model               : $EXACTLEARNER_MODEL ($MODEL_PATH)"
echo "Run tag             : $EXACTLEARNER_RUN_TAG"
echo "Launcher            : $LEARNER_MAIN_CLASS"
echo "Sampler             : $EXACTLEARNER_SAMPLER, seed $EXACTLEARNER_SEED"
echo "Precomputation      : $EXACTLEARNER_PRECOMP (reuse $EXACTLEARNER_PRECOMP_REUSE)"
echo "Budget              : $EXACTLEARNER_BUDGET_MODE, resume $EXACTLEARNER_RESUME"
echo "Batching            : size $EXACTLEARNER_BATCH_SIZE, decompose $EXACTLEARNER_BATCH_DECOMPOSE, unsaturate $EXACTLEARNER_BATCH_UNSATURATE"
echo "ELK unlock          : $EXACTLEARNER_ELK_UNLOCK, every $EXACTLEARNER_ELK_UNLOCK_INTERVAL queries"
echo "Temperature         : $TEMPERATURE"
echo "Java heap           : $JAVA_HEAP"
echo "Java classes        : built $CLASSES_BUILT, copied to $JOB_CLASSES"
echo "GPUs                : $GPU_NAMES (tensor parallel $TENSOR_PARALLEL)"
echo "Software            : $VERSIONS"
echo "Server              : port $PORT, log $SERVER_LOG"
echo "$BANNER"

curl -s -o /dev/null -d "Experiment started: $MODEL_NAME" https://ntfy.sh/exact-llm || true

#------------------------- Start the Model Server ------------------------------------

# Run from outside the repo: vLLM puts the CWD first on sys.path, and statistics/
# once shadowed the stdlib module. setsid gives the server a process group, so
# cleanup_server can take its TP workers down too.
SERVER_CWD="${SCRATCH:-/tmp}/exactlearner-server-$SLURM_JOB_ID"
STATUS_FILE="$REPO_DIR/$LOG_DIR/server-status-$SLURM_JOB_ID.json"
mkdir -p "$SERVER_CWD"
SERVER_ARGS=(--model "$MODEL_PATH" --port "$PORT"
             --max-new-tokens "$MAX_NEW_TOKENS" --temperature "$TEMPERATURE"
             --tensor-parallel-size "$TENSOR_PARALLEL" --max-model-len "$MAX_MODEL_LEN"
             --trace-file "$REPO_DIR/$LOG_DIR/llm-queries-$SLURM_JOB_ID.jsonl"
             --status-file "$STATUS_FILE"
             --disable-custom-all-reduce)   # no NVLink: little gain, and its P2P probe can hang
if [[ "${ENFORCE_EAGER:-0}" == 1 ]]; then
  SERVER_ARGS+=(--enforce-eager)
fi
( cd "$SERVER_CWD" && exec setsid python3 "$REPO_DIR/scripts/llm_server.py" "${SERVER_ARGS[@]}" ) \
  > "$SERVER_LOG" 2>&1 &
SERVER_PID=$!
SERVER_STARTED=$(date +%s)

#------------------------- Stop the Server on Exit -----------------------------------

# Called by on_exit. The wait is for the heartbeat, which can rewrite the status
# file while the server exits.
cleanup_server() {
  # The plain PID covers a server killed before setsid has made its group.
  kill -TERM -"$SERVER_PID" 2>/dev/null || kill -TERM "$SERVER_PID" 2>/dev/null || true
  for _ in {1..10}; do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 1; done
  rm -f "$STATUS_FILE" "$STATUS_FILE.tmp"
}

#------------------------- Wait for the Server ---------------------------------------

# Wait for the model to load (the server log has its phases), then send one
# query the way Java will, to catch answers that do not parse. --noproxy: Educloud's
# http_proxy would send these localhost requests to Squid.
echo "Loading the model server..."
DEADLINE=$(( $(date +%s) + SERVER_READY_TIMEOUT ))
until [[ "$(curl -s -m 10 --noproxy '*' "http://localhost:$PORT/health")" == *'"ready":true'* ]]; do
  kill -0 "$SERVER_PID" 2>/dev/null || die "server died during startup; see the end of $SERVER_LOG below"
  (( $(date +%s) < DEADLINE )) ||
    die "server not ready within ${SERVER_READY_TIMEOUT}s; raise SERVER_READY_TIMEOUT or set ENFORCE_EAGER=1"
  sleep 15
done
PROBE='{"system":"Answer with only True or False.","options":{"num_predict":2},"stream":false,"prompt":"Is a dog an animal?"}'
RESPONSE=$(curl -s -m 300 --noproxy '*' -H 'Content-Type: application/json' -d "$PROBE" \
             "http://localhost:$PORT/api/generate" || true)
[[ "$RESPONSE" == *'"response":"'* ]] || die "server is ready but the probe did not parse: $RESPONSE"
echo "Server ready after $(( $(date +%s) - SERVER_STARTED )) s. Probe: $RESPONSE"

# Olivia deletes files left untouched for months, folders aside. The model has just
# been loaded and used, so mark it as used. Someone else's copy may not be ours to
# touch; that is fine.
find -L "$MODEL_PATH" -type f -exec touch -c {} + 2>/dev/null || true

#------------------------- Run the Learner -------------------------------------------

# 64g heap default, overwritten by the environment variable. The 8 cores are shared with the
# server, so cap the GC threads or a pause stalls them all. Plain java: the pom
# has no exec-maven-plugin, and compute nodes have no network to fetch it.
echo "Starting learner at $(date), heap $JAVA_HEAP"
# UTF-8: counterexamples are logged with ⊑, which a C locale prints as ?.
java -Xmx"$JAVA_HEAP" -XX:ParallelGCThreads=4 \
  -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 \
  -Dslf4j.internal.verbosity=ERROR -cp "$JOB_CLASSES:$(cat cp.txt)" \
  "$LEARNER_MAIN_CLASS" "${LEARNER_ARGS[@]}"
echo "Finished at $(date)"
