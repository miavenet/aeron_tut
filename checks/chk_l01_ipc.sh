#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
workspace="$(cat)"
for check in core controlled handler; do
  if output=$(printf '%s\n' "$workspace" | python3 -B "$ROOT/checks/l01/run.py" l01 "$check"); then
    :
  else
    status=$?
    printf '%s\n' "$output"
    exit "$status"
  fi
done
printf '%s\n' "$output"
