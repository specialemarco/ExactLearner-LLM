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
#   cache=shared       shared cache.sqlite3, or fresh, or a path of its own
#   precomp=true       run learner.precomputation() before the loop
#                      (reuse = run it once, then replay it across the repeats)
#   eval=baris|none    Macro/Micro Precision/Recall after the loop
#   budget=global      or per-round -- see MEETING-2026-08-18.md section 8
#   sampler=weighted   or unweighted (both ABox-induced), or pac (uniform)
#   resume=false       continue from the previous job's checkpointed hypothesis
#   seed=0             A-induced sampler
#   pacseed=0          uniform PAC sampler
#   repeats=1          submit this many jobs, seeds seed..seed+N-1 (submit.sh only)
#
# Order does not matter and every one is optional. Each takes exactly the
# spellings above, lowercase. PAC epsilon and delta are set in the config
# (epsilon:, delta:), not here.
#
# precomp is the readable direction of the Java flag, which is skipPrecomputation:
# precomp=false means skip it. eval names the evaluator rather than saying true,
# because "baris" is what the report is called; none turns it off.
#
# precomp=reuse is precomp=true plus EXACTLEARNER_PRECOMP_REUSE: the first repeat
# records the n(n-1) pass to results/ontologies/precomp_<config>.txt -- untagged,
# since the record is what the repeats SHARE -- and the rest replay it.
#
# sampler names the candidate source in the equivalence-query loop, the fourth
# axis. weighted (the default, and every run before 2026-09-07) and unweighted are
# paclo's two ABox-induced samplers -- WeightedABoxInducedSubsumptionSampler and
# ABoxInducedSubsumptionSampler. weighted draws the premise individual from the
# typed individuals with probability proportional to 2^|types|; unweighted draws
# uniformly from every individual in the signature, so untyped ones give an empty
# premise (owl:Thing on the left), which is what the plain sampler does. pac is
# the uniform sampler over the signature, which never looks at the ABox.
# run_experiment.sh turns this into the launcher class, since the class is what
# the arm has always been.
#
# Sets: PRECOMP (true unless precomp=false), LEARNER_FLAG_ARGS
# (the trailing argv for the launcher), RUN_ARGS_SUMMARY (what was asked for,
# echoed back), and exports
# EXACTLEARNER_BUDGET_MODE / _SAMPLER / _SAMPLER_SEED / _PAC_SEED / _RESUME
# when given.

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
  CACHE_MODE=shared
  REPEATS=1
  PRECOMP_LABEL=""
  local precomp="" evaluate="" arg value

  for arg in "$@"; do
    value="${arg#*=}"
    case "$arg" in
      precomp=true|precomp=false) precomp="$value" ;;
      precomp=reuse)              precomp=true; PRECOMP_LABEL=reuse
                                  export EXACTLEARNER_PRECOMP_REUSE=true ;;
      eval=baris)                 evaluate=true ;;
      eval=none)                  evaluate=false ;;
      cache=shared|cache=fresh)   CACHE_MODE="$value" ;;
      cache=?*)                   CACHE_MODE=path; export EXACTLEARNER_CACHE="$value" ;;
      budget=global|budget=per-round)                  export EXACTLEARNER_BUDGET_MODE="$value" ;;
      # Exported only when given, so run_experiment.sh's own default stands.
      resume=true|resume=false)                        export EXACTLEARNER_RESUME="$value" ;;
      sampler=weighted|sampler=unweighted|sampler=pac) export EXACTLEARNER_SAMPLER="$value" ;;
      seed=*)    parse_run_int seed "$value";    export EXACTLEARNER_SAMPLER_SEED="$value" ;;
      pacseed=*) parse_run_int pacseed "$value"; export EXACTLEARNER_PAC_SEED="$value" ;;
      # Acted on by submit.sh; the job itself is always one repeat.
      repeats=*) parse_run_int repeats "$value"; REPEATS="$value" ;;
      *) die "bad parameter '$arg'. Use: $RUN_ARGS_USAGE" ;;
    esac
  done
  [[ "$REPEATS" -ge 1 ]] || die "repeats=$REPEATS: expected 1 or more"

  # Whether precomputation runs, for naming the log folder. Unset is the
  # launcher's default, skipPrecomputation=false, so it runs.
  PRECOMP="${precomp:-true}"

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
  RUN_ARGS_SUMMARY=""
  [[ "$CACHE_MODE" != shared ]] && RUN_ARGS_SUMMARY+=" cache=$CACHE_MODE"
  [[ -n "$precomp"  ]] && RUN_ARGS_SUMMARY+=" precomp=${PRECOMP_LABEL:-$precomp}"
  [[ "$evaluate" == true  ]] && RUN_ARGS_SUMMARY+=" eval=baris"
  [[ "$evaluate" == false ]] && RUN_ARGS_SUMMARY+=" eval=none"
  [[ -n "${EXACTLEARNER_SAMPLER:-}"      ]] && RUN_ARGS_SUMMARY+=" sampler=$EXACTLEARNER_SAMPLER"
  [[ -n "${EXACTLEARNER_BUDGET_MODE:-}"  ]] && RUN_ARGS_SUMMARY+=" budget=$EXACTLEARNER_BUDGET_MODE"
  [[ -n "${EXACTLEARNER_RESUME:-}"       ]] && RUN_ARGS_SUMMARY+=" resume=$EXACTLEARNER_RESUME"
  [[ -n "${EXACTLEARNER_SAMPLER_SEED:-}" ]] && RUN_ARGS_SUMMARY+=" seed=$EXACTLEARNER_SAMPLER_SEED"
  [[ -n "${EXACTLEARNER_PAC_SEED:-}"     ]] && RUN_ARGS_SUMMARY+=" pacseed=$EXACTLEARNER_PAC_SEED"
  [[ "$REPEATS" -gt 1 ]] && RUN_ARGS_SUMMARY+=" repeats=$REPEATS"
  RUN_ARGS_SUMMARY="${RUN_ARGS_SUMMARY# }"
  RUN_ARGS_SUMMARY="${RUN_ARGS_SUMMARY:-defaults}"
  return 0
}

RUN_ARGS_USAGE="precomp=true|false|reuse eval=baris|none cache=shared|fresh|<path> sampler=weighted|unweighted|pac budget=global|per-round resume=true|false seed=N pacseed=N repeats=N"

# Called by run_experiment.sh only, once the job id is known. cache=fresh gets a
# file of its own per job, so the run pays for every query it asks and its timings
# stand alone -- the shared cache is keyed by (model, system, query) and not by
# ontology or run, so a rerun of the same configuration replays the previous
# run's answers. The shared cache is never touched, moved or deleted by this.
resolve_cache_path() {
  [[ "${CACHE_MODE:-shared}" == fresh ]] || return 0
  export EXACTLEARNER_CACHE="cache-fresh-${SLURM_JOB_ID:-$$}.sqlite3"
}

parse_run_int() {
  [[ "$2" =~ ^-?[0-9]+$ ]] || die "$1=$2: expected an integer"
}
