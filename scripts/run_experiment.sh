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

#------------------------- Notify and Clean Up on Exit -------------------------------

# One message however the job ends: finished, failed (a die below, a Java crash), or
# killed by Slurm at walltime or by scancel. TERM and INT go through exit, as bash
# skips the EXIT trap on a fatal signal. cleanup_server exists once the server does.
on_exit() {
  local status=$? how=finished
  if declare -F cleanup_server >/dev/null; then cleanup_server; fi
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
MODEL_PATH="$MODEL_ROOT/$MODEL_DIR"
SERVER_READY_TIMEOUT="${SERVER_READY_TIMEOUT:-5400}"   # a cold 32B load can take over 30 min
MAX_MODEL_LEN="${MAX_MODEL_LEN:-4096}"
JAVA_HEAP="${JAVA_HEAP:-64g}"

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

#------------------------- GPU Check -------------------------------------------------

# vLLM falls back to Ray and waits forever when tensor parallelism exceeds the GPUs.
n_gpus=$(nvidia-smi --query-gpu=index --format=csv,noheader | wc -l)
[[ "$n_gpus" -ge "$TENSOR_PARALLEL" ]] ||
  die "tensor parallelism is $TENSOR_PARALLEL but only $n_gpus GPU(s) are visible"

#------------------------- Software Versions -----------------------------------------

# Print the versions the modules gave us, and check that torch can see the GPUs
python3 -c '
import sys, torch, transformers, vllm
print(f"python {sys.version.split()[0]} | torch {torch.__version__} | transformers {transformers.__version__} | vllm {vllm.__version__}")
assert torch.cuda.is_available(), "torch sees no CUDA"
'

#------------------------- Output Folders --------------------------------------------

# Where the learner writes the learned ontologies and the per-run statistics
mkdir -p results/ontologies statistics

#------------------------- Run Summary -----------------------------------------------

echo "Config: $CONFIG"
echo "Model: $EXACTLEARNER_MODEL ($MODEL_PATH) | run tag: $EXACTLEARNER_RUN_TAG"
echo "Sampler: $EXACTLEARNER_SAMPLER ($LEARNER_MAIN_CLASS) | seed: $EXACTLEARNER_SEED | budget: $EXACTLEARNER_BUDGET_MODE | resume: $EXACTLEARNER_RESUME | precomp: $EXACTLEARNER_PRECOMP | precomp reuse: $EXACTLEARNER_PRECOMP_REUSE"
echo "Batching: size=$EXACTLEARNER_BATCH_SIZE decompose=$EXACTLEARNER_BATCH_DECOMPOSE unsaturate=$EXACTLEARNER_BATCH_UNSATURATE | ELK unlock: $EXACTLEARNER_ELK_UNLOCK every $EXACTLEARNER_ELK_UNLOCK_INTERVAL"

#------------------------- Server Port -----------------------------------------------

# One port per job, as a batch of repeats lands on one node together; step past
# any port still held, e.g. by an orphaned server.
PORT=$(( 20000 + SLURM_JOB_ID % 10000 ))
while (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; do
  echo "port $PORT is in use; trying $(( PORT + 1 ))"
  PORT=$(( PORT + 1 ))
done
echo "Server port: $PORT"
export EXACTLEARNER_LLM_URL="http://localhost:$PORT/api/generate"   # Read by the learner

#------------------------- Notification ----------------------------------------------

curl -d "Experiment started: $MODEL_NAME" https://ntfy.sh/exact-llm

#------------------------- Start the Model Server ------------------------------------

# Run from outside the repo: vLLM puts the CWD first on sys.path, and statistics/
# once shadowed the stdlib module. setsid gives the server a process group, so
# cleanup_server can take its TP workers down too.
SERVER_CWD="${SCRATCH:-/tmp}/exactlearner-server-$SLURM_JOB_ID"
STATUS_FILE="$REPO_DIR/$LOG_DIR/server-status-$SLURM_JOB_ID.json"
mkdir -p "$SERVER_CWD"
SERVER_ARGS=(--model "$MODEL_PATH" --port "$PORT"
             --max-new-tokens "$MAX_NEW_TOKENS"
             --tensor-parallel-size "$TENSOR_PARALLEL" --max-model-len "$MAX_MODEL_LEN"
             --trace-file "$REPO_DIR/$LOG_DIR/llm-queries-$SLURM_JOB_ID.jsonl"
             --status-file "$STATUS_FILE"
             --disable-custom-all-reduce)   # no NVLink: little gain, and its P2P probe can hang
if [[ "${ENFORCE_EAGER:-0}" == 1 ]]; then
  SERVER_ARGS+=(--enforce-eager)
fi
( cd "$SERVER_CWD" && exec setsid python3 "$REPO_DIR/scripts/llm_server.py" "${SERVER_ARGS[@]}" ) &
SERVER_PID=$!

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

# Wait for the model to load (the server logs its own phases), then send one
# query the way Java will, to catch answers that do not parse. --noproxy: Educloud's
# http_proxy would send these localhost requests to Squid.
DEADLINE=$(( $(date +%s) + SERVER_READY_TIMEOUT ))
until [[ "$(curl -s -m 10 --noproxy '*' "http://localhost:$PORT/health")" == *'"ready":true'* ]]; do
  kill -0 "$SERVER_PID" 2>/dev/null || die "server died during startup; traceback above"
  (( $(date +%s) < DEADLINE )) ||
    die "server not ready within ${SERVER_READY_TIMEOUT}s; raise SERVER_READY_TIMEOUT or set ENFORCE_EAGER=1"
  sleep 15
done
PROBE='{"system":"Answer with only True or False.","options":{"num_predict":2},"stream":false,"prompt":"Is a dog an animal?"}'
RESPONSE=$(curl -s -m 300 --noproxy '*' -H 'Content-Type: application/json' -d "$PROBE" \
             "http://localhost:$PORT/api/generate" || true)
[[ "$RESPONSE" == *'"response":"'* ]] || die "server is ready but the probe did not parse: $RESPONSE"
echo "Server ready. Probe: $RESPONSE"

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
  -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -cp "target/classes:$(cat cp.txt)" \
  "$LEARNER_MAIN_CLASS" "${LEARNER_ARGS[@]}"
echo "Finished at $(date)"
