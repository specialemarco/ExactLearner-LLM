#!/bin/bash
# Rebuild target/classes. Run from the repo root on a login node.
# `clean` is not optional: without it Maven prints "Nothing to compile" and
# exits 0 after real edits, and the job then runs your old classes.

source scripts/experiment.env
source scripts/modules.sh
load_modules build || exit 1

find src -type d -name .ipynb_checkpoints -exec rm -rf {} + 2>/dev/null

out=$(mvn -o -DskipTests clean compile 2>&1) || { echo "$out"; echo "BUILD FAILED"; exit 1; }
echo "BUILD SUCCESS"
