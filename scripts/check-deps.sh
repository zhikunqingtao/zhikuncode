#!/bin/bash
# CI dependency verification script
# Usage: ./scripts/check-deps.sh

set -euo pipefail

LOCK_FILE="python-service/requirements.lock"

if [ ! -f "$LOCK_FILE" ]; then
    echo "ERROR: $LOCK_FILE not found"
    exit 1
fi

pip freeze > /tmp/current-freeze.txt
diff <(grep -v '^#' "$LOCK_FILE" | grep -v '^$' | sort) \
     <(grep -v '^#' /tmp/current-freeze.txt | grep -v '^$' | sort) \
     && echo "Dependencies match lock file" || { echo "ERROR: Dependency mismatch detected"; exit 1; }
