#!/bin/bash
# Submit run_experiment.sh with your personal Slurm settings applied.
#
#   scripts/submit.sh <config.yml> [epsilon] [delta]
#
# Why this wrapper exists: sbatch reads the #SBATCH directives inside
# run_experiment.sh before that script ever executes, so the personal config it
# sources cannot change --account, --partition or --time. Command-line flags do
# override those directives, so this reads SBATCH_ARGS out of your personal
# config and puts them on the sbatch line.
#
# Submit from the repository ROOT -- several code paths resolve relative to the
# working directory.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXACTLEARNER_ENV="${EXACTLEARNER_ENV:-$SCRIPT_DIR/experiment.env}"

SBATCH_ARGS=()
if [[ -f "$EXACTLEARNER_ENV" ]]; then
  set +u; . "$EXACTLEARNER_ENV"; set -u
else
  echo "No personal config at $EXACTLEARNER_ENV -- submitting with the script's" >&2
  echo "built-in defaults. cp scripts/experiment.env.example scripts/experiment.env" >&2
fi

# Exported so the job re-reads the same file on the compute node, even when the
# submitting environment is not inherited wholesale.
export EXACTLEARNER_ENV

# ${arr[@]+...} keeps an empty array from tripping `set -u` on older bash.
exec sbatch "${SBATCH_ARGS[@]+"${SBATCH_ARGS[@]}"}" \
     "$SCRIPT_DIR/run_experiment.sh" "$@"
