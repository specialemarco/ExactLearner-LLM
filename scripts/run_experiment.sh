#!/bin/bash
# =============================================================================
# ExactLearner-LLM on Slurm: model server + learner in a single job.
#
#   sbatch scripts/run_experiment.sh <config.yml> [epsilon] [delta]
#
# Example:
#   sbatch scripts/run_experiment.sh \
#     src/main/java/org/configurations/experiments/mistral-owl2bench-c1-nlp-advanced.yml
#
# Submit from the repository ROOT. Several code paths resolve relative to the
# working directory (CacheManager reads src/main/java/org/experiments/logger/
# updates; results go to results/ontologies and statistics/).
#
# One-time setup on a LOGIN node before this will work:
#   mvn -DskipTests install
#   mvn dependency:build-classpath -Dmdep.outputFile=cp.txt
# =============================================================================
#SBATCH --account=ec30
#SBATCH --job-name=exactlearner
#SBATCH --partition=accel
# --nodes=1 and the a100: prefix are both load-bearing, and neither is default.
# A plain "--gpus=4" is an allocation-wide total that SLURM may split 2+2 across
# nodes, which makes vLLM's mp executor fall back to Ray and hang. The accel
# partition is heterogeneous: its H100 nodes have no kernel image for this vLLM
# module and die at the first kernel launch.
#SBATCH --nodes=1
#SBATCH --gpus-per-node=a100:4
#SBATCH --cpus-per-task=8
#SBATCH --mem=128G
#SBATCH --time=24:00:00
#SBATCH --output=logs/%x-%j.log

set -euo pipefail

# ----- EDIT THIS ------------------------------------------------------------
MODEL_PATH="/cluster/work/projects/ec30/emilpo/hub/models--deepseek-ai--DeepSeek-R1-Distill-Qwen-32B/snapshots/711ad2ea6aa40cfca18895e8aca02ab92df1a746"

# Reasoning budget per query. DeepSeek-R1 emits a <think> block before the
# answer, so the num_predict: 2 the Java client sends is ignored. Do NOT lower
# this to buy speed: at 512 the measured p95 was 493, and a query tipping over
# the cap silently flipped True -> False. Watch at_cap, not just truncated.
MAX_NEW_TOKENS="${MAX_NEW_TOKENS:-1024}"

# Full reasoning traces as JSONL. Set empty to disable.
TRACE_FILE="logs/traces-${SLURM_JOB_ID:-local}.jsonl"

# Machine-readable server state, rewritten every heartbeat. Lives on the shared
# filesystem because a login shell cannot reach the compute node's HTTP port.
STATUS_FILE="logs/server-status-${SLURM_JOB_ID:-local}.json"

# Load budget only. Cold vLLM startup on a 32B across 4 ranks can exceed half an
# hour; ~/.cache/vllm makes later starts much faster. A dead server is caught
# immediately regardless, since /health answers from the first second.
SERVER_READY_TIMEOUT="${SERVER_READY_TIMEOUT:-5400}"

# Server progress-line interval, in seconds.
HEARTBEAT_SECONDS="${HEARTBEAT_SECONDS:-30}"

# Project-local EasyBuild tree, newer than the cluster-wide one (which tops out
# at PyTorch 2.1.2 / foss-2023a).
EXTRA_MODULEPATH="/fp/projects01/ec30/software/easybuild/modules/all/"
# All foss-2024a / Python 3.12.3, so they share one site-packages generation.
# Do NOT mix in the cluster-wide PyTorch/2.1.2-foss-2023a (Python 3.11) -- a
# different Python tree, mutually invisible.
PYTHON_MODULES=(
  "nlpl-pytorch/2.6.0-foss-2024a-cuda-12.6.0-Python-3.12.3"
  "nlpl-accelerate/1.9.0-foss-2024a-Python-3.12.3"
  "Transformers/4.57.1-gfbf-2024a"
  "nlpl-vllm/0.8.2-foss-2024a-Python-3.12.3"
)

