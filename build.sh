#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

mode="${1:-package}"
if [[ "$#" -gt 0 ]]; then shift; fi

case "$mode" in
    package) tasks=(clean build) ;;
    install) tasks=(clean build publishToMavenLocal) ;;
    *) echo "Usage: ./build.sh {package|install} [test] [gradle options...]" >&2; exit 1 ;;
esac

args=()
run_tests=false
for arg in "$@"; do
    if [[ "$arg" == "test" ]]; then run_tests=true; else args+=("$arg"); fi
done
if [[ "$run_tests" != "true" ]]; then args+=(-x test); fi

./gradlew "${tasks[@]}" --parallel --max-workers 8 "${args[@]}"
