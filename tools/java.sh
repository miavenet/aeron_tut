#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
"$ROOT/tools/setup.sh" --jar-only
CP="$ROOT/labs/lib/aeron-all-1.54.1.jar"
if [[ "${1:-}" = --classpath ]]; then
  [[ $# -ge 3 ]] || { echo 'usage: java.sh [--classpath classes] [JVM arguments] Main [arguments]' >&2; exit 2; }
  CP="$2:$CP"
  shift 2
fi
JAVA_BIN=${JAVA_HOME:+$JAVA_HOME/bin/}java
JVM_ARGS=(--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED -cp "$CP")
exec "$JAVA_BIN" "${JVM_ARGS[@]}" "$@"
