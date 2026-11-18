#!/bin/bash
set -euo pipefail
exec python3 "$(dirname "$0")/l03/run.py" core
