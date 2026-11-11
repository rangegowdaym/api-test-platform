#!/usr/bin/env bash
# Onboard ANY Swagger/OpenAPI (YAML/JSON), Postman collection or Markdown API doc in one go:
#   ./scripts/new-spec.sh path/to/your-api.yaml [env]
# It copies the file into specs/, builds the jar, generates the service-layer Api classes, rebuilds,
# generates the JSON test cases and prints the config keys you still have to set.
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

echo "== 1/5 Build"
[ -f "$JAR" ] || mvn -q -DskipTests package

echo "== 2/5 Read the spec"
java -jar "$JAR" ingest --spec "$SPEC" --env "$ENV"

echo "== 3/5 Service layer: <Tag>Api + <Tag>Steps per OpenAPI tag"
java -jar "$JAR" services --spec "$SPEC"

echo "== 4/5 Rebuild with the generated classes"
mvn -q -DskipTests package

echo "== 5/5 JSON test cases"
java -jar "$JAR" generate --spec "$SPEC"
java -jar "$JAR" status

cat <<NEXT

Next:
  1. Fill in the keys listed under "Config check" in config/env/$ENV.properties, export your token
  2. Approve cases:   PLATFORM_USERS="you:pass:REVIEWER" ./scripts/serve.sh   -> http://localhost:8080
  2b. Check the env:  java -jar $JAR doctor --env $ENV   (proxy, company CA, secrets, reachability)
  3. In the UI:       1 Generate features -> 2 Generate services & steps -> 3 Execute tests -> 4 Open report
     or from a shell:  java -jar $JAR features && ./scripts/run-tests.sh $ENV "@smoke"
  4. Report:          open reports/index.html#rca
NEXT
