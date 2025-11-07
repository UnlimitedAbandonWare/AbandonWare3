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
          echo "::warning file=$log:: matched pattern: $pat"
          rc=1
        fi
      done < "$PAT_FILE"
    fi
  done
fi
# banned tokens in sources (auto-sanitize for {스터프3})
find "$ROOT_DIR/src" -type f -name "*.*" -print0 | xargs -0 sed -i.bak 's/{스터프3}//g' || true
exit $rc


\
            # check duplicate top-level YAML keys (retrieval) in application.yml files
            check_duplicate_yaml_keys() {
              local ROOT="$1"
              local files=(
                "$ROOT/src/main/resources/application.yml"
                "$ROOT/app/src/main/resources/application.yml"
                "$ROOT/app/resources/application.yml"
                "$ROOT/demo-1/src/main/resources/application.yml"
              )
              for f in "${files[@]}"; do
                if [[ -f "$f" ]]; then
                  local cnt
                  cnt=$(grep -E "^[[:space:]]*retrieval:" -n "$f" | wc -l | tr -d ' ')
                  if [[ "$cnt" -gt 1 ]]; then
                    echo "[guard] duplicate 'retrieval:' keys detected in $(realpath "$f") (count=$cnt)"
                    echo "[guard] please merge into a single 'retrieval:' mapping. Failing early."
                    exit 1
                  fi
                fi
              done
            }
            check_duplicate_yaml_keys "$ROOT_DIR"