# vLLM shards attention heads across GPUs, so this must divide the head count
# -- a power of two. Keep it equal to --gpus above.
TENSOR_PARALLEL="${TENSOR_PARALLEL:-4}"

# Caps vLLM's KV cache reservation. Prompts here are ~50 tokens plus the
# reasoning budget; reserving the model's full context would waste most of the
# GPU memory.
MAX_MODEL_LEN="${MAX_MODEL_LEN:-4096}"

# Everything comes from modules, so ~/.local is deliberately excluded -- it
# still holds an accelerate 0.29.3 built for Python 3.11. Set to 1 only if you
# add a --user package this stack needs.
USER_SITE="${USER_SITE:-0}"
# ----------------------------------------------------------------------------

PORT="${PORT:-11434}"

CONFIG="${1:?usage: sbatch scripts/run_experiment.sh <config.yml> [epsilon] [delta]}"
EPSILON="${2:-0.2}"
DELTA="${3:-0.1}"

# `module` is an Lmod shell FUNCTION, not a binary. It reaches a batch job only
# by being exported from the submitting shell, so anything that stops a shell
# from sourcing its startup files takes it away -- for instance a ~/.bashrc that
# is a directory rather than a file. Sourcing Lmod's init here avoids all that.
ensure_module() {
  [[ "$(type -t module || true)" == "function" ]] && return 0
  local f
  for f in /etc/profile.d/lmod.sh /etc/profile.d/z00_lmod.sh \
           /usr/share/lmod/lmod/init/bash /opt/apps/lmod/lmod/init/bash \
           /cluster/software/lmod/lmod/init/bash /usr/share/Modules/init/bash; do
    if [[ -r "$f" ]]; then
      # These scripts predate `set -u` and reference unset variables freely, so
      # the strict flags have to come off for the duration of the source.
      set +eu; . "$f"; set -eu
      [[ "$(type -t module || true)" == "function" ]] && {
        echo "Loaded module command from $f"; return 0; }
    fi
  done
  echo "ERROR: the 'module' command is unavailable and no Lmod init script was
found in the usual places. Check that ~/.bashrc is a FILE (ls -ld ~/.bashrc) --
if it is a directory, bash skips it and Lmod is never initialised." >&2
  return 1
}
ensure_module

module purge
module load Java/21.0.8

if [[ "$USER_SITE" != "1" ]]; then
  export PYTHONNOUSERSITE=1
fi

module use -a "$EXTRA_MODULEPATH"
for m in "${PYTHON_MODULES[@]}"; do
  module load "$m"
done

# `module purge` can leave PATH with nothing but EasyBuild directories -- no
# /usr/bin at all -- when the submitting shell never got a proper environment.
# The resulting failures are scattered and misleading: nvidia-smi vanishes and
# the heartbeat silently loses its GPU column, tee and ps disappear. Appending
# is safe: module directories keep priority.
for d in /usr/bin /bin /usr/sbin /sbin; do
  [[ -d "$d" && ":$PATH:" != *":$d:"* ]] && PATH="$PATH:$d"
done
export PATH
for tool in nvidia-smi curl java python3; do
  command -v "$tool" >/dev/null 2>&1 ||
    echo "WARNING: '$tool' is not on PATH. Check that ~/.bashrc is a FILE." >&2
done

cd "${SLURM_SUBMIT_DIR:-$PWD}"
mkdir -p logs results/ontologies statistics

# --- preflight: fail now, not after the model has loaded ---------------------
# Each of these otherwise surfaces as a failure deep inside vLLM, minutes or
# hours later.

if [[ -n "${SLURM_JOB_ID:-}" ]]; then
  n_nodes="${SLURM_JOB_NUM_NODES:-1}"
  if [[ "$n_nodes" != "1" ]]; then
    echo "ERROR: allocation spans $n_nodes nodes. vLLM's mp executor sees only" >&2
    echo "       the local node, so TP=$TENSOR_PARALLEL would exceed the visible" >&2
    echo "       GPU count, fall back to Ray, and hang. Use --nodes=1." >&2
    exit 1
  fi
