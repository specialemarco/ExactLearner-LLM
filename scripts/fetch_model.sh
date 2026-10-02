#!/bin/bash

#------------------------- Fetch Model Weights ---------------------------------------

# Downloads a model's weights from Hugging Face to where its model file expects them:
# $MODEL_ROOT/hub, the Hugging Face cache layout that MODEL_DIR in
# scripts/models/<model>.env points into. Run from the repository root on a login
# node; it submits itself as a small CPU job, since a 32B download takes a while.
#
# It first checks that the cluster's vLLM supports the model's architecture (from
# config.json: the downloaded copy, or Hugging Face), and downloads only if it does.
# Given a Hugging Face repository instead of a model name, it also writes the model
# file, scripts/models/<name>.env, with settings from the model's metadata
# (scripts/fetch_model.py); review them before a real run.
#
#   scripts/fetch_model.sh <model>                        e.g. olmo2-13b
#   scripts/fetch_model.sh <org/repo> [--name <name>]     e.g. Qwen/Qwen3-4B-Thinking-2507
#   scripts/fetch_model.sh --check <model | org/repo>     check only: no file, no download
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

CHECK_ONLY=false
NAME=""
TARGET=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --check) CHECK_ONLY=true ;;
    --name)  NAME="${2:-}"; shift ;;
    -*)      die "unknown option $1" ;;
    *)       [[ -z "$TARGET" ]] || die "one model at a time"; TARGET="$1" ;;
  esac
  shift
done
[[ -n "$TARGET" ]] || die "usage: scripts/fetch_model.sh [--check] <model | org/repo> [--name <name>], models: $(find scripts/models -name '*.env' -type f -exec basename {} .env \; | sort | tr '\n' ' ')"

# A repository (org/repo) is a new model: its model file is written below.
NEW=false
if [[ "$TARGET" == */* ]]; then
  NEW=true
  REPO="$TARGET"
  MODEL="${NAME:-$(basename "$REPO" | tr '[:upper:]' '[:lower:]')}"
else
  [[ -z "$NAME" ]] || die "--name only goes with a Hugging Face repository (org/repo)"
  MODEL="$TARGET"
fi
MODEL_ENV="scripts/models/$MODEL.env"
if $NEW; then
  [[ ! -e "$MODEL_ENV" ]] || die "$MODEL_ENV already exists; run scripts/fetch_model.sh $MODEL"
else
  [[ -f "$MODEL_ENV" ]] || die "no model file $MODEL_ENV (run from the repository root)"
fi

set +u
source scripts/experiment.env
set -u
[[ -n "${MODEL_ROOT:-}" ]] || die "MODEL_ROOT is not set in scripts/experiment.env"
# Before the model file: its MODEL_DIR may cd into MODEL_ROOT, and under set -e a
# missing directory would end the script there without a word.
mkdir -p "$MODEL_ROOT"
if ! $NEW; then
  set +u
  source "$MODEL_ENV"
  set -u
fi

#------------------------- Load Required Modules -------------------------------------

# The stack run_experiment.sh serves the model with, so the check asks the right vLLM.
source scripts/modules.sh
load_modules fetch

#------------------------- Check vLLM Support ----------------------------------------

# Before anything is submitted. A new model's file is written only once the check
# has passed, so an unsupported model leaves nothing behind. The download job is
# recognised by its own marker, not SLURM_JOB_ID: that is also set inside an
# interactive salloc, where this part still has to run.
check_failed() {
  [[ "$1" == 1 ]] && die "the cluster's vLLM cannot serve $2; not fetching"
  die "could not check $2 (see above); not fetching"
}
if [[ -z "${EXACTLEARNER_FETCH_JOB:-}" ]]; then
  if $NEW; then
    WRITE=()
    $CHECK_ONLY || WRITE=(--write "$MODEL_ENV" --name "$MODEL")
    python3 scripts/fetch_model.py --repo "$REPO" ${WRITE[@]+"${WRITE[@]}"} ||
      check_failed $? "$REPO"
  else
    python3 scripts/fetch_model.py --config "$MODEL_ROOT/$MODEL_DIR/config.json" \
      --repo "${HF_REPO:?$MODEL_ENV has no HF_REPO to check against}" ${HF_REVISION:+--revision "$HF_REVISION"} ||
      check_failed $? "$MODEL"
  fi
  if $CHECK_ONLY; then
    exit 0
  fi
  if $NEW; then
    set +u
    source "$MODEL_ENV"
    set -u
  fi
fi

[[ -n "${HF_REPO:-}" ]]    || die "$MODEL_ENV has no HF_REPO"
[[ "$MODEL_DIR" == hub/* ]] ||
  die "$MODEL_ENV has MODEL_DIR=$MODEL_DIR, outside $MODEL_ROOT/hub where this script downloads to"

#------------------------- Submit ----------------------------------------------------

# Outside the download job: submit this script as one, and stop.
if [[ -z "${EXACTLEARNER_FETCH_JOB:-}" ]]; then
  mkdir -p logs/fetch
  sbatch --account="$SBATCH_ACCOUNT" --time="${FETCH_WALLTIME:-04:00:00}" \
    --export=ALL,EXACTLEARNER_FETCH_JOB=1 \
    --output="logs/fetch/$MODEL-%j.log" "$0" "$MODEL"
  echo "logs -> logs/fetch/$MODEL-<jobid>.log"
  exit 0
fi

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

#------------------------- Check the Download ----------------------------------------

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
