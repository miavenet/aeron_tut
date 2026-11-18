#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
case "${1:-}" in starter|completion|independent|solution) ;; *) echo 'usage: prepare.sh starter|completion|independent|solution' >&2; exit 2;; esac
mkdir -p "$ROOT/labs/l04/work/backups"
if [[ -f "$ROOT/labs/l04/work/ArchiveWork.java" ]]; then cp "$ROOT/labs/l04/work/ArchiveWork.java" "$ROOT/labs/l04/work/backups/ArchiveWork.java"; fi
cp "$ROOT/labs/l04/$1/ArchiveWork.java" "$ROOT/labs/l04/work/ArchiveWork.java"
printf 'Prepared l04/work from %s\n' "$1"
