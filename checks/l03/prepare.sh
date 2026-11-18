#!/bin/bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
case "${1:-}" in starter|completion|independent) ;; *) echo 'Choose starter, completion, or independent' >&2; exit 2 ;; esac
mkdir -p "$root/labs/l03/work/backups"
if [[ -f "$root/labs/l03/work/Diagnostics.java" ]]; then cp "$root/labs/l03/work/Diagnostics.java" "$root/labs/l03/work/backups/Diagnostics.java"; fi
cp "$root/labs/l03/$1/Diagnostics.java" "$root/labs/l03/work/Diagnostics.java"
echo 'Prepared labs/l03/work/Diagnostics.java; previous work is in work/backups/.'
