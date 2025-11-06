#!/usr/bin/env bash
set -euo pipefail

LOG_DIR="${LOG_DIR:-analysis}"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/gradle_build_$(date +%Y%m%d_%H%M%S).log"

set +e
./gradlew :app:clean | tee "$LOG_FILE"
CLEAN_EXIT=${PIPESTATUS[0]}
if [[ $CLEAN_EXIT -ne 0 ]]; then
  echo "[build.sh] CLEAN phase failed with code $CLEAN_EXIT" | tee -a "$LOG_FILE"
  EXIT_CODE=$CLEAN_EXIT
else
  ./gradlew -Dorg.gradle.jvmargs="-Xmx1024m" :app:bootJar | tee -a "$LOG_FILE"
  EXIT_CODE=${PIPESTATUS[0]}
fi
set -e

# Non-fatal post-analysis (optional)
if command -v python3 >/dev/null 2>&1; then
  PY=python3
else
  PY=python
fi
if [[ -f "scripts/analyze_build_output.py" ]]; then
  $PY scripts/analyze_build_output.py --log "$LOG_FILE" --code-root "$(pwd)" || true
fi

exit ${EXIT_CODE:-0}

# Persist error patterns DB (improved)
if [[ -f "scripts/persist_build_error_patterns.py" ]]; then
  $PY scripts/persist_build_error_patterns.py || true
fi
