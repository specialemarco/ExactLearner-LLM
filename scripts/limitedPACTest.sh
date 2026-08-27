#!/bin/bash
# BROKEN, AND NOT REPAIRABLE AS WRITTEN (audited 2026-08-27).
#
# Same failure as its sibling scripts/limitedPACResult.sh, and for the same reason: this is
# an exp3-era script, and ca94e39 ("wip: refactoring", 2025-05-09) deleted the whole exp3
# lineage outright rather than renaming it. Nothing it names still resolves.
#
# Broken links, all three unresolvable:
#   cd /home/dev/persistent/ExactLearner       -> path does not exist (hardcoded to a
#                                                 machine that is not this one)
#   org.experiments.exp3.experiment.PACLaunch  -> deleted, 96 lines. Confirmed a pure delete,
#                                                 not a rename, at a 30% rename threshold.
#                                                 It was a thin subclass of the old
#                                                 exp2.LaunchLLMLearner that overrode config
#                                                 loading and concept/role counting.
#   org/configurations/exp3/medical/*.yml      -> directory does not exist
#
# The WORK it did is not lost, only this entry point: PAC sampling now lives in org.pac.Pac,
# driven by org.experiments.LaunchLLMLearnerAInduced (which takes the same epsilon/delta the
# args below pass as 0.2 / 0.1). scripts/run_experiment.sh is the current, maintained path.
# Kept as a record of the exp3 workflow; reviving it means rewriting against those classes.
if [ $# -lt 2 ]
then
  exit 1;
fi

cd /home/dev/persistent/ExactLearner || { exit 127; }
mvn clean install -DskipTests
mvn exec:java -Dexec.mainClass="org.experiments.exp3.experiment.PACLaunch" -Dexec.args="src/main/java/org/configurations/exp3/medical/Llama3ENLP.yml 0.2 0.1 $1 $2"
mvn exec:java -Dexec.mainClass="org.experiments.exp3.experiment.PACLaunch" -Dexec.args="src/main/java/org/configurations/exp3/medical/MixtralMistralNLP.yml 0.2 0.1 $1 $2"
