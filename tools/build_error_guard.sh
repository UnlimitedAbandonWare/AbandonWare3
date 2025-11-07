#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PAT_FILE="$ROOT_DIR/tools/build_error_patterns.txt"
LOG_CANDIDATES=(
  "$ROOT_DIR/build/logs/build.log"
  "$ROOT_DIR/build/reports/tests/test/index.html"
  "$ROOT_DIR/build/tmp/compileJava/previous-compilation-data.bin"
)
# Also scan source tree for banned tokens (e.g., {스터프3})
echo "[guard] scanning for known bad patterns..."
rc=0
if [[ -f "$PAT_FILE" ]]; then
  for log in "${LOG_CANDIDATES[@]}"; do
    if [[ -f "$log" ]]; then
      while IFS= read -r pat; do
        [[ -z "$pat" || "$pat" =~ ^# ]] && continue
        if grep -E -n --color=never "$pat" "$log" >/dev/null 2>&1; then
          echo "::error file=$log:: matched pattern: $pat"
          rc=1
        fi
      done < "$PAT_FILE"
    fi
  done
fi
# banned tokens in sources
if grep -RIn --color=never "\{스터프3\}" "$ROOT_DIR/src" >/dev/null 2>&1; then
  echo "[guard] Found banned token {스터프3} in sources – sanitizing..."
  find "$ROOT_DIR/src" -type f -name "*.*" -print0 | xargs -0 sed -i.bak 's/{스터프3}//g' || true
fi
exit $rc
