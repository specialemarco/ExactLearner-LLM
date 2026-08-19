#!/bin/bash
# Submit one model x one config to Slurm.
#
#   scripts/submit.sh <model> <config.yml> [epsilon] [delta]
#
# <model> is a file in scripts/models/ without the .env. It carries everything
# that travels with the weights -- path, tensor parallelism, GPU count, token
# budget, batch size -- and is tracked, so a model runs the same way for both
# of us. scripts/experiment.env is yours alone: MODEL_ROOT and the account.
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
MODEL="${1-}"
CONFIG="${2-}"
if [[ -z "$MODEL" || ! -f "$CONFIG" ]]; then
  die "usage: scripts/submit.sh <model> <config.yml> [epsilon] [delta]
       models: $(ls "$SCRIPT_DIR"/models/*.env 2>/dev/null | xargs -n1 basename 2>/dev/null | sed 's/\.env$//' | tr '\n' ' ')"
fi
shift 2

EXACTLEARNER_ENV="${EXACTLEARNER_ENV:-$SCRIPT_DIR/experiment.env}"
EXACTLEARNER_MODEL_ENV="$SCRIPT_DIR/models/$MODEL.env"
[[ -f "$EXACTLEARNER_ENV" ]] ||
  die "no personal config at $EXACTLEARNER_ENV -- cp scripts/experiment.env.example scripts/experiment.env and fill it in."
[[ -f "$EXACTLEARNER_MODEL_ENV" ]] || die "no such model: $EXACTLEARNER_MODEL_ENV"

set +u; . "$EXACTLEARNER_ENV"; . "$EXACTLEARNER_MODEL_ENV"; set -u

[[ -n "${MODEL_ROOT:-}"     ]] || die "MODEL_ROOT is not set in $EXACTLEARNER_ENV"
[[ -n "${SBATCH_ACCOUNT:-}" ]] || die "SBATCH_ACCOUNT is not set in $EXACTLEARNER_ENV"

# The cache is keyed by the model name in the YAML, so the weights and that name
# must agree. Running one model's weights under another's name writes its
# answers into that model's cache and every later run replays them, silently.
yml_model=$(grep -A2 '^models:' "$CONFIG" | grep -o '"[^"]*"' | head -1 | tr -d '"')
[[ "$yml_model" == "$MODEL_NAME" ]] ||
  die "$CONFIG asks for \"$yml_model\" but $MODEL.env serves \"$MODEL_NAME\". That name is the cache key -- mixing them corrupts it."

export EXACTLEARNER_ENV EXACTLEARNER_MODEL_ENV

echo "$MODEL_NAME ($MODEL) | $CONFIG | ${GPUS} tp=${TENSOR_PARALLEL} batch=${EXACTLEARNER_BATCH_SIZE}"

# sbatch reads the #SBATCH directives inside run_experiment.sh before that
# script executes, so account and GPUs can only be set from the command line.
# SBATCH_EXTRA in the personal config covers partition/time if a site differs.
exec sbatch --account="$SBATCH_ACCOUNT" --gpus-per-node="$GPUS" \
     "${SBATCH_EXTRA[@]+"${SBATCH_EXTRA[@]}"}" \
     "$SCRIPT_DIR/run_experiment.sh" "$CONFIG" "$@"
