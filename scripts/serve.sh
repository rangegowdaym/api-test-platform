#!/usr/bin/env bash
# Start the approval UI on http://localhost:${PORT:-8080}
#   PLATFORM_USERS="alice:secret:REVIEWER,bob:secret:VIEWER" ./scripts/serve.sh
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f target/api-test-platform.jar ] || mvn -q -DskipTests package
: "${PLATFORM_USERS:?set PLATFORM_USERS=name:password:REVIEWER (comma separated for more users)}"
exec java -jar target/api-test-platform.jar serve
