#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
case "${1:-}" in starter|completion|independent|solution) ;; *) echo 'usage: prepare.sh starter|completion|independent|solution' >&2; exit 2;; esac
mkdir -p "$ROOT/labs/l01/work/backups"
if [[ -f "$ROOT/labs/l01/work/Ownership.java" ]]; then cp "$ROOT/labs/l01/work/Ownership.java" "$ROOT/labs/l01/work/backups/Ownership.java"; fi
cp "$ROOT/labs/l01/$1/Ownership.java" "$ROOT/labs/l01/work/Ownership.java"
printf 'Prepared l01/work from %s\n' "$1"
