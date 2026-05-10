#!/usr/bin/env bash
# Task 3-5 测试执行日志归档
# 引用：docs/Task3-5差异化升级功能测试方案.md §11.5
# 用法：./scripts/archive-logs.sh [tag]

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TAG="${1:-run}"
STAMP=$(date +%Y%m%d-%H%M%S)
DEST="docs/test-results/logs/task3-5/${TAG}-${STAMP}"

mkdir -p "$DEST"

# 三端日志快照（只 cp 不 mv，避免破坏 logback 独占写句柄）
cp -f log/app.log              "$DEST/backend.log"          2>/dev/null || true
cp -f log/error.log            "$DEST/backend-error.log"    2>/dev/null || true
cp -f log/backend-console.log  "$DEST/backend-console.log"  2>/dev/null || true
cp -f log/python-console.log   "$DEST/python.log"           2>/dev/null || true
cp -f log/frontend-console.log "$DEST/frontend.log"         2>/dev/null || true

# 归档元信息
{
    echo "# Task 3-5 Test Run Logs"
    echo "**Tag**: ${TAG}"
    echo "**Timestamp**: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo ""
    echo "## Service PIDs (from .service-pids)"
    cat .service-pids 2>/dev/null || echo "(no .service-pids found)"
    echo ""
    echo "## Git Info"
    echo "- Branch: $(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo n/a)"
    echo "- Commit: $(git rev-parse HEAD 2>/dev/null || echo n/a)"
    echo ""
    echo "## Versions"
    echo "- Node: $(node -v 2>/dev/null || echo n/a)"
    echo "- Java: $(java -version 2>&1 | head -1)"
    echo "- Python: $(python3 --version 2>/dev/null || echo n/a)"
} > "$DEST/manifest.md"

echo "✅ Logs archived → $DEST"
ls -la "$DEST"
