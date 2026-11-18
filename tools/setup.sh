#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
JAR="$ROOT/labs/lib/aeron-all-1.54.1.jar"
SHA1=f08ea4580129ba17f6f798dc6eb234c96fe0f5ea
SHA256=f389f4b0a6fc330930d8645b5c001b8acceae3c25648a1ff2b3513cc12c53a9a
MODE=${1:-setup}
[[ $# -le 1 && ( "$MODE" = setup || "$MODE" = --check || "$MODE" = --jar-only ) ]] || { echo 'usage: tools/setup.sh [--check|--jar-only]' >&2; exit 2; }
fail() {
  echo "SETUP PROBLEM: $1" >&2
  echo 'macOS: install a JDK 17 or newer (tested with 26.0.1), put java and javac on PATH, then run bash tools/setup.sh --check.' >&2
  echo 'Linux: install a JDK 17 or newer (tested with 26.0.1), put java and javac on PATH, then run bash tools/setup.sh --check.' >&2
  exit 2
}
verify_jar() {
  [[ -f "$1" ]] && [[ "$(shasum "$1" | awk '{print $1}')" = "$SHA1" ]] &&
    [[ "$(shasum -a 256 "$1" | awk '{print $1}')" = "$SHA256" ]]
}
if [[ ! -f "$JAR" && "$MODE" = setup ]]; then
  mkdir -p "$(dirname "$JAR")"
  TMP_JAR=$(mktemp "$ROOT/labs/lib/.download.XXXXXX")
  trap 'rm -f "$TMP_JAR"' EXIT
  curl --fail --location --retry 2 --connect-timeout 10 --max-time 120 \
    'https://repo1.maven.org/maven2/io/aeron/aeron-all/1.54.1/aeron-all-1.54.1.jar' -o "$TMP_JAR" || {
      echo 'SETUP PROBLEM: Aeron jar download failed.' >&2
      echo 'macOS and Linux: restore access to repo1.maven.org, then run bash tools/setup.sh again.' >&2
      exit 2
    }
  verify_jar "$TMP_JAR" || fail 'downloaded jar hash mismatch; remove labs/lib/aeron-all-1.54.1.jar if present, then run bash tools/setup.sh.'
  mv "$TMP_JAR" "$JAR"
fi
verify_jar "$JAR" || fail 'missing jar or hash mismatch; run bash tools/setup.sh to fetch the pinned artifact.'
[[ "$MODE" = --jar-only ]] && exit 0
JAVA_BIN=${JAVA_HOME:+$JAVA_HOME/bin/}java
JAVAC_BIN=${JAVA_HOME:+$JAVA_HOME/bin/}javac
JAVA_VERSION=$("$JAVA_BIN" -version 2>&1) || fail "cannot run $JAVA_BIN."
JAVAC_VERSION=$("$JAVAC_BIN" -version 2>&1) || fail "cannot run $JAVAC_BIN."
JAVA_MAJOR=$(printf '%s\n' "$JAVA_VERSION" | sed -nE '1s/.*version "([0-9]+).*/\1/p')
JAVAC_MAJOR=$(printf '%s\n' "$JAVAC_VERSION" | sed -nE '1s/^javac ([0-9]+).*/\1/p')
[[ -n "$JAVA_MAJOR" && -n "$JAVAC_MAJOR" ]] || fail 'could not read java and javac versions.'
# The pinned jar's io/aeron/Aeron.class has class-file major 61 (Java 17).
[[ "$JAVA_MAJOR" -ge 17 && "$JAVAC_MAJOR" -ge 17 ]] || fail 'the pinned Aeron jar requires Java 17 or newer (io/aeron/Aeron.class major 61).'
[[ "$JAVA_MAJOR" = "$JAVAC_MAJOR" ]] || fail 'java and javac must use the same major version.'
if [[ "$JAVA_VERSION" != *'version "26.0.1"'* || "$JAVAC_VERSION" != 'javac 26.0.1' ]]; then
  echo "WARNING: tested with java and javac 26.0.1; you have $(printf '%s' "$JAVA_VERSION" | head -1) and $JAVAC_VERSION; if a lab fails, try 26.0.1. Other versions are untested."
fi
printf '%s\n' "$JAVA_VERSION" "$JAVAC_VERSION" "jar SHA-1=$SHA1" "jar SHA-256=$SHA256" 'SETUP PASS'
