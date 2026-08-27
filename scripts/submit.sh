#!/bin/bash
# Submit one model x one config to Slurm.
#
#   scripts/submit.sh <model> <config> [name=value ...]
#
# <config> is a name under src/main/java/org/configurations/experiments -- the
# directory and the .yml are both optional. A path that exists is used as given.
#
# The run parameters are name=value in any order and all optional -- eps, delta,
# precomp, eval, budget, seed, pacseed. scripts/run_args.sh documents them and is
# sourced here as well as in the job, so a typo fails now rather than after the
# model has loaded on a compute node.
#
# <model> is a file in scripts/models/ without the .env. It carries everything
# that travels with the weights -- path, tensor parallelism, GPU count, token
# budget, batch size, walltime -- and is tracked, so a model runs the same way
# for both of us. scripts/experiment.env is yours alone: MODEL_ROOT and the
# account.
#
# The model file is sourced after the personal one and wins, because it states
# facts about the model rather than preferences. To vary a setting, copy the
# file under a new name; that name is then what labels the result.
#
# Submit from the repository ROOT -- several code paths resolve relative to the
# working directory.
set -euo pipefail

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$SCRIPT_DIR/run_args.sh"

MODEL="${1-}"
CONFIG="${2-}"
if [[ -z "$MODEL" || -z "$CONFIG" ]]; then
  die "usage: scripts/submit.sh <model> <config> [name=value ...]
       models: $(ls "$SCRIPT_DIR"/models/*.env 2>/dev/null | xargs -n1 basename 2>/dev/null | sed 's/\.env$//' | tr '\n' ' ')
       params: $RUN_ARGS_USAGE
       configs: $CONFIG_DIR/ -- name them without the directory or the .yml"
fi
shift 2

# Bare name, or a path as given. Resolved here so what reaches sbatch, the model
# name check below, and the log all refer to the same file.
CONFIG="$(resolve_config "$CONFIG")"

# Validate only -- the job re-parses these itself. Exits here on a bad name.
parse_run_args "$@"

EXACTLEARNER_ENV="${EXACTLEARNER_ENV:-$SCRIPT_DIR/experiment.env}"
EXACTLEARNER_MODEL_ENV="$SCRIPT_DIR/models/$MODEL.env"
[[ -f "$EXACTLEARNER_ENV" ]] ||
  die "no personal config at $EXACTLEARNER_ENV -- cp scripts/experiment.env.example scripts/experiment.env and fill it in."
[[ -f "$EXACTLEARNER_MODEL_ENV" ]] || die "no such model: $EXACTLEARNER_MODEL_ENV"

set +u; . "$EXACTLEARNER_ENV"; . "$EXACTLEARNER_MODEL_ENV"; set -u

[[ -n "${MODEL_ROOT:-}"     ]] || die "MODEL_ROOT is not set in $EXACTLEARNER_ENV"
[[ -n "${SBATCH_ACCOUNT:-}" ]] || die "SBATCH_ACCOUNT is not set in $EXACTLEARNER_ENV"

# WALLTIME belongs with the model because how long a run needs follows the
# weights: a 32B at 45 tok/s does not fit in what a 7B needs. Unset falls back to
# the #SBATCH --time directive in run_experiment.sh. Checked here because sbatch
# rejects a malformed one only after the rest of the line has been accepted, and
# the error does not name the file it came from.
if [[ -n "${WALLTIME:-}" ]]; then
  [[ "$WALLTIME" =~ ^([0-9]+(:[0-9]{1,2}){0,2}|[0-9]+-[0-9]{1,2}(:[0-9]{1,2}){0,2}|UNLIMITED|INFINITE)$ ]] ||
    die "WALLTIME=\"$WALLTIME\" in $EXACTLEARNER_MODEL_ENV is not a Slurm time.
       Use minutes, MM:SS, HH:MM:SS, D-HH, D-HH:MM or D-HH:MM:SS -- e.g. 24:00:00 or 2-00:00:00."
fi

# MEMORY belongs with the model for the same reason WALLTIME does: the host-side
# footprint follows the weights and the tensor-parallel width. Unset falls back to
# the #SBATCH --mem directive. Same reason for checking it here -- sbatch reports a
# malformed --mem without naming the file it came from.
if [[ -n "${MEMORY:-}" ]]; then
  [[ "$MEMORY" =~ ^[0-9]+[KMGTkmgt]?$ ]] ||
    die "MEMORY=\"$MEMORY\" in $EXACTLEARNER_MODEL_ENV is not a Slurm size.
       Use a number with an optional K/M/G/T suffix -- e.g. 64G. 0 means the whole node."

  # The learner's heap is carved out of this allocation, and so is vLLM's host
  # side, so MEMORY at or below JAVA_HEAP cannot work. Only that -- the certain
  # misconfiguration -- is checked. vLLM's footprint is the other half and it is
  # model-dependent: ~50 GiB was measured on a 32B at TP=4, while job 4094190
  # (7B, TP=1) touched 15.0 GiB for the whole job, so there is no one threshold
  # to test against. The message carries the number instead.
  mem_g=${MEMORY%[KMGTkmgt]}
  case "$MEMORY" in
    *[Kk]) mem_g=$(( mem_g / 1024 / 1024 )) ;;
    *[Mm]) mem_g=$(( mem_g / 1024 )) ;;
    *[Tt]) mem_g=$(( mem_g * 1024 )) ;;
  esac
  heap_g=${JAVA_HEAP:-64g}
  heap_g=${heap_g%[Gg]}
  if [[ "$MEMORY" != 0 && "$heap_g" =~ ^[0-9]+$ ]] && (( mem_g <= heap_g )); then
    warn "MEMORY=$MEMORY leaves $(( mem_g - heap_g ))G once the ${heap_g}g JVM heap is taken.
         vLLM's host side has to fit in what is left -- ~50 GiB on a 32B at TP=4,
         far less on a 7B. Raise MEMORY or lower JAVA_HEAP in $EXACTLEARNER_MODEL_ENV."
  fi
fi

# The cache is keyed by the model name in the YAML, so the weights and that name
# must agree. Running one model's weights under another's name writes its
# answers into that model's cache and every later run replays them, silently.
yml_model=$(grep -A2 '^models:' "$CONFIG" | grep -o '"[^"]*"' | head -1 | tr -d '"')
[[ "$yml_model" == "$MODEL_NAME" ]] ||
  die "$CONFIG asks for \"$yml_model\" but $MODEL.env serves \"$MODEL_NAME\". That name is the cache key -- mixing them corrupts it."

# Absolute, and on the shared filesystem: the job may run from a spool copy of
# run_experiment.sh, where a relative scripts/ does not resolve.
RUN_ARGS_LIB="$SCRIPT_DIR/run_args.sh"

export EXACTLEARNER_ENV EXACTLEARNER_MODEL_ENV RUN_ARGS_LIB

echo "$MODEL_NAME ($MODEL) | $CONFIG | ${GPUS} tp=${TENSOR_PARALLEL} batch=${EXACTLEARNER_BATCH_SIZE} time=${WALLTIME:-<script default>} mem=${MEMORY:-<script default>}"
echo "$RUN_ARGS_SUMMARY"

# sbatch reads the #SBATCH directives inside run_experiment.sh before that script
# executes, so account, GPUs and walltime can only be set from the command line.
# SBATCH_EXTRA stays last so it still wins: it is the escape hatch for a site
# whose limits the model file cannot know about.
exec sbatch --account="$SBATCH_ACCOUNT" --gpus-per-node="$GPUS" \
     ${WALLTIME:+--time="$WALLTIME"} \
     ${MEMORY:+--mem="$MEMORY"} \
     "${SBATCH_EXTRA[@]+"${SBATCH_EXTRA[@]}"}" \
     "$SCRIPT_DIR/run_experiment.sh" "$CONFIG" "$@"