fi

n_gpus=$(nvidia-smi --query-gpu=index --format=csv,noheader 2>/dev/null | wc -l)
if [[ "$n_gpus" -lt "$TENSOR_PARALLEL" ]]; then
  echo "ERROR: tensor-parallel size is $TENSOR_PARALLEL but only $n_gpus GPU(s) visible." >&2
  echo "       vLLM would fall back to Ray and wait forever on a cluster that" >&2
  echo "       was never started." >&2
  exit 1
fi

# Wrong GPU generation: weights load fine, then the first kernel launch dies
# with "no kernel image is available for execution on the device".
bad_gpu=$(nvidia-smi --query-gpu=name,compute_cap --format=csv,noheader 2>/dev/null \
          | grep -v '8\.0' | head -1 || true)
if [[ -n "$bad_gpu" ]]; then
  echo "WARNING: this node's GPUs are not the A100 (compute capability 8.0)" >&2
  echo "         the nlpl-vllm module was built for: $bad_gpu" >&2
  echo "         Expect 'no kernel image is available' once weights finish" >&2
  echo "         loading. Submit with --gpus-per-node=a100:4." >&2
fi

# Orphaned workers from a previous run hold memory and spin at 100%, which both
# slows this run and can starve the KV cache allocation.
busy=$(nvidia-smi --query-compute-apps=pid --format=csv,noheader 2>/dev/null | wc -l)
if [[ "$busy" -gt 0 ]]; then
  echo "WARNING: $busy process(es) already using these GPUs. If this job did not" >&2
  echo "         start them they are orphans from an earlier run -- throughput" >&2
  echo "         and KV cache size will both suffer." >&2
  nvidia-smi --query-compute-apps=pid,used_memory --format=csv >&2 || true
fi

