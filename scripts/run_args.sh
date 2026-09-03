# Argument handling shared by submit.sh and run_experiment.sh: the config name,
# and the run parameters in name=value form.
#
# Sourced by both so a typo is caught by submit.sh before the job is queued,
# rather than 20 minutes later when the model has finished loading.
#
# resolve_config() takes the bare name of a config -- everything under
# CONFIG_DIR can be named without that directory and without the .yml. The
# OWL2Bench configs live in owl2bench/ and name no model at all -- the model comes
# from the first argument, so one config serves every model:
#
#   scripts/submit.sh deepseek-r1-32b owl2bench/c2-nlp-advanced
#   scripts/submit.sh mistral-7b      owl2bench/c2-nlp-advanced
#
# The flat pre-2026-09-01 names still resolve and still work -- they are the same
# experiments, with the model hard-coded inside:
#
#   scripts/submit.sh deepseek-r1-32b mistral-owl2bench-c2-nlp-advanced
#
# A path that exists as given is still used unchanged, so tab completion of the
# full path keeps working. It is resolved relative to the working directory,
# which both scripts already require to be the repository root.
#
#   eps=0.2            PAC epsilon
#   delta=0.1          PAC delta
#   cache=shared       shared cache.sqlite3, or fresh, or a path of its own
#   precomp=true       run learner.precomputation() before the loop
#   eval=baris|none    Macro/Micro Precision/Recall after the loop
#   budget=global      or per-round -- see MEETING-2026-08-18.md section 8
#   resume=false       continue from the previous job's checkpointed hypothesis
#   seed=0             A-induced sampler
#   pacseed=0          uniform PAC sampler
#   repeats=1          submit this many jobs, seeds seed..seed+N-1 (submit.sh only)
#
# Order does not matter and every one is optional; omitting all of them is the
# arm every run so far has used. Bare numbers are still read as epsilon then
# delta, so the old positional form keeps working.
#
# precomp is the readable direction of the Java flag, which is skipPrecomputation:
# precomp=false means skip it. eval names the evaluator rather than saying true,
# because "baris" is what the report is called; none turns it off.
#
# Sets: EPSILON, DELTA, LEARNER_FLAG_ARGS (the trailing argv for the launcher),
# RUN_ARGS_SUMMARY (what was asked for, echoed back), and exports
# EXACTLEARNER_BUDGET_MODE / _SAMPLER_SEED / _PAC_SEED / _RESUME when given.

CONFIG_DIR="src/main/java/org/configurations/experiments"

# Every config under CONFIG_DIR, named the way resolve_config accepts them:
# relative to CONFIG_DIR and without the .yml. Recursive, because the per-model
# folders added on 2026-09-01 put the OWL2Bench configs one level down, and a
# plain ls would list the folder names rather than anything runnable.
list_configs() {
  (cd "$CONFIG_DIR" 2>/dev/null &&
     find . -name '*.yml' | sed 's|^\./||; s|\.yml$||' | sort)
}

resolve_config() {
  local given="${1-}" candidate
  [[ -n "$given" ]] || die "no config given"
  for candidate in "$given" "$given.yml" "$CONFIG_DIR/$given" "$CONFIG_DIR/$given.yml"; do
    if [[ -f "$candidate" ]]; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  die "no such config: $given
       looked in . and $CONFIG_DIR, with and without .yml
       available: $(list_configs | tr '\n' ' ')"
}

