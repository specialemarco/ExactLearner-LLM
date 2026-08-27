#!/bin/bash
# BROKEN, BUT TRIVIALLY REPAIRABLE (audited 2026-08-27).
#
# One stale reference, and it is a rename rather than a deletion, so the target still exists:
#   org.experiments.exp2.LaunchLLMLearner  ->  org.experiments.LaunchLLMLearner
# Git recorded it in ca94e39 ("wip: refactoring", 2025-05-09) as
#     org/experiments/{exp2 => }/LaunchLLMLearner.java | 2 +-
# i.e. only the package line changed. This script was never rewired, so the mvn exec:java
# call at the bottom fails to resolve its main class -- that single name is the only thing
# standing between this script and working again.
#
# Everything else here is still valid: EXACTLEARNER_OLLAMA_URL is read by OllamaBridge.java
# and BatchPrewarmer.java, and the YAML it generates into tmp/test_file.yml matches the
# current Configuration fields (models / ontologies / system / maxTokens / queryFormat / type).
#
# Note it takes two args -- $1 the repo root to cd into, $2 a directory of ontology modules --
# and samples 10 of them deterministically (shuf --random-source=<(yes 42)). GNU shuf only;
# on macOS this needs coreutils' gshuf.
cd $1 || { exit 127; }

export EXACTLEARNER_OLLAMA_URL="http://localhost:11434/api/generate"

mkdir -p tmp

rm tmp/modules.txt

mvn clean install -DskipTests

for file in $(find "$2" -type f | sort | shuf --random-source=<(yes 42) -n 10); do
    echo "Running test on $file"

    echo "$file" >> tmp/modules.txt

    # Yes, i know this looks bad
    echo "models:
  - \"mistral\"
ontologies:
  - \"$file\"
system: >
  You need to classify the following statements as True or False. The statement will be provided in either Manchester OWL syntax or natural language. Strictly follow these guidelines:
  1. answer with only True or False;
  2. entities with has part relation are not in a subclass relation;
  3. take a deep breath before answering;
  4. if you are unsure about the classification, answer with False.
maxTokens: 2
queryFormat: \"nlp\"
type: \"statementsQuerying\"
" > tmp/test_file.yml
    
    timeout 2h mvn exec:java -Dexec.mainClass="org.experiments.exp2.LaunchLLMLearner" -Dexec.args="tmp/test_file.yml"
done
