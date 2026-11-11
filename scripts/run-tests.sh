#!/usr/bin/env bash
# Run the API tests, then RCA + report. Exit code = test result (non-zero if any scenario failed).
#   ./scripts/run-tests.sh [env] [tag-expression] [--llm]
#   ./scripts/run-tests.sh qa "@smoke"
set -uo pipefail
cd "$(dirname "$0")/.."

ENV="${1:-qa}"
TAGS="${2:-not @wip}"
LLM="${3:-}"
JAR=target/api-test-platform.jar

[ -f "$JAR" ] || mvn -q -DskipTests package || { echo "build failed"; exit 2; }

if ! ls src/test/resources/features/generated/*.feature >/dev/null 2>&1; then
  echo "No generated features. Approve cases in the UI, then let the AI write them: /ai automation (IDE agent) or java -jar $JAR ai automation"
  exit 3
fi

echo "== Running API tests: env=$ENV tags=\"$TAGS\""
mvn -q test -Denv="$ENV" -Dcucumber.filter.tags="$TAGS" ${THREADS:+-Dthreads=$THREADS}
MVN_EXIT=$?

echo "== RCA + report"
java -jar "$JAR" post-run $LLM

FAILED=$(jq -r '.totals.FAILED // 0' target/run-summary.json 2>/dev/null || echo 0)
echo "Report: file://$(pwd)/reports/index.html#dashboard   (root causes: #rca)"
if [ "$MVN_EXIT" -ne 0 ] && [ ! -f target/run-summary.json ]; then exit "$MVN_EXIT"; fi
[ "$FAILED" = "0" ] || exit 1
