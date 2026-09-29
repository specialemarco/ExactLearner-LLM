#!/bin/bash

#------------------------- Fetch Model Weights ---------------------------------------

# Downloads a model's weights from Hugging Face to where its model file expects them:
# $MODEL_ROOT/hub, the Hugging Face cache layout that MODEL_DIR in
# scripts/models/<model>.env points into. Run from the repository root on a login
# node; it submits itself as a small CPU job, since a 32B download takes a while.
#
#   scripts/fetch_model.sh <model>                        e.g. olmo2-13b
#   FETCH_WALLTIME=08:00:00 scripts/fetch_model.sh <model>
#
# The model file names the repository (HF_REPO) and can pin a revision (HF_REVISION).
# A gated model needs a token: run `huggingface-cli login` once, or export HF_TOKEN.
# Downloading again is safe: files already in the cache are skipped.

#------------------------- SLURM Job Configuration -----------------------------------

#SBATCH --job-name=exactlnr-fetch
#SBATCH --partition=normal
#SBATCH --cpus-per-task=4
#SBATCH --mem=8G

# Account, time and log file come from the submission below.

#------------------------- Safety Settings -------------------------------------------

set -euo pipefail

die() { echo "ERROR: $*" >&2; exit 1; }

#------------------------- Load the Experiment and Model Settings --------------------

MODEL="${1:-}"
[[ -n "$MODEL" ]] || die "usage: scripts/fetch_model.sh <model>, one of: $(find scripts/models -name '*.env' -type f -exec basename {} .env \; | sort | tr '\n' ' ')"
MODEL_ENV="scripts/models/$MODEL.env"
[[ -f "$MODEL_ENV" ]] || die "no model file $MODEL_ENV (run from the repository root)"

set +u
source scripts/experiment.env
set -u
[[ -n "${MODEL_ROOT:-}" ]] || die "MODEL_ROOT is not set in scripts/experiment.env"
# Before the model file: its MODEL_DIR may cd into MODEL_ROOT, and under set -e a
# missing directory would end the script there without a word.
mkdir -p "$MODEL_ROOT"
set +u
source "$MODEL_ENV"
set -u
[[ -n "${HF_REPO:-}" ]]    || die "$MODEL_ENV has no HF_REPO"
[[ "$MODEL_DIR" == hub/* ]] ||
  die "$MODEL_ENV has MODEL_DIR=$MODEL_DIR, outside $MODEL_ROOT/hub where this script downloads to"

#------------------------- Submit ----------------------------------------------------

# Outside a job: submit this script as one, and stop.
if [[ -z "${SLURM_JOB_ID:-}" ]]; then
  mkdir -p logs/fetch
  sbatch --account="$SBATCH_ACCOUNT" --time="${FETCH_WALLTIME:-04:00:00}" \
    --output="logs/fetch/$MODEL-%j.log" "$0" "$MODEL"
  echo "logs -> logs/fetch/$MODEL-<jobid>.log"
  exit 0
fi

#------------------------- Load Required Modules -------------------------------------

module purge
# Only ec30 members can read this module tree; others set MODULE_TREE.
module use -a "${MODULE_TREE:-/fp/projects01/ec30/software/easybuild/modules/all/}"
module load Transformers/4.57.1-gfbf-2024a   # brings huggingface_hub
export PYTHONNOUSERSITE=1

#------------------------- Download --------------------------------------------------

# HF_HUB_CACHE, not HF_HOME: HF_HOME would also move where `huggingface-cli login`
# keeps its token.
export HF_HUB_CACHE="$MODEL_ROOT/hub"
mkdir -p "$HF_HUB_CACHE"
echo "Fetching $HF_REPO${HF_REVISION:+ at $HF_REVISION} into $HF_HUB_CACHE"

python3 - "$HF_REPO" "${HF_REVISION:-}" <<'PY'
import sys
from huggingface_hub import snapshot_download

repo, revision = sys.argv[1], sys.argv[2] or None
# vLLM reads the config, tokenizer and safetensors; skip the duplicate formats.
path = snapshot_download(repo, revision=revision, ignore_patterns=[
    "*.bin", "*.pth", "*.pt", "*.gguf", "*.h5", "*.msgpack", "*.onnx",
    "original/*", "consolidated*"])
print("Downloaded to", path)
PY

#------------------------- Check -----------------------------------------------------

# Read the model file again: a MODEL_DIR that globs over snapshots/* only resolves
# once the snapshot exists.
set +u
source "$MODEL_ENV"
set -u
[[ "$MODEL_DIR" != *" "* ]] ||
  die "MODEL_DIR matches more than one snapshot ($MODEL_DIR); delete the old one or pin HF_REVISION"
[[ -f "$MODEL_ROOT/$MODEL_DIR/config.json" ]] ||
  die "no config.json at $MODEL_ROOT/$MODEL_DIR, where the job will look"
echo "Ready: $MODEL_ROOT/$MODEL_DIR"
