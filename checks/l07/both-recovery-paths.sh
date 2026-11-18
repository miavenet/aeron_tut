#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
workspace="$(cat)"
if output=$(printf '%s\n' "$workspace" | "$ROOT/checks/chk_l07_snapshot.sh"); then
  :
else
  status=$?
  printf '%s\n' "$output"
  exit "$status"
fi
if output=$(printf '%s\n' "$workspace" | "$ROOT/checks/l07/no-snapshot.sh"); then
  :
else
  status=$?
  printf '%s\n' "$output"
  exit "$status"
fi
printf '%s\n' "$output"
