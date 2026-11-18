#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
workspace="$(cat)"
if output=$(printf '%s\n' "$workspace" | "$ROOT/checks/l05/boundary.sh"); then
  :
else
  status=$?
  printf '%s\n' "$output"
  exit "$status"
fi
if output=$(printf '%s\n' "$workspace" | "$ROOT/checks/l05/policy.sh"); then
  :
else
  status=$?
  printf '%s\n' "$output"
  exit "$status"
fi
printf '%s\n' "$output"
