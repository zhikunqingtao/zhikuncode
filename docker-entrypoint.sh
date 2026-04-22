#!/bin/bash
# =============================================================================
# ZhikunCode — Container Entrypoint
# Starts Java backend (which internally manages the Python subprocess)
# =============================================================================
set -e

# ---- Resolve Python virtual environment ----
export PATH="/app/python-service/.venv/bin:${PATH}"
export PYTHONPATH="/app/python-service/src"

# ---- Ensure data directories exist ----
mkdir -p /app/data /app/workspace

# ============================================================
# Pre-flight checks
# ============================================================

# 1. Validate LLM_API_KEY
if [ -z "$LLM_API_KEY" ] || [ "$LLM_API_KEY" = "your-api-key-here" ]; then
    echo "============================================================"
    echo "  ERROR: LLM_API_KEY is not configured!"
    echo ""
    echo "  Please set LLM_API_KEY in your .env file or pass it via"
    echo "  docker run -e LLM_API_KEY=your-actual-key"
    echo ""
    echo "  See .env.example for configuration details."
    echo "============================================================"
    exit 1
fi

# 2. Print configuration summary
MASKED_KEY="${LLM_API_KEY:0:6}****${LLM_API_KEY: -4}"
echo "============================================================"
echo "  ZhikunCode — Starting"
echo "============================================================"
echo "  LLM API Key:    $MASKED_KEY"
echo "  LLM Base URL:   ${LLM_BASE_URL:-default (DashScope)}"
echo "  Default Model:  ${LLM_DEFAULT_MODEL:-default}"
echo "  Log Directory:  ${LOG_DIR:-/app/log}"
echo "  Private Network: ${ALLOW_PRIVATE_NETWORK:-false}"
echo "============================================================"

# 3. Check writable directories
for dir in "${LOG_DIR:-/app/log}" /app/data; do
    if [ -d "$dir" ] && [ ! -w "$dir" ]; then
        echo "WARNING: Directory $dir is not writable by user $(whoami)"
    fi
done

# ---- Startup banner ----
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  ZhikunCode — Starting..."
echo "  Java:   $(java --version 2>&1 | head -1)"
echo "  Python: $(python --version 2>&1)"
echo "  Profile: ${SPRING_PROFILES_ACTIVE:-default}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# ---- Launch Java application ----
# PythonProcessManager inside Spring Boot will start the Python subprocess automatically
exec java \
    ${JAVA_OPTS} \
    -Djava.security.egd=file:/dev/./urandom \
    -Dspring.profiles.active="${SPRING_PROFILES_ACTIVE:-production}" \
    -jar /app/app.jar \
    "$@"
