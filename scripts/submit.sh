#!/bin/bash

#------------------------- Submit Script ---------------------------------------------

# Submits ExactLearner-LLM experiments to Slurm: one model with one config, as one
# job or as a batch of repeats with different seeds. Each job runs
# scripts/run_experiment.sh. Run from the repository root, on a login node.
#
#   scripts/submit.sh <model> <config> [name=value ...]
#   scripts/submit.sh mistral-7b owl2bench/c2-nlp-advanced sampler=unweighted repeats=10
#
# <model> is scripts/models/<model>.env. <config> is a path, or a name under
# src/main/java/org/configurations/experiments without the .yml. Parameters,
# first value the default:
#
#   precomp=true|false|reuse    reuse: repeats replay the first one's precomputation
#   eval=baris|none             evaluation after the loop; default off for sampler=pac
#   cache=shared|fresh|<path>   fresh: a new cache file for this job
#   sampler=weighted|unweighted|pac
#   budget=global|per-round
#   resume=false|true           continue from the previous job's checkpoint
#   seed=N                      sampler seed, for whichever sampler runs
#   repeats=N                   N jobs with seeds seed..seed+N-1
#
# They reach the job as EXACTLEARNER_* variables, which sbatch passes on.

#------------------------- Safety Settings -------------------------------------------

set -euo pipefail # Exit on any error or unset variable, and on a failure in a pipe

die() { echo "ERROR: $*" >&2; exit 1; }

#------------------------- Model and Config ------------------------------------------

MODEL="$1"
CONFIG="src/main/java/org/configurations/experiments/$2.yml"
shift 2

#------------------------- Parameters ------------------------------------------------

# Defaults, then each name=value argument overrides one of them
REPEATS=1
export EXACTLEARNER_SAMPLER=weighted EXACTLEARNER_PRECOMP=true
for arg in "$@"; do
  value="${arg#*=}"
  case "$arg" in
    precomp=true|precomp=false)     export EXACTLEARNER_PRECOMP="$value" ;;
    precomp=reuse)                  export EXACTLEARNER_PRECOMP=true EXACTLEARNER_PRECOMP_REUSE=true ;;
    eval=baris)                     export EXACTLEARNER_EVAL=true ;;
    eval=none)                      export EXACTLEARNER_EVAL=false ;;
    cache=shared)                   ;;
    cache=?*)                       export EXACTLEARNER_CACHE="$value" ;;   # the job names a fresh one
    budget=global|budget=per-round) export EXACTLEARNER_BUDGET_MODE="$value" ;;
    resume=true|resume=false)       export EXACTLEARNER_RESUME="$value" ;;
    sampler=weighted|sampler=unweighted|sampler=pac) export EXACTLEARNER_SAMPLER="$value" ;;
    seed=*)                         export EXACTLEARNER_SEED="$value" ;;
    repeats=*)                      REPEATS="$value" ;;
    *) die "bad parameter: $arg" ;;
  esac
done

#------------------------- Load the Experiment and Model Settings --------------------

# Exported because the job sources both again: the variables they set are not.
export EXACTLEARNER_ENV="scripts/experiment.env"
export EXACTLEARNER_MODEL_ENV="scripts/models/$MODEL.env"

# The model file comes second, so it wins.
set +u
source "$EXACTLEARNER_ENV"
source "$EXACTLEARNER_MODEL_ENV"
set -u
#[[ -n "${MODEL_ROOT:-}" ]] || die "MODEL_ROOT is not set in $EXACTLEARNER_ENV"

#------------------------- Experiment Arm --------------------------------------------

# Logs go to logs/<model>/<arm>/<config>/, and the run tag <arm>-seed<N>[-eps<E>]
# names the per-run files in results/ontologies/ and statistics/.
ARM="${EXACTLEARNER_SAMPLER}_precomp"
if [[ "$EXACTLEARNER_PRECOMP" == false ]]; then
  ARM="${EXACTLEARNER_SAMPLER}_noprecomp"
fi

#------------------------- Epsilon Tag -----------------------------------------------

# Epsilon is set in the config, and a *-eps<E> config name gives it its own log
# folder, but not its own results/ and statistics/ files: those are named without
# the config, so a non-default epsilon (the launcher's is 0.2) tags the run.
EPSILON=$(awk '$1 == "epsilon:" { print $2 }' "$CONFIG")
EPS_TAG=""
if [[ -n "$EPSILON" ]] && awk -v e="$EPSILON" 'BEGIN { exit !(e + 0 != 0.2) }'; then
  EPS_TAG="-eps$EPSILON"
fi

#------------------------- Log Folder ------------------------------------------------

export EXACTLEARNER_LOG_DIR="logs/$MODEL_NAME/$ARM/$(basename "$CONFIG" .yml)"
mkdir -p "$EXACTLEARNER_LOG_DIR"   # sbatch does not create it, and the job fails at launch

#------------------------- Submission Summary ----------------------------------------

echo "$MODEL_NAME ($MODEL) | $CONFIG | ${GPUS} tp=${TENSOR_PARALLEL} batch=${EXACTLEARNER_BATCH_SIZE} time=$WALLTIME mem=$MEMORY"
echo "logs -> $EXACTLEARNER_LOG_DIR/"

#------------------------- sbatch Call -----------------------------------------------

# The Slurm options that vary per model or machine. The other Slurm options are the
# #SBATCH lines in run_experiment.sh.
DEPENDENCY=""
submit() {
  sbatch --parsable --account="$SBATCH_ACCOUNT" --gpus-per-node="$GPUS" \
    --time="$WALLTIME" --mem="$MEMORY" \
    ${DEPENDENCY:+--dependency="$DEPENDENCY"} \
    --output="$EXACTLEARNER_LOG_DIR/%x-%j.log" \
    scripts/run_experiment.sh "$CONFIG"
}

#------------------------- Single Run ------------------------------------------------

# Seed 0 unless seed= was given
if [[ "$REPEATS" -eq 1 ]]; then
  export EXACTLEARNER_RUN_TAG="$ARM-seed${EXACTLEARNER_SEED:-0}$EPS_TAG"
  echo "Submitted batch job $(submit) (tag=$EXACTLEARNER_RUN_TAG)"
  exit 0
fi

#------------------------- Repeats ---------------------------------------------------

# One job per seed, starting from 1 unless seed= was given
first_seed="${EXACTLEARNER_SEED:-1}"

for (( seed = first_seed; seed < first_seed + REPEATS; seed++ )); do
  export EXACTLEARNER_SEED=$seed
  export EXACTLEARNER_RUN_TAG="$ARM-seed$seed$EPS_TAG"

  job=$(submit)
  echo "Submitted batch job $job (seed=$seed tag=$EXACTLEARNER_RUN_TAG${DEPENDENCY:+, after ${DEPENDENCY#afterany:}})"

  # precomp=reuse: the other repeats wait for the first, which records the
  # precomputation. afterany, not afterok: a walltime kill exits non-zero long
  # after the record is written. Cancelling the first starts the rest, so scancel all.
  if [[ "${EXACTLEARNER_PRECOMP_REUSE:-false}" == true && -z "$DEPENDENCY" ]]; then
    DEPENDENCY="afterany:$job"
  fi
done

#------------------------- Notification ----------------------------------------------

curl -H "Exact Learner: $MODEL_NAME" -d "Experiment submitted" ntfy.sh/exact-llm

