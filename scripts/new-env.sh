#!/usr/bin/env bash
# Add an environment (e.g. your company's SIT/UAT):   ./scripts/new-env.sh sit [from-env]
#   copies config/env/<from-env>.properties (default qa) to config/env/<name>.properties, then runs the doctor.
#   Machine-only values (your proxy password variable names, local URLs) can go in config/env/<name>.local.properties
#   which is git-ignored and overrides the shared file.
set -euo pipefail
cd "$(dirname "$0")/.."
NAME="${1:?usage: scripts/new-env.sh <name> [copy-from-env]}"
FROM="${2:-qa}"
[[ "$NAME" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "name may only contain letters, digits, _ and -"; exit 1; }
DEST="config/env/$NAME.properties"
[ -e "$DEST" ] && { echo "$DEST already exists"; exit 1; }
sed "1s/.*/# ===== $NAME environment =====  (run with -Denv=$NAME)/" "config/env/$FROM.properties" > "$DEST"
echo "Created $DEST from $FROM. Edit baseUri, auth.* and the company network section, then:"
echo "  java -jar target/api-test-platform.jar doctor --env $NAME"
