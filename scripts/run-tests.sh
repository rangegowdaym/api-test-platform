#!/usr/bin/env bash
# Run the API tests, then AI RCA + report. Exit code = test result (non-zero if any scenario failed).
#   ./scripts/run-tests.sh [env] [tag-expression] [--heal]
#   ./scripts/run-tests.sh qa "@smoke"
#   --heal: the AI fixes automation/test-data failures (never expectations), then the tests run once more.
# AI steps use the LLM token when one is set; otherwise they are queued: type /ai in Copilot Chat (Agent mode).
set -uo pipefail
cd "$(dirname "$0")/.."

ENV="${1:-qa}"
TAGS="${2:-not @wip}"
HEAL="${3:-}"
JAR=target/api-test-platform.jar

[ -f "$JAR" ] || mvn -q -DskipTests package || { echo "build failed"; exit 2; }

if ! ls src/test/resources/features/generated/*.feature >/dev/null 2>&1; then
  echo "No generated features. Approve cases in the UI, then: java -jar $JAR ai automation  (no LLM token: then /ai in Copilot Chat)"
  exit 3
fi

run_tests() {
  echo "== Running API tests: env=$ENV tags=\"$TAGS\""
  mvn -q test -Denv="$ENV" -Dcucumber.filter.tags="$TAGS" ${THREADS:+-Dthreads=$THREADS}
  MVN_EXIT=$?
}

run_tests
echo "== AI RCA + report"
if [ "$HEAL" = "--heal" ]; then
  java -jar "$JAR" post-run --heal | tee target/post-run.log
  if grep -q "^heal (llm):" target/post-run.log && grep -q "src/test/" target/post-run.log; then
    echo "== Re-running after the AI heal (review the diff afterwards)"
    run_tests
    java -jar "$JAR" post-run
  fi
else
  java -jar "$JAR" post-run
fi

FAILED=$(jq -r '.totals.FAILED // 0' target/run-summary.json 2>/dev/null || echo 0)
echo "Report: file://$(pwd)/reports/index.html#dashboard   (root causes: #rca)"
if [ "$MVN_EXIT" -ne 0 ] && [ ! -f target/run-summary.json ]; then exit "$MVN_EXIT"; fi
[ "$FAILED" = "0" ] || exit 1
