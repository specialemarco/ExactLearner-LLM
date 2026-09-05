#!/bin/bash
# Rebuild target/classes. Run from the repo root on a login node.
# `clean` is not optional: without it Maven prints "Nothing to compile" and
# exits 0 after real edits, and the job then runs your old classes.

module load Java/21.0.8
module load Maven/3.6.3

find src -type d -name .ipynb_checkpoints -exec rm -rf {} + 2>/dev/null

out=$(mvn -o -DskipTests clean compile 2>&1) || { echo "$out"; echo "BUILD FAILED"; exit 1; }
echo "BUILD SUCCESS"
