#!/usr/bin/env bash
# SWE-bench Lite 官方排行榜提交脚本
# 前置条件：已完成 `gh auth login` 认证（GitHub 用户：zhikunqingtao）
set -euo pipefail

# 路径计算：以本脚本所在位置为基准，避免硬编码用户路径
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

GH_USER="zhikunqingtao"
SUBMISSION_DIR="$PROJECT_ROOT/swe-bench/submission/20260520_zhikuncode"
WORK_DIR="$PROJECT_ROOT/swe-bench/leaderboard_workspace"
BRANCH="zhikuncode-swe-bench-lite"
TARGET_SUBDIR="evaluation/lite/20260520_zhikuncode"

echo "==> [1/7] 校验 gh 认证"
gh auth status

echo "==> [2/7] 准备工作目录: $WORK_DIR"
mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

if [ ! -d "experiments/.git" ]; then
  echo "==> [3/7] Fork 并克隆 swe-bench/experiments"
  gh repo fork SWE-bench/experiments --clone=false || true
  for i in 1 2 3 4 5 6; do
    if gh repo view "${GH_USER}/experiments" >/dev/null 2>&1; then echo "    fork ready"; break; fi
    echo "    waiting fork sync... ($i/6)"; sleep 5
  done
  gh repo clone "${GH_USER}/experiments" experiments
else
  echo "==> [3/7] 已有本地仓库，跳过 fork/clone"
fi

cd experiments

echo "==> [4/7] 同步上游并创建分支 $BRANCH"
if ! git remote | grep -q '^upstream$'; then
  git remote add upstream https://github.com/SWE-bench/experiments.git
fi
git fetch upstream
git checkout main 2>/dev/null || git checkout -b main upstream/main
git reset --hard upstream/main
git checkout -B "$BRANCH"

echo "==> [5/7] 复制提交资产到 $TARGET_SUBDIR"
mkdir -p "$TARGET_SUBDIR"
rsync -a \
  --exclude 'verify_submission.py' \
  --exclude 'VERIFICATION_REPORT.md' \
  --exclude 'zhikuncode.zhikuncode_eval_300_FINAL.json' \
  "$SUBMISSION_DIR"/ "$TARGET_SUBDIR"/

echo "==> 已复制内容："
ls -la "$TARGET_SUBDIR"
echo "logs: $(ls "$TARGET_SUBDIR/logs" | wc -l)"
echo "trajs: $(ls "$TARGET_SUBDIR/trajs" | wc -l)"

echo "==> [6/7] Commit & Push"
git add "$TARGET_SUBDIR"
git commit -m "Add ZhikunCode results for SWE-bench Lite (46.3% resolve rate)"
git push -u origin "$BRANCH"

echo "==> [7/7] 创建 PR"
gh pr create --repo SWE-bench/experiments \
  --base main \
  --head "${GH_USER}:${BRANCH}" \
  --title "Add ZhikunCode results for SWE-bench Lite" \
  --body-file "$SCRIPT_DIR/PR_BODY.md"

echo "==> 完成。"
gh pr list --repo SWE-bench/experiments --author "$GH_USER"