parse_run_args() {
  EPSILON=""
  DELTA=""
  CACHE_MODE=shared
  REPEATS=1
  local precomp="" evaluate="" resume="" positional=0

  local arg key value
  for arg in "$@"; do
    if [[ "$arg" != *=* ]]; then
      # Legacy positional form: epsilon, then delta.
      case $((positional++)) in
        0) EPSILON="$arg" ;;
        1) DELTA="$arg" ;;
        *) die "unexpected argument '$arg'. Use name=value: $RUN_ARGS_USAGE" ;;
      esac
      continue
    fi

    key="${arg%%=*}"
    value="${arg#*=}"
    case "$key" in
      eps|epsilon) EPSILON="$value" ;;
      delta)       DELTA="$value" ;;
      precomp)     precomp="$(parse_run_bool "$key" "$value")" ;;
      eval)
        case "$(run_args_lower "$value")" in
          baris|true|on|yes) evaluate=true ;;
          none|false|off|no) evaluate=false ;;
          *) die "eval=$value: expected baris or none" ;;
        esac
        ;;
      budget)
        case "$(run_args_lower "$value")" in
          global|per-round|perround|round) export EXACTLEARNER_BUDGET_MODE="$value" ;;
          *) die "budget=$value: expected global or per-round" ;;
        esac
        ;;
      cache)
        # fresh is resolved by resolve_cache_path() rather than here: submit.sh
        # sources this file too, and sbatch exports its environment, so a path
        # built from the login node's $$ would follow the job and defeat itself.
        case "$(run_args_lower "$value")" in
          shared|keep) CACHE_MODE=shared ;;
          fresh|new|cold) CACHE_MODE=fresh ;;
          "") die "cache=: expected shared, fresh, or a path" ;;
          *) CACHE_MODE=path; export EXACTLEARNER_CACHE="$value" ;;
        esac
        ;;
      resume)
        # Continues from <hypo>.owl + <hypo>-run-state.properties in
        # results/ontologies/ instead of deleting them and starting empty.
        # A parameter rather than a bare environment variable so that a typo
        # fails at submission, and so the run summary records that the numbers
        # came from more than one job.
        #
        # Assigned to a local and exported below rather than exported straight
        # from the substitution: `export X="$(f)"` takes its exit status from
        # export, not from f, so die() inside parse_run_bool would NOT trip
        # set -e -- and resume=ture would then quietly run with resume off,
        # which deletes the very checkpoint it was asked to continue from.
        resume="$(parse_run_bool "$key" "$value")"
        ;;
      seed)    parse_run_int "$key" "$value"; export EXACTLEARNER_SAMPLER_SEED="$value" ;;
      pacseed) parse_run_int "$key" "$value"; export EXACTLEARNER_PAC_SEED="$value" ;;
      repeats)
        # Acted on by submit.sh, which fires this many jobs. Parsed here too so
        # that a bare `sbatch run_experiment.sh <config> repeats=5` is not
        # rejected as an unknown name -- the job itself is always one repeat, and
        # run_experiment.sh says so rather than silently doing one of five.
        parse_run_int "$key" "$value"
        [[ "$value" -ge 1 ]] || die "repeats=$value: expected 1 or more"
        REPEATS="$value"
        ;;
      *) die "unknown parameter '$key'. Use one of: $RUN_ARGS_USAGE" ;;
    esac
  done

  # Exported only when asked for, so an unset resume leaves run_experiment.sh's
  # own default (false) in place rather than overriding an inherited value.
  [[ -n "$resume" ]] && export EXACTLEARNER_RESUME="$resume"

  EPSILON="${EPSILON:-0.2}"
  DELTA="${DELTA:-0.1}"
  parse_run_num epsilon "$EPSILON"
  parse_run_num delta   "$DELTA"

  # The launcher reads these positionally, so eval cannot be passed without
  # precomp ahead of it. false is skipPrecomputation's own default, so filling it
  # in changes nothing. eval is left off entirely when unset, because the two
  # arms disagree about its default and only the launcher knows which is running.
  LEARNER_FLAG_ARGS=()
  if [[ -n "$precomp" || -n "$evaluate" ]]; then
    # Java's flag is skipPrecomputation -- the negation of precomp.
    if [[ "$precomp" == false ]]; then
      LEARNER_FLAG_ARGS+=(true)
    else
      LEARNER_FLAG_ARGS+=(false)
    fi
    [[ -n "$evaluate" ]] && LEARNER_FLAG_ARGS+=("$evaluate")
  fi

  # Echoed back by both scripts. Only what was actually asked for: a parameter
  # left out is the launcher's own default, and this file does not know it.
  RUN_ARGS_SUMMARY="eps=$EPSILON delta=$DELTA"
  [[ "$CACHE_MODE" != shared ]] && RUN_ARGS_SUMMARY+=" cache=$CACHE_MODE"
  [[ -n "$precomp"  ]] && RUN_ARGS_SUMMARY+=" precomp=$precomp"
  [[ "$evaluate" == true  ]] && RUN_ARGS_SUMMARY+=" eval=baris"
  [[ "$evaluate" == false ]] && RUN_ARGS_SUMMARY+=" eval=none"
  [[ -n "${EXACTLEARNER_BUDGET_MODE:-}"  ]] && RUN_ARGS_SUMMARY+=" budget=$EXACTLEARNER_BUDGET_MODE"
  [[ -n "${EXACTLEARNER_RESUME:-}"       ]] && RUN_ARGS_SUMMARY+=" resume=$EXACTLEARNER_RESUME"
  [[ -n "${EXACTLEARNER_SAMPLER_SEED:-}" ]] && RUN_ARGS_SUMMARY+=" seed=$EXACTLEARNER_SAMPLER_SEED"
  [[ -n "${EXACTLEARNER_PAC_SEED:-}"     ]] && RUN_ARGS_SUMMARY+=" pacseed=$EXACTLEARNER_PAC_SEED"
  [[ "$REPEATS" -gt 1 ]] && RUN_ARGS_SUMMARY+=" repeats=$REPEATS"
  return 0
}

RUN_ARGS_USAGE="eps= delta= precomp=true|false eval=baris|none cache=shared|fresh|<path> budget=global|per-round resume=true|false seed=N pacseed=N repeats=N"

# Called by run_experiment.sh only, once the job id is known. cache=fresh gets a
# file of its own per job, so the run pays for every query it asks and its timings
# stand alone -- the shared cache is keyed by (model, system, query) and not by
# ontology or run, so a rerun of the same configuration replays the previous
# run's answers. The shared cache is never touched, moved or deleted by this.
resolve_cache_path() {
  [[ "${CACHE_MODE:-shared}" == fresh ]] || return 0
  export EXACTLEARNER_CACHE="cache-fresh-${SLURM_JOB_ID:-$$}.sqlite3"
}

# bash 3.2 on a mac has no ${x,,}, and these scripts get edited there.
run_args_lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

parse_run_bool() {
  case "$(run_args_lower "$2")" in
    true|on|yes)  printf true ;;
    false|off|no) printf false ;;
    *) die "$1=$2: expected true or false" ;;
  esac
}

parse_run_int() {
  [[ "$2" =~ ^-?[0-9]+$ ]] || die "$1=$2: expected an integer"
}

parse_run_num() {
  [[ "$2" =~ ^[0-9]*\.?[0-9]+$ ]] || die "$1=$2: expected a number"
}
