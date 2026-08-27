#!/bin/bash
# BROKEN (audited 2026-08-27).
#
# This script does not run as-is: every Java class and half the configs it names were
# renamed or removed by ca94e39 ("wip: refactoring", 2025-05-09) and it was never rewired.
# It is kept rather than deleted because the logic is still valid -- the 2x2 matrix it
# drives (EXACTLEARNER_SPLIT x EXACTLEARNER_DESATURATE -> none/split/desaturate/simplify)
# maps onto env vars the current code still reads: see AxiomSimplifier.java:29,50 and
# LLMEngine.java:146. So this is a stale harness for a live experiment, not dead code.
#
# Broken links, and what each should become:
#   org.experiments.exp2.LaunchLLMLearner  -> org.experiments.LaunchLLMLearner
#   org.analysis.exp2.ResultAnalyzer       -> org.analysis.ResultAnalyzer
#         (a rename, only the package line changed -- the class itself still exists and
#          this script is the only thing that tries to invoke it)
#   statementsQueryingConf.yml             -> org/configurations/test/statementsQueryingConf.yml
#   statementsQueryingConf2.yml            -> org/configurations/test/statementsQueryingConf2.yml
#   statementsQueryingConfAdvanced.yml     -> MISSING, no replacement found
#   statementsQueryingConfTrueFalse.yml    -> MISSING, no replacement found
#
# Fixing the four resolvable references would make this runnable again; the two missing
# configs need to be recreated or their two mvn lines dropped.
cd $1 || { exit 127; }

mkdir results/ontologies/temp_store
mkdir analysis/temp_store

for file in results/ontologies/*; do
  [ -f "$file" ] && mv "$file" results/ontologies/temp_store
done

for file in analysis/*; do
  [ -f "$file" ] && mv "$file" analysis/temp_store
done

function run_test {
  export EXACTLEARNER_OLLAMA_URL="http://localhost:11434/api/generate"
  export EXACTLEARNER_SPLIT=$1
  export EXACTLEARNER_DESATURATE=$2

  mvn clean install -DskipTests
  mvn exec:java -Dexec.mainClass="org.experiments.exp2.LaunchLLMLearner" -Dexec.args="src/main/java/org/configurations/statementsQueryingConf.yml"
  mvn exec:java -Dexec.mainClass="org.experiments.exp2.LaunchLLMLearner" -Dexec.args="src/main/java/org/configurations/statementsQueryingConf2.yml"
  mvn exec:java -Dexec.mainClass="org.experiments.exp2.LaunchLLMLearner" -Dexec.args="src/main/java/org/configurations/statementsQueryingConfAdvanced.yml"
  mvn exec:java -Dexec.mainClass="org.experiments.exp2.LaunchLLMLearner" -Dexec.args="src/main/java/org/configurations/statementsQueryingConfTrueFalse.yml"

  mvn exec:java -Dexec.mainClass="org.analysis.exp2.ResultAnalyzer" -Dexec.args="src/main/java/org/configurations/statementsQueryingConf.yml"

  mkdir -p results/ontologies/$3

  for file in results/ontologies/*; do
    [ -f "$file" ] && mv "$file" results/ontologies/$3
  done

  mkdir -p analysis/$3

  for file in analysis/*; do
    [ -f "$file" ] && mv "$file" analysis/$3
  done
}

run_test "false" "false" "none"

run_test "true" "false" "split"

run_test "false" "true" "desaturate"

run_test "true" "true" "simplify"

mv results/ontologies/temp_store/* results/ontologies
rmdir results/ontologies/temp_store

mv analysis/temp_store/* analysis/
rmdir analysis/temp_store