[[ -f "$CONFIG" ]]     || { echo "ERROR: no such config: $CONFIG" >&2; exit 1; }
[[ -f cp.txt ]]        || { echo "ERROR: cp.txt missing. On a login node run:
  mvn dependency:build-classpath -Dmdep.outputFile=cp.txt" >&2; exit 1; }
[[ -d target/classes ]]|| { echo "ERROR: target/classes missing. Run: mvn -o -DskipTests compile" >&2; exit 1; }
[[ -d "$MODEL_PATH" ]] || { echo "ERROR: MODEL_PATH is not a directory: $MODEL_PATH" >&2; exit 1; }

# Verify the Python environment before the model load, so a missing package
# costs seconds rather than surfacing minutes in.
python3 - <<'PYCHECK' || { echo "ERROR: Python environment incomplete" >&2; exit 1; }
import sys
# The HTTP layer is stdlib (http.server), so these are the only third-party
# requirements: vllm for the engine, transformers for the tokenizer and its
# chat template, torch underneath both.
missing = []
for mod in ("torch", "transformers", "vllm"):
    try:
        __import__(mod)
    except ImportError as e:
        missing.append(f"{mod} ({e})")
if missing:
    print("Missing Python packages:", *missing, sep="\n  ", file=sys.stderr)
    print("\nLoad the matching module on a LOGIN node, or pip install --user.",
          file=sys.stderr)
    sys.exit(1)
import torch, transformers, vllm
print(f"python {sys.version.split()[0]} | torch {torch.__version__} | "
      f"transformers {transformers.__version__} | vllm {vllm.__version__}")
print(f"CUDA available: {torch.cuda.is_available()} | GPUs: {torch.cuda.device_count()}")

# A CPU-only torch would not crash -- it would just run perhaps 100x slower,
# turning an already-long job into an impossible one. Refuse to start.
if not torch.cuda.is_available():
    print("\nERROR: torch reports no CUDA. This module's PyTorch may be a "
          "CPU-only build, or the job has no GPU allocated.", file=sys.stderr)
    sys.exit(1)
PYCHECK

# The ontology named in the config must sit beside initialOntology.owl and
# baseSet. If either is missing the learner does NOT fail -- it silently falls
# back to uniform PAC sampling and you get a different experiment.
ONTOLOGY=$(grep -A2 '^ontologies:' "$CONFIG" | grep -o '"[^"]*"' | head -1 | tr -d '"')
ONTOLOGY_DIR=$(dirname "$ONTOLOGY")
for required in "$ONTOLOGY" "$ONTOLOGY_DIR/initialOntology.owl" "$ONTOLOGY_DIR/baseSet"; do
  [[ -e "$required" ]] || { echo "ERROR: missing (or broken symlink): $required" >&2; exit 1; }
done
echo "Config:   $CONFIG"
echo "Ontology: $ONTOLOGY"
echo "Data dir: $ONTOLOGY_DIR"

# --- model server ------------------------------------------------------------
export EXACTLEARNER_OLLAMA_URL="http://localhost:${PORT}/api/generate"

# Batch the 17,030 independent precomputation queries instead of issuing them
# one at a time. Measured 6.9x on 4xA100 with 8 CPU cores (2.08 s/query against
# 14.40), which takes precomputation from ~69 h to ~10 h. Only the transport
# changes: the same questions are asked, keyed identically in the cache, and
# the learner itself is untouched. Set to 0 to disable.
export EXACTLEARNER_BATCH_SIZE="${EXACTLEARNER_BATCH_SIZE:-16}"

# Batch the learner's own sweeps. These default ON here rather than being passed
# on the sbatch line, because job 4038936 burned a full 24 h walltime running the
# sequential path: neither flag was in the environment, installDecomposePrefetcher
# returned at its first guard, and the only symptom was a MISSING log line. Both
# forms below still honour an override, so `EXACTLEARNER_BATCH_DECOMPOSE=false
# sbatch ...` reproduces the pre-batching runs exactly.
#
#   DECOMPOSE  - decompose()'s signature scans. Unconditionally independent, so
#                this only changes when answers are fetched.
#   UNSATURATE - extends that to unsaturateLeft/saturateRight, which is where the
#                bottleneck actually sits now. Only *conditionally* independent;
#                watch "speculation rounds=/restarts=" on the counterexample
#                lines. restarts approaching rounds means the speculation is
#                being thrown away and this should go back to false.
export EXACTLEARNER_BATCH_DECOMPOSE="${EXACTLEARNER_BATCH_DECOMPOSE:-true}"
export EXACTLEARNER_BATCH_UNSATURATE="${EXACTLEARNER_BATCH_UNSATURATE:-true}"

echo "Batching: size=$EXACTLEARNER_BATCH_SIZE decompose=$EXACTLEARNER_BATCH_DECOMPOSE unsaturate=$EXACTLEARNER_BATCH_UNSATURATE"

# DRAFT, and default OFF for that reason -- unlike the batching flags above,
# this one has never run on the cluster and it changes what a run *is*, not only
# how fast it gets there. Set, a job continues from the hypothesis and sample
# position the previous one checkpointed, instead of starting from an empty
# hypothesis; unset, loadHypothesisOntology() truncates as it always has.
#
# Every job so far has thrown its whole hypothesis away at the 24 h walltime,
# so consecutive jobs repeat each other rather than accumulating. Discuss with
# Baris before this becomes the default: it is the difference between "the run
# died at the walltime" and "the run is 158 counterexamples in".
export EXACTLEARNER_RESUME="${EXACTLEARNER_RESUME:-false}"
echo "Resume: $EXACTLEARNER_RESUME"

# Educloud sets http_proxy, and curl honours it for EVERY host including
# localhost -- so a probe of our own server is bounced off the Squid proxy with
# an HTML "Access Denied" page, the readiness loop never matches, and the job
# aborts after the full timeout while a loaded model sits idle beside it. The
# curl calls below also pass --noproxy explicitly, since the variable's spelling
# and casing are not consistently honoured. Java ignores both and talks to
# localhost directly, so the learner itself is unaffected.
export no_proxy="localhost,127.0.0.1,::1,${no_proxy:-}"
export NO_PROXY="$no_proxy"

SERVER_ARGS=(--tensor-parallel-size "$TENSOR_PARALLEL"
             --max-model-len "$MAX_MODEL_LEN")
# Default ON for this cluster: the GPUs are A100-PCIE with no NVLink, so the
# custom all-reduce kernel buys little, while the P2P capability probe that
# precedes it can spin forever between NCCL init and weight loading. Set
# DISABLE_CUSTOM_ALL_REDUCE=0 to get the kernel back.
[[ "${DISABLE_CUSTOM_ALL_REDUCE:-1}" == "1" ]] &&
  SERVER_ARGS+=(--disable-custom-all-reduce)
[[ "${ENFORCE_EAGER:-0}" == "1" ]] && SERVER_ARGS+=(--enforce-eager)

# The server must NOT run with the repo root as its working directory. vLLM
# spawns subprocesses with `python3 -m ...`, which puts the CWD first on
# sys.path, so a repo directory can shadow a stdlib module of the same name --
# statistics/ did exactly that until its __init__.py was removed, making
# torch._inductor fail to import and vLLM misreport it as "Model architectures
# ['Qwen2ForCausalLM'] failed to be inspected". Kept as cheap insurance against
# that reappearing. The Java process still runs from the repo root.
REPO_DIR="$PWD"
SERVER_CWD="${SERVER_CWD:-${SCRATCH:-/tmp}/exactlearner-server-${SLURM_JOB_ID:-$$}}"
mkdir -p "$SERVER_CWD"

# Paths handed to the server must be absolute, since its CWD is elsewhere.
TRACE_ABS=""
if [[ -n "$TRACE_FILE" ]]; then
  [[ "$TRACE_FILE" = /* ]] && TRACE_ABS="$TRACE_FILE" || TRACE_ABS="$REPO_DIR/$TRACE_FILE"
fi
STATUS_ABS=""
if [[ -n "$STATUS_FILE" ]]; then
  [[ "$STATUS_FILE" = /* ]] && STATUS_ABS="$STATUS_FILE" || STATUS_ABS="$REPO_DIR/$STATUS_FILE"
fi

echo "Server CWD: $SERVER_CWD"
[[ -n "$STATUS_ABS" ]] && echo "Status:     $STATUS_ABS"
echo
echo "To watch this job from a login shell:"
echo "  squeue -u \$USER"
[[ -n "$STATUS_ABS" ]] && echo "  cat $STATUS_ABS   # rewritten every heartbeat; stale file = dead process"
echo

# vLLM's tensor-parallel workers must not be forked from a process that already
# holds a CUDA context. llm_server.py sets this too; exporting it here covers
# manual invocations of the server as well.
export VLLM_WORKER_MULTIPROC_METHOD=spawn

# REQUIRED on this node: PCIe peer-to-peer does not work, and nothing detects
# that. cudaDeviceCanAccessPeer returns true, NCCL reports "Init COMPLETE" in
# 0.3s, and then the first all-reduce spins at 100% GPU utilization forever --
# no error, no timeout. Measured, not assumed: NCCL_P2P_LEVEL=PHB (P2P only
# within a PCI host bridge) ALSO hangs, so P2P is broken between every pair on
# this node and only disabling it outright works. Setting this to 0 reintroduces
# a silent, unrecoverable hang.
export NCCL_P2P_DISABLE="${NCCL_P2P_DISABLE:-1}"

# setsid puts the server in its own process group, so the trap can take down
# vLLM's spawned tensor-parallel workers with it. Killing the parent alone
# leaves orphaned workers holding GPU memory, which makes the next job in the
# allocation fail to allocate for no visible reason. exec keeps $! pointing at
# the python process itself rather than at a wrapper.
LAUNCH=(python3 "$REPO_DIR/scripts/llm_server.py"
        --model "$MODEL_PATH"
        --port "$PORT"
        --max-new-tokens "$MAX_NEW_TOKENS"
        --heartbeat-seconds "$HEARTBEAT_SECONDS"
        "${SERVER_ARGS[@]}")
[[ -n "$TRACE_ABS"  ]] && LAUNCH+=(--trace-file  "$TRACE_ABS")
[[ -n "$STATUS_ABS" ]] && LAUNCH+=(--status-file "$STATUS_ABS")
if command -v setsid >/dev/null 2>&1; then
  ( cd "$SERVER_CWD" && exec setsid "${LAUNCH[@]}" ) &
else
  ( cd "$SERVER_CWD" && exec "${LAUNCH[@]}" ) &
fi
SERVER_PID=$!

# $! is the python PID in both branches: a background subshell in a
# non-interactive script is not a process-group leader (job control is off), so
# setsid() succeeds and execs in place rather than forking a wrapper.
cleanup_server() {
  # Negative PID targets the process group, which setsid made equal to the
  # server's PID -- that reaches vLLM's workers. Falls back to the bare PID
  # when setsid was unavailable, in which case there is no separate group.
  kill -TERM -"$SERVER_PID" 2>/dev/null ||
    kill -TERM "$SERVER_PID" 2>/dev/null || true
}
# TERM and INT as well as EXIT: Slurm sends SIGTERM at walltime, and bash does
# not run an EXIT trap for an untrapped fatal signal -- which would leave vLLM
# workers holding all four GPUs after the job record says it finished.
trap cleanup_server EXIT TERM INT

# --- wait until it actually answers, rather than sleeping and hoping ---------
# Three distinguishable outcomes:
#   * process gone             -> fail immediately, do not wait out the budget
#   * loading, phase advancing -> keep waiting, and say what it is doing
#   * budget exhausted         -> dump thread stacks before killing it
READY=0
DEADLINE=$(( $(date +%s) + SERVER_READY_TIMEOUT ))
START_WAIT=$(date +%s)
LAST_PHASE=""
LAST_REPORT=0
PROBE='{"model":"mistral","system":"Answer with only True or False.","options":{"num_predict":2},"stream":false,"prompt":"Is a dog an animal?"}'

while [[ $(date +%s) -lt $DEADLINE ]]; do
  kill -0 "$SERVER_PID" 2>/dev/null || {
    echo "ERROR: server process died during startup. Its traceback is above; \
look for the last 'phase:' line to see how far it got." >&2; exit 1; }

  # /health is bound before the model loads and never takes the generation
  # lock, so it answers within milliseconds at every stage.
  HEALTH=$(curl -s -m 10 --noproxy '*' "http://localhost:${PORT}/health" 2>/dev/null || true)

  if [[ "$HEALTH" == *'"ready":true'* ]]; then
    # Health says loaded; now prove the full path end to end, exactly as the
    # Java client will drive it. This is the check that catches a model that
    # loads fine but whose answers do not parse.
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
    # Print on every phase change, and otherwise every 5 minutes, so the log
    # stays informative without becoming a wall of text on a 24h job.
    if [[ "$PHASE" != "$LAST_PHASE" || $(( NOW - LAST_REPORT )) -ge 300 ]]; then
      echo "  [$(( NOW - START_WAIT ))s] server phase: ${PHASE:-unknown}"
      LAST_PHASE="$PHASE"; LAST_REPORT=$NOW
    fi
  elif [[ $(( NOW - LAST_REPORT )) -ge 300 ]]; then
    # Alive (kill -0 passed) but not yet listening: normal for the first
    # seconds, suspicious after that.
    echo "  [$(( NOW - START_WAIT ))s] server up but /health not answering yet"
    LAST_REPORT=$NOW
  fi
  sleep 15
done

if [[ $READY -ne 1 ]]; then
  echo "ERROR: server did not become ready within ${SERVER_READY_TIMEOUT}s \
(last phase: ${LAST_PHASE:-unknown}). Dumping thread stacks before exit." >&2
  # SIGUSR1 is wired to faulthandler in llm_server.py: this prints where every
  # thread actually is, which is the difference between "slow" and "deadlocked".
  kill -USR1 "$SERVER_PID" 2>/dev/null || true
  sleep 5
  echo "Raise SERVER_READY_TIMEOUT, or pass --enforce-eager to skip \
torch.compile, if the stacks show it was still making progress." >&2
  exit 1
fi

# --- run ---------------------------------------------------------------------
# Heap. Job 4044683 died with "OutOfMemoryError: Java heap space" after 23.6 h
# and 158 counterexamples, while it was capped at -Xmx16g under --mem=128G:
# Slurm reported 66.7 GiB for the whole step, so vLLM's host side is ~50 GiB and
# roughly 45 GiB of the allocation was never usable by the learner. 64g keeps
# ~14 GiB of slack over the two together.
JAVA_HEAP="${JAVA_HEAP:-64g}"

# GC logging, always on. It is a few MB over 24 h and it is the only thing that
# separates the two explanations for that OOM: a heap that was merely too small
# levels off after each full GC, a leak walks the post-collection floor upwards
# run-long. That job also held 77.1 % CPU across 8 cores while the GPUs idled
# two thirds of the wall clock, and spent its last 17 minutes issuing no model
# requests at all, so some of what currently reads as "local Java time" may be
# collection rather than reasoning. This is how we find out.
GC_LOG="${GC_LOG:-logs/gc-${SLURM_JOB_NAME:-exactlearner}-${SLURM_JOB_ID:-$$}.log}"
mkdir -p "$(dirname "$GC_LOG")"

JAVA_OPTS=(-Xmx"$JAVA_HEAP"
           "-Xlog:gc*,gc+heap=debug:file=${GC_LOG}:time,uptime:filecount=0")

# Heap dump on OOM: opt-in, because the dump is written at up to the full heap
# size and 64 GiB of it would land on scratch in one go. Turn it on for a run
# whose only purpose is to catch the leak, not for one meant to make progress.
if [[ "${JAVA_HEAP_DUMP:-0}" == "1" ]]; then
  HEAP_DUMP_DIR="${HEAP_DUMP_DIR:-${SCRATCH:-/tmp}}"
  mkdir -p "$HEAP_DUMP_DIR"
  JAVA_OPTS+=(-XX:+HeapDumpOnOutOfMemoryError
              -XX:HeapDumpPath="$HEAP_DUMP_DIR")
  echo "Heap dump on OOM: $HEAP_DUMP_DIR (up to $JAVA_HEAP)"
fi

# The 8 cores are shared with the model server. G1 sizes its parallel workers
# from the core count, so a heap this size can put every core into a collection
# pause and stall the process feeding the GPUs. ELK's own worker pool is left
# alone -- it reads availableProcessors(), which this does not change.
JAVA_OPTS+=(-XX:ParallelGCThreads="${PARALLEL_GC_THREADS:-4}")

echo "JVM: heap=$JAVA_HEAP gc-log=$GC_LOG"

# Plain java, not `mvn exec:java`: exec-maven-plugin is not declared in pom.xml,
# so Maven would try to fetch it and fail on a compute node with no network.
echo "Starting learner at $(date)"
java "${JAVA_OPTS[@]}" -cp "target/classes:$(cat cp.txt)" \
  org.experiments.LaunchLLMLearnerAInduced "$CONFIG" "$EPSILON" "$DELTA"

echo "Finished at $(date)"
