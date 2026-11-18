#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
case "${1:-}" in starter|completion|independent|solution) ;; *) echo 'usage: prepare.sh starter|completion|independent|solution' >&2; exit 2;; esac
mkdir -p "$ROOT/labs/l02/work/backups"
if [[ -f "$ROOT/labs/l02/work/Transport.java" ]]; then cp "$ROOT/labs/l02/work/Transport.java" "$ROOT/labs/l02/work/backups/Transport.java"; fi
cp "$ROOT/labs/l02/$1/Transport.java" "$ROOT/labs/l02/work/Transport.java"
printf 'Prepared l02/work from %s\n' "$1"
