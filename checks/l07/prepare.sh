#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
case "${1:-}" in completion|independent) ;; *) echo 'usage: prepare.sh completion|independent' >&2; exit 2;; esac
mkdir -p "$ROOT/labs/l07/work/backups"
if [[ -f "$ROOT/labs/l07/work/RequestWork.java" ]]; then cp "$ROOT/labs/l07/work/RequestWork.java" "$ROOT/labs/l07/work/backups/RequestWork.java"; fi
cp "$ROOT/labs/l07/$1/RequestWork.java" "$ROOT/labs/l07/work/RequestWork.java"
mkdir -p "$ROOT/labs/l07/work/backups"
if [[ -f "$ROOT/labs/l07/work/ClientWork.java" ]]; then cp "$ROOT/labs/l07/work/ClientWork.java" "$ROOT/labs/l07/work/backups/ClientWork.java"; fi
cp "$ROOT/labs/l07/$1/ClientWork.java" "$ROOT/labs/l07/work/ClientWork.java"
