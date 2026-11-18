#!/bin/bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
case "${1:-completion}" in
  completion|independent) variant=${1:-completion} ;;
  *) echo 'Use completion or independent' >&2; exit 2 ;;
esac
mkdir -p "$ROOT/labs/l05/work/backups"
if [[ -f "$ROOT/labs/l05/work/StateWork.java" ]]; then cp "$ROOT/labs/l05/work/StateWork.java" "$ROOT/labs/l05/work/backups/StateWork.java"; fi
cp "$ROOT/labs/l05/$variant/StateWork.java" "$ROOT/labs/l05/work/StateWork.java"
printf 'Prepared labs/l05/work/StateWork.java from %s\n' "$variant"
