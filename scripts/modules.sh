#!/bin/bash

#------------------------- Cluster Modules -------------------------------------------

# The module stack for each cluster, sourced by run_experiment.sh, rebuild.sh and
# fetch_model.sh. CLUSTER comes from scripts/experiment.env (fox if unset).
#
#   load_modules run     Java to run the learner; Python, torch and vLLM to serve
#   load_modules build   Java and Maven (login node)
#   load_modules fetch   Python, huggingface_hub and vLLM, for the support check

load_modules() {
  local purpose="$1"
  module purge
  case "${CLUSTER:-fox}" in

    fox)
      if [[ "$purpose" == build ]]; then
        module load Java/21.0.8 Maven/3.6.3
        return
      fi
      [[ "$purpose" == run ]] && module load Java/21.0.8
      # Only ec30 members can read this module tree; others set MODULE_TREE.
      module use -a "${MODULE_TREE:-/fp/projects01/ec30/software/easybuild/modules/all/}"
      module load nlpl-pytorch/2.6.0-foss-2024a-cuda-12.6.0-Python-3.12.3
      [[ "$purpose" == run ]] && module load nlpl-accelerate/1.9.0-foss-2024a-Python-3.12.3
      module load Transformers/4.57.1-gfbf-2024a
      module load nlpl-vllm/0.8.2-foss-2024a-Python-3.12.3
      ;;

    olivia)
      # The GPU nodes are GH200 (aarch64) and NRIS/GPU is built for them. Its
      # vLLM module is an hpc-container-wrapper install that brings torch and
      # transformers. purge keeps the sticky init-NRIS but drops NRIS/GPU.
      # No Maven module on Olivia: a tarball unpacked in $HOME (MAVEN_HOME to override).
      if [[ "$purpose" == build ]]; then
        module load NRIS/CPU Java/17.0.15
        export PATH="${MAVEN_HOME:-$HOME/apache-maven-3.9.12}/bin:$PATH"
        command -v mvn >/dev/null || { echo "ERROR: no mvn in ${MAVEN_HOME:-$HOME/apache-maven-3.9.12}/bin" >&2; return 1; }
        return
      fi
      module load NRIS/GPU Java/17.0.15 vLLM/0.11.0
      ;;

    *) echo "ERROR: unknown CLUSTER=$CLUSTER (fox or olivia)" >&2; return 1 ;;
  esac
  export PYTHONNOUSERSITE=1   # a `pip install --user` would outrank the modules' torch and vllm
}
