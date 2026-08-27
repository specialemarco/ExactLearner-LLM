#!/bin/bash
# BROKEN (audited 2026-08-27).
#
# Unlike scripts/testSimplification.sh -- whose target was merely renamed and still exists
# -- everything this script points at was deleted by ca94e39 ("wip: refactoring",
# 2025-05-09) with no successor. Kept only as a record of the exp3 workflow; there is
# nothing left to repoint it at.
#
# Broken links, all three unresolvable:
#   cd /home/dev/persistent/ExactLearner    -> path does not exist (hardcoded to a machine
#                                              that is not this one)
#   org.analysis.exp3.PartialResultAnalyzer -> deleted, 146 lines. Confirmed a pure delete,
#                                              not a rename, at a 30% rename threshold;
#                                              PartialResultBase and ResultCheck went with
#                                              it. No successor class exists.
#   org/configurations/exp3/medical/*.yml   -> directory does not exist
#
# Reviving this means rewriting it against the current analysis code, not editing names.
if [ $# -lt 2 ]
then
  exit 1;
fi

cd /home/dev/persistent/ExactLearner || { exit 127; }
mvn clean install -DskipTests
mvn exec:java -Dexec.mainClass="org.analysis.exp3.PartialResultAnalyzer" -Dexec.args="src/main/java/org/configurations/exp3/medical/Llama3ENLP.yml $1 $2"
mvn exec:java -Dexec.mainClass="org.analysis.exp3.PartialResultAnalyzer" -Dexec.args="src/main/java/org/configurations/exp3/medical/MixtralMistralNLP.yml $1 $2"
