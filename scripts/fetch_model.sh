#!/bin/bash

#------------------------- Fetch Model Weights ---------------------------------------

# Downloads a model's weights from Hugging Face to where its model file expects them:
# $MODEL_ROOT/$MODEL_DIR, a plain folder named after the repository, the layout of
# the group's shared model folder on Olivia. Run from the repository root on a login
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
# A folder that already holds a config.json is never touched: it may be the group's.

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

[[ -n "${HF_REPO:-}" ]] || die "$MODEL_ENV has no HF_REPO"
TARGET_DIR="$MODEL_ROOT/$MODEL_DIR"

#------------------------- Existing Copy ---------------------------------------------

# Someone else's copy is used as it is, never re-downloaded over: a download at
# another revision would change it under their runs. A --local-dir download
# records its commit on the first line of each .metadata file.
if [[ -f "$TARGET_DIR/config.json" ]]; then
  have=$(head -1 "$TARGET_DIR/.cache/huggingface/download/config.json.metadata" 2>/dev/null || true)
  if [[ -z "$have" ]]; then
    echo "Already present, revision unknown: $TARGET_DIR"
  elif [[ -n "${HF_REVISION:-}" && "$have" != "$HF_REVISION" ]]; then
    die "$TARGET_DIR is at $have, but $MODEL_ENV pins $HF_REVISION; pin that one, or use another MODEL_DIR"
  else
    echo "Already present at $have: $TARGET_DIR"
  fi
  exit 0
fi

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

mkdir -p "$TARGET_DIR"
echo "Fetching $HF_REPO${HF_REVISION:+ at $HF_REVISION} into $TARGET_DIR"

python3 - "$HF_REPO" "${HF_REVISION:-}" "$TARGET_DIR" <<'PY'
import sys
from huggingface_hub import snapshot_download

repo, revision, target = sys.argv[1], sys.argv[2] or None, sys.argv[3]
# vLLM reads the config, tokenizer and safetensors; skip the duplicate formats.
path = snapshot_download(repo, revision=revision, local_dir=target, ignore_patterns=[
    "*.bin", "*.pth", "*.pt", "*.gguf", "*.h5", "*.msgpack", "*.onnx",
    "original/*", "consolidated*"])
print("Downloaded to", path)
PY

#------------------------- Check the Download ----------------------------------------

[[ -f "$TARGET_DIR/config.json" ]] ||
  die "no config.json at $TARGET_DIR, where the job will look"
echo "Ready: $MODEL_ROOT/$MODEL_DIR"
