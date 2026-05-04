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

# 1. Validate LLM configuration (multi-provider or legacy single-provider)
HAS_PROVIDER_KEY=false
if [ -n "$LLM_PROVIDER_DASHSCOPE_API_KEY" ] && [ "$LLM_PROVIDER_DASHSCOPE_API_KEY" != "your-dashscope-api-key-here" ]; then
    HAS_PROVIDER_KEY=true
fi
if [ -n "$LLM_PROVIDER_DEEPSEEK_API_KEY" ] && [ "$LLM_PROVIDER_DEEPSEEK_API_KEY" != "your-deepseek-api-key-here" ]; then
    HAS_PROVIDER_KEY=true
fi
if [ -n "$LLM_PROVIDER_MOONSHOT_API_KEY" ] && [ "$LLM_PROVIDER_MOONSHOT_API_KEY" != "your-moonshot-api-key-here" ]; then
    HAS_PROVIDER_KEY=true
fi
HAS_LEGACY_KEY=false
if [ -n "$LLM_API_KEY" ] && [ "$LLM_API_KEY" != "your-api-key-here" ]; then
    HAS_LEGACY_KEY=true
fi

if [ "$HAS_PROVIDER_KEY" = false ] && [ "$HAS_LEGACY_KEY" = false ]; then
    echo "============================================================"
    echo "  ERROR: No LLM API Key configured!"
    echo ""
    echo "  Option 1 (Recommended): Set provider-specific keys:"
    echo "    LLM_PROVIDER_DASHSCOPE_API_KEY=your-key"
    echo "    LLM_PROVIDER_DEEPSEEK_API_KEY=your-key"
    echo ""
    echo "  Option 2 (Legacy): Set a single key:"
    echo "    LLM_API_KEY=your-key"
    echo ""
    echo "  See .env.example for configuration details."
    echo "============================================================"
    exit 1
fi

# 2. Print configuration summary
echo "============================================================"
echo "  ZhikunCode — Starting"
echo "============================================================"
if [ -n "$LLM_PROVIDER_DASHSCOPE_API_KEY" ] && [ "$LLM_PROVIDER_DASHSCOPE_API_KEY" != "your-dashscope-api-key-here" ]; then
    MASKED="${LLM_PROVIDER_DASHSCOPE_API_KEY:0:6}****${LLM_PROVIDER_DASHSCOPE_API_KEY: -4}"
    echo "  DashScope Key:  $MASKED"
fi
if [ -n "$LLM_PROVIDER_DEEPSEEK_API_KEY" ] && [ "$LLM_PROVIDER_DEEPSEEK_API_KEY" != "your-deepseek-api-key-here" ]; then
    MASKED="${LLM_PROVIDER_DEEPSEEK_API_KEY:0:6}****${LLM_PROVIDER_DEEPSEEK_API_KEY: -4}"
    echo "  DeepSeek Key:   $MASKED"
fi
if [ -n "$LLM_PROVIDER_MOONSHOT_API_KEY" ] && [ "$LLM_PROVIDER_MOONSHOT_API_KEY" != "your-moonshot-api-key-here" ]; then
    MASKED="${LLM_PROVIDER_MOONSHOT_API_KEY:0:6}****${LLM_PROVIDER_MOONSHOT_API_KEY: -4}"
    echo "  Moonshot Key:   $MASKED"
fi
if [ "$HAS_LEGACY_KEY" = true ]; then
    MASKED="${LLM_API_KEY:0:6}****${LLM_API_KEY: -4}"
    echo "  Legacy API Key: $MASKED"
    echo "  LLM Base URL:   ${LLM_BASE_URL:-default (DashScope)}"
fi
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
