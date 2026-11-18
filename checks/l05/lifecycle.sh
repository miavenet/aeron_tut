#!/bin/bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
exec python3 -B "$ROOT/checks/l05/run.py" lifecycle
