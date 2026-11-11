#!/usr/bin/env bash
# Onboard ANY Swagger/OpenAPI (YAML/JSON), Postman collection or Markdown API doc in one go:
#   ./scripts/new-spec.sh path/to/your-api.yaml [env]
# It copies the file into specs/, builds the jar and lets the AI design the JSON test cases (or prepares
# the request for your IDE agent when no LLM token is set), then prints what is left to configure.
# Safe to re-run after the spec changes (approvals of unchanged cases are kept).
set -euo pipefail
cd "$(dirname "$0")/.."

SRC="${1:?usage: ./scripts/new-spec.sh <spec file or URL> [env]}"
ENV="${2:-qa}"
JAR=target/api-test-platform.jar

if [[ "$SRC" =~ ^https?:// ]]; then
  SPEC="$SRC"
else
  [ -f "$SRC" ] || { echo "Spec not found: $SRC"; exit 2; }
  mkdir -p specs
  NAME="$(basename "$SRC")"
  if [ "$(cd "$(dirname "$SRC")" && pwd)/$NAME" != "$(pwd)/specs/$NAME" ]; then cp "$SRC" "specs/$NAME"; fi
  SPEC="specs/$NAME"
fi

echo "== 1/2 Build"
[ -f "$JAR" ] || mvn -q -DskipTests package

echo "== 2/2 AI designs the test cases (LLM token), or prepares the request for your IDE agent"
java -jar "$JAR" generate --spec "$SPEC" || true
java -jar "$JAR" status

cat <<NEXT

Next:
  0. No LLM token? In your IDE agent: /ai testcases --spec $SPEC   (then the cases appear in the UI)
  1. Fill in baseUri, auth.* and testdata.* in config/env/$ENV.properties, export your token
  2. Approve cases:   PLATFORM_USERS="you:pass:REVIEWER" ./scripts/serve.sh   -> http://localhost:8080
  2b. Check the env:  java -jar $JAR doctor --env $ENV   (proxy, company CA, secrets, reachability)
  3. Automation (AI): UI button "1 Generate automation (AI)" (LLM token on the server), or in your IDE agent: /ai automation
     then review the diff and: mvn -q test -Dcucumber.execution.dry-run=true
  3b. Run:            UI "2 Execute tests", or ./scripts/run-tests.sh $ENV "@smoke"; failures: /rca (AI root cause analysis)
  4. Report:          open reports/index.html#rca
NEXT
