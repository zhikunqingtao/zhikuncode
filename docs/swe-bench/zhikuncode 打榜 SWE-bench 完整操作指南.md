# zhikuncode 打榜 SWE-bench 完整操作指南

> **版本**：v2.0（基于 300 实例全量实战验证）
> **最后验证日期**：2026-05-20
> **实测成绩**：SWE-bench Lite 300 实例，resolved=139，**Resolve Rate=46.3%**
> **模型**：qwen3.6-max-preview（唯一使用 Qwen 国产模型进入 Leaderboard 的 Agent）

---

## 目录

1. [前置条件](#1-前置条件)
2. [本地推理环境搭建](#2-本地推理环境搭建)
3. [数据集准备](#3-数据集准备)
4. [推理参数配置](#4-推理参数配置)
5. [断点续跑机制](#5-断点续跑机制)
6. [predictions 格式验证](#6-predictions-格式验证)
7. [ECS 上传和官方评测](#7-ecs-上传和官方评测)
8. [提交目录组装](#8-提交目录组装)
9. [metadata.yaml 正确格式](#9-metadatayaml-正确格式)
10. [合规性验证](#10-合规性验证)
11. [GitHub PR 提交](#11-github-pr-提交)
12. [常见问题和解决方案](#12-常见问题和解决方案)
13. [提交后跟进](#13-提交后跟进)

---

## 1. 前置条件

### 1.1 环境要求

| 组件 | 版本 | 用途 |
|---|---|---|
| macOS 13+ / Ubuntu 22.04+ | - | 本地推理机 |
| JDK 21 | openjdk 21.x | zhikuncode 后端 |
| Node.js 22 | v22.x（nvm 管理） | zhikuncode 前端 |
| Python 3.11+ | 3.11.x | swe_bench.py 推理脚本 |
| Git | 2.40+ | 仓库克隆 |
| VPN | - | GitHub + DashScope 稳定访问 |

### 1.2 依赖安装（macOS）

```bash
# Homebrew（如已安装可跳过）
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# JDK 21（brew 安装后必须手动加 PATH）
brew install openjdk@21
echo 'export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"' >> ~/.zshrc
echo 'export JAVA_HOME="/opt/homebrew/opt/openjdk@21"' >> ~/.zshrc
source ~/.zshrc
java --version  # 预期：openjdk 21.0.x

# Node.js 22
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.7/install.sh | bash
source ~/.zshrc
nvm install 22 && nvm use 22

# Python 3.11
brew install python@3.11
```

### 1.3 账号准备

| 账号 | 用途 | 获取方式 |
|---|---|---|
| 阿里云 DashScope API Key | 模型推理 | [dashscope.console.aliyun.com](https://dashscope.console.aliyun.com) → API-KEY 管理 |
| GitHub 账号 | fork experiments 仓库提交 PR | github.com |
| GitHub CLI (gh) | PR 自动化提交 | `brew install gh && gh auth login` |
| 阿里云 ECS | 官方 harness 评测 | ecs.console.aliyun.com（64vCPU+256GB RAM） |

> ⚠️ **DashScope 余额提示**：300 实例全量评测约消耗 ¥250-400（基于 qwen3.6-max-preview 定价，token 消耗约 70M input / 9M output）。请在启动评测前确保账户余额充足，余额不足会导致推理中途中断，产生大量空 patch。

### 1.4 VPN 要求（⚠️ 需要 VPN 的步骤）

以下步骤**强制要求 VPN 在线**：
- 本地推理全程（git clone GitHub 仓库 + DashScope API 调用）
- `gh` CLI 操作（fork、clone、push、创建 PR）
- HuggingFace 数据集下载（如本地缺失）

**VPN 诊断命令**：
```bash
curl -sI -m 5 https://github.com | head -1          # 预期：HTTP/2 200
time git ls-remote --heads https://github.com/django/django.git main  # 预期 < 3s
```

---

## 2. 本地推理环境搭建

### 2.1 项目克隆和初始化

```bash
git clone https://github.com/zhikunqingtao/zhikuncode.git
cd zhikuncode

# 配置 .env
cp .env.example .env
# 编辑 .env，填入：
#   LLM_PROVIDER_DASHSCOPE_API_KEY=sk-你的真实key
#   LLM_PROVIDER_DASHSCOPE_MODELS=qwen3.6-max-preview

# 前端依赖安装（首次）
cd frontend && npm install && cd ..

# Python 服务初始化（首次）
cd python-service && python3.11 -m venv .venv && .venv/bin/pip install -r requirements.txt && cd ..

# 后端编译（首次）
cd backend && ./mvnw clean package -DskipTests && cd ..
```

### 2.2 三端服务启动

> **前置条件**：确保已完成第 2.1 章的 python-service 虚拟环境初始化（`python-service/.venv` 目录存在）。`start.sh` 会自动激活该 venv 并启动 Python 服务，若 venv 未初始化则 Python 服务会启动失败。

```bash
./start.sh
```

启动后验证三端就绪：
```bash
curl -s http://127.0.0.1:8080/api/health/ready   # 预期含 "READY"
curl -s http://127.0.0.1:8000/health              # 预期：{"status":"ok"}
curl -sI http://127.0.0.1:5173 | head -1          # 预期：HTTP/1.1 200 OK
```

> **端口**：Java 后端 8080 + Python 服务 8000 + 前端 5173

### 2.3 验证模型可用

```bash
curl -s -X POST http://127.0.0.1:8080/api/query \
    -H "Content-Type: application/json" \
    -d '{"prompt":"1+1=?","model":"qwen3.6-max-preview","allowedTools":[],"permissionMode":"SKIP_ALL_PROMPTS","maxTurns":1,"timeoutSeconds":30}' \
    | jq '.result'
# 预期：返回模型回答
```

---

## 3. 数据集准备

### 3.1 格式要求

**⚠️ 关键约束**：`swe_bench.py --dataset` 参数**只接受 JSONL 格式**（每行一条 JSON），不支持 JSON 数组。

仓库已预置完整数据集：
```bash
ls -lh docs/swe-bench/scripts/swe-bench-lite-full.jsonl
# 预期：300 行，每行一条 instance
```

如需从 JSON 数组转换为 JSONL（`swe-bench-lite-full.jsonl` 已为 JSONL，无需转换）：
```bash
python3 -c "
import json
with open('your-array.json') as f:
    arr = json.load(f)
with open('docs/swe-bench/scripts/swe-bench-lite-full.jsonl', 'w') as f:
    for r in arr:
        f.write(json.dumps(r, ensure_ascii=False) + '\n')
print(f'写入 {len(arr)} 行')
"
```

### 3.2 分层采样策略

300 个实例按仓库分层分布（确保评测覆盖率）：

| 仓库 | 实例数 | 占比 |
|---|---|---|
| django/django | ~114 | 38% |
| sympy/sympy | ~78 | 26% |
| pytest-dev/pytest | ~18 | 6% |
| matplotlib/matplotlib | ~17 | 5.7% |
| scikit-learn/scikit-learn | ~16 | 5.3% |
| sphinx-doc/sphinx | ~15 | 5% |
| 其他 | ~42 | 14% |

### 3.3 工具白名单

推理时 Agent 可用的工具集（硬编码在 swe_bench.py）：
```python
ALLOWED_TOOLS = ["Read", "Edit", "Write", "Bash", "Grep", "Glob"]
```
权限模式：`BYPASS_PERMISSIONS`（自动跳过所有确认提示）

---

## 4. 推理参数配置

### 4.1 完整推理命令

```bash
cd /path/to/zhikuncode

# 为 SWE-bench 推理脚本创建独立虚拟环境（与 python-service 的 venv 不同）
python3.11 -m venv docs/swe-bench/scripts/.venv-swebench
source docs/swe-bench/scripts/.venv-swebench/bin/activate
pip install requests  # 唯一外部依赖
```

> 注意：`python-service/.venv` 是后端服务专用，`docs/swe-bench/scripts/.venv-swebench` 是推理脚本专用，两者不能混用。

```bash
python3 docs/swe-bench/scripts/swe_bench.py \
    --dataset ./docs/swe-bench/scripts/swe-bench-lite-full.jsonl \
    --model qwen3.6-max-preview \
    --output ./swe-bench/results-300 \
    --workers 2 \
    --timeout 1200 \
    --max-turns 80 \
    --multiphase \
    --resume \
    2>&1 | tee swe-bench/results-300/run.log
```

### 4.2 参数说明

| 参数 | 值 | 含义/原因 |
|---|---|---|
| `--dataset` | `.jsonl` 路径 | **必须 JSONL**，JSON 数组会报错 |
| `--model` | `qwen3.6-max-preview` | DashScope 主力模型 |
| `--output` | 输出目录 | 自动生成 `all_preds.jsonl` + `trajs/` |
| `--workers` | `2` | 本地并发 2 个实例（受后端线程池限制） |
| `--timeout` | `1200` | 单次 API 调用超时 1200 秒，防止复杂仓库超时 |
| `--max-turns` | `80` | Agent 最大工具调用轮次 |
| `--multiphase` | flag | 单次连续会话模式（优于分阶段切割） |
| `--resume` | flag | **断点续跑**，跳过已完成实例 |

### 4.3 推理时间预估（300 实例）

| 指标 | 数值 |
|---|---|
| 总耗时 | 8-12 小时（workers=2） |
| 平均每实例 | ~4 分钟（并行吞吐） |
| LLM token 消耗 | ~70M input / ~9M output |
| LLM 成本 | ¥250-400 |

---

## 5. 断点续跑机制

### 5.1 `--resume` 参数行为

- 启动时扫描 `output/all_preds.jsonl` 中已完成的 `instance_id`
- 自动跳过已有结果的实例，只跑剩余的
- 适用场景：VPN 中断后重连、进程被 kill 后重启

### 5.2 使用方式

```bash
# 首次运行（或不加 --resume 则从头开始）
python3 docs/swe-bench/scripts/swe_bench.py --dataset ... --output ./swe-bench/results-300 --resume

# 中断后直接重新执行同一命令即可，已完成的实例自动跳过
python3 docs/swe-bench/scripts/swe_bench.py --dataset ... --output ./swe-bench/results-300 --resume
```

### 5.3 harness 端的断点续跑

ECS 官方评测 harness 同样支持断点续跑：会自动读取已有的 predictions 文件中已评测过的实例，跳过重复评测。

---

## 6. predictions 格式验证

### 6.1 文件格式要求

`all_preds.jsonl` 每行一条 JSON，包含三个必须字段：
```json
{"instance_id":"django__django-11099","model_name_or_path":"zhikuncode","model_patch":"diff --git a/..."}
```

### 6.2 验证命令

```bash
PREDS=swe-bench/results-300/all_preds.jsonl

# 1. 总行数 = 300
wc -l "$PREDS"

# 2. 字段完整性
python3 -c "
import json
with open('$PREDS') as f:
    lines = [json.loads(l) for l in f if l.strip()]
print(f'总数: {len(lines)}')
missing = [l['instance_id'] for l in lines if not all(k in l for k in ('instance_id','model_name_or_path','model_patch'))]
print(f'字段缺失: {len(missing)}')
empty = [l['instance_id'] for l in lines if not l.get('model_patch','').strip()]
print(f'空 patch: {len(empty)}')
print(f'有效 patch: {len(lines) - len(empty)}')
"

# 3. instance_id 唯一性
python3 -c "
import json
with open('$PREDS') as f:
    ids = [json.loads(l)['instance_id'] for l in f if l.strip()]
print(f'唯一 ID: {len(set(ids))}, 总行数: {len(ids)}, 重复: {len(ids)-len(set(ids))}')
"

# 4. model_name_or_path 一致性
python3 -c "
import json
with open('$PREDS') as f:
    names = set(json.loads(l)['model_name_or_path'] for l in f if l.strip())
print(f'model_name_or_path 值集合: {names}')
"
# 预期：{'zhikuncode'}
```

---

## 7. ECS 上传和官方评测

> 本章假设 ECS 已部署好 Docker + swebench 官方环境。ECS 部署细节请参考《阿里云ECS环境SWE-bench评测手册.md》。

### 7.1 ECS 规格

- **推荐配置**：64vCPU + 256GB RAM
- **必须 x86_64 架构**（ARM 不支持官方 Docker 镜像）
- **Docker + swebench 官方环境已安装**

### 7.2 上传 predictions

```bash
ECS_IP="your_ecs_ip"
ECS_PASS="your_password"

# 上传 predictions 文件
expect -c "set timeout 60; spawn scp -o StrictHostKeyChecking=no \
    swe-bench/results-300/all_preds.jsonl \
    root@$ECS_IP:/data/swe-bench/all_preds.jsonl; \
    expect \"password:\" {send \"$ECS_PASS\r\"}; expect eof"
```

### 7.3 执行评测

```bash
# 在 ECS 上执行
source /data/swe-bench/venv/bin/activate
export HF_ENDPOINT=https://hf-mirror.com
export HF_HOME=/data/swe-bench/hf_cache

nohup python -m swebench.harness.run_evaluation \
    --dataset_name /data/swe-bench/swe-bench-lite-array.json \
    --predictions_path /data/swe-bench/all_preds.jsonl \
    --num_workers 32 \
    --timeout 900 \
    --run_id zhikuncode_eval \
    --namespace none \
    --report_dir /data/swe-bench/results \
    > /root/eval.log 2>&1 &
disown
```

**关键参数**：
- `--num_workers 32`：ECS 64vCPU 可高并发
- `--namespace none`：**必须！** 默认值会尝试从 Docker Hub 拉取镜像（403 失败）
- `--dataset_name`：使用本地 .json 文件路径，避免在线下载

### 7.4 评测结果位置

```
/data/swe-bench/logs/run_evaluation/<run_name>/<model_name>/
├── <instance_id>/
│   ├── report.json      ← 核心结果（resolved: true/false）
│   ├── patch.diff
│   ├── run_instance.log
│   └── test_output.txt
```

评测完成后自动生成 JSON 格式的评测汇总。

### 7.5 实测成绩

- **300 实例**：resolved=139，resolve rate=**46.3%**
- no_patch（空 patch 实例）：20 个

---

## 8. 提交目录组装

### 8.1 完整目录结构

```
submission/20260520_zhikuncode/
├── all_preds.jsonl          # 300 条，model_name_or_path="zhikuncode"
├── metadata.yaml            # info/assets/tags 三段式（见第9章）
├── README.md                # 含按仓库统计表 + 139 个 resolved 实例列表
├── results/
│   ├── results.json         # 核心结果（resolved 列表 + 统计）
│   ├── resolved_by_repo.json
│   └── resolved_by_time.json
├── trajs/                   # 300 个 .md 文件（gitignored by experiments repo）
└── logs/                    # 280 个 instance 目录（gitignored by experiments repo）
```

### 8.2 各文件生成方式

**all_preds.jsonl**：直接使用推理产出，确保 `model_name_or_path` 统一为 `"zhikuncode"`。

**results/ 三件套**：从 report.json 汇总生成：
```bash
# gen_results.py 位于 docs/swe-bench/scripts/ 目录
# 从 ECS 评测产出的 per-instance report.json 重建三件套
cd docs/swe-bench/scripts
python3 gen_results.py \
    --logs-dir ../20260520/logs/zhikuncode_eval_300_FINAL/zhikuncode \
    --output-dir ../20260520/results
cd ../../..
```

> **说明**：`gen_results.py` 遍历 logs 目录下所有 `report.json`，
> 统计 resolved 列表并生成 `results.json`、`resolved_by_repo.json`、`resolved_by_time.json` 三个文件。
> 脚本位置：`docs/swe-bench/scripts/gen_results.py`

**README.md**：包含：
- 模型信息和评测概述
- 按仓库统计表（repo / total / resolved / rate）
- 139 个 resolved 实例的完整列表
- 合规 Checklist

**trajs/**：本地推理产出的 trajectory 文件（每个 instance 一个 .md）。

**logs/**：ECS 评测产出的日志目录。

> **注意**：`trajs/` 和 `logs/` 在 experiments 官方仓库中被 `.gitignore` 排除，这是**正常行为**。提交 PR 时不需要包含它们，SWE-bench 团队合并后会通过 S3 上传。

---

## 9. metadata.yaml 正确格式

### 9.1 完整模板（已验证可通过官方 CI）

```yaml
info:
  name: ZhikunCode
  site: https://github.com/zhikunqingtao/zhikuncode
  report: https://zhikunqingtao.github.io/zhikuncode/swe-bench-report.html
  authors:
    - Qingtao Guo
assets:
  logs: s3://swe-bench-submissions/lite/20260520_zhikuncode/logs
  trajs: s3://swe-bench-submissions/lite/20260520_zhikuncode/trajs
tags:
  checked: false
  model:
    - qwen-3.6-max-preview
  org:
    - ZhikunCode
  os_model: true
  os_system: true
  system:
    attempts: 1
```

### 9.2 字段说明

| 段 | 字段 | 说明 |
|---|---|---|
| `info` | `name` | 系统名称（显示在 leaderboard） |
| `info` | `site` | 项目主页 URL |
| `info` | `report` | 评测报告页面 URL |
| `info` | `authors` | 作者列表 |
| `assets` | `logs` | S3 路径（官方约定 placeholder） |
| `assets` | `trajs` | S3 路径（官方约定 placeholder） |
| `tags` | `checked` | 官方是否已验证（提交时填 false） |
| `tags` | `model` | 使用的 LLM 模型列表 |
| `tags` | `org` | 组织名 |
| `tags` | `os_model` | 模型是否开源 |
| `tags` | `os_system` | 系统是否开源 |
| `tags.system` | `attempts` | 每个实例尝试次数 |

> **⚠️ 关键说明**：`assets` 中的 S3 路径是官方约定的 placeholder，提交者**无需 S3 写权限**。SWE-bench 团队在合并 PR 后会自行上传 trajs/logs 到 S3。

---

## 10. 合规性验证

### 10.1 八项必须全部通过

```bash
SUBMISSION_DIR="submission/20260520_zhikuncode"

# 1. all_preds.jsonl 行数 = 300，字段完整
echo "=== Check 1: all_preds.jsonl 行数 ==="
wc -l "$SUBMISSION_DIR/all_preds.jsonl"
python3 -c "
import json
with open('$SUBMISSION_DIR/all_preds.jsonl') as f:
    lines = [json.loads(l) for l in f if l.strip()]
assert len(lines) == 300, f'行数不对: {len(lines)}'
for l in lines:
    assert all(k in l for k in ('instance_id','model_name_or_path','model_patch'))
print('✅ Check 1 PASSED: 300 行，字段完整')
"

# 2. trajs/ 数量 = 300
echo "=== Check 2: trajs 数量 ==="
ls "$SUBMISSION_DIR/trajs/" | wc -l
# 预期：300

# 3. logs/ 至少有 251 个 report.json
echo "=== Check 3: logs report.json 数量 ==="
find "$SUBMISSION_DIR/logs/" -name "report.json" | wc -l
# 预期：>= 251（空 patch 实例不生成 logs）

# 4. metadata.yaml 格式正确（info/assets/tags 三段式）
echo "=== Check 4: metadata.yaml 格式 ==="
python3 -c "
import yaml
with open('$SUBMISSION_DIR/metadata.yaml') as f:
    meta = yaml.safe_load(f)
assert 'info' in meta and 'assets' in meta and 'tags' in meta
assert 'name' in meta['info'] and 'authors' in meta['info']
assert 'logs' in meta['assets'] and 'trajs' in meta['assets']
assert 'model' in meta['tags'] and 'checked' in meta['tags']
print('✅ Check 4 PASSED: metadata.yaml 三段式完整')
"

# 5. README.md 存在且含结果统计
echo "=== Check 5: README.md ==="
grep -c "resolved" "$SUBMISSION_DIR/README.md"
# 预期：> 0

# 6. model_name_or_path 一致（全部为 "zhikuncode"）
echo "=== Check 6: model_name_or_path 一致性 ==="
python3 -c "
import json
with open('$SUBMISSION_DIR/all_preds.jsonl') as f:
    names = set(json.loads(l)['model_name_or_path'] for l in f if l.strip())
assert names == {'zhikuncode'}, f'不一致: {names}'
print('✅ Check 6 PASSED: 全部为 zhikuncode')
"

# 7. resolved 数量从 report.json 验证 = 139
echo "=== Check 7: resolved 数量 ==="
python3 -c "
import json, os
logs_dir = '$SUBMISSION_DIR/logs'
resolved = 0
for d in os.listdir(logs_dir):
    rp = os.path.join(logs_dir, d, 'report.json')
    if os.path.exists(rp):
        data = json.load(open(rp))
        for v in data.values():
            if v.get('resolved'): resolved += 1
print(f'Resolved: {resolved}')
# 请根据你的实际评测成绩修改此处的期望值
EXPECTED_RESOLVED = 139  # ZhikunCode 实测值，其他系统请修改此行
if resolved == EXPECTED_RESOLVED:
    print(f'✅ Check 7 PASSED: resolved={resolved}')
else:
    print(f'⚠️  Check 7 WARNING: resolved={resolved}，预期为 {EXPECTED_RESOLVED}，请确认是否正确')
"

# 8. instance_id 唯一，无重复
echo "=== Check 8: instance_id 唯一性 ==="
python3 -c "
import json
with open('$SUBMISSION_DIR/all_preds.jsonl') as f:
    ids = [json.loads(l)['instance_id'] for l in f if l.strip()]
assert len(ids) == len(set(ids)), f'有重复: {len(ids)} vs {len(set(ids))}'
print('✅ Check 8 PASSED: 300 个 ID 全部唯一')
"
```

---

## 11. GitHub PR 提交

> ⚠️ 以下全部步骤**需要 VPN**。

### 11.1 Fork experiments 仓库

```bash
# fork 仓库（--clone 和 --remote 不能同时用，必须分两步）
gh repo fork SWE-bench/experiments --clone=false || true

# 等待 fork 就绪（GitHub 需要几秒初始化）
for i in 1 2 3 4 5; do
    if gh repo view zhikunqingtao/experiments >/dev/null 2>&1; then
        echo "fork ready"
        break
    fi
    sleep 5
done

# clone 到本地
gh repo clone zhikunqingtao/experiments experiments
```

### 11.2 同步上游并创建分支

```bash
cd experiments
git remote add upstream https://github.com/SWE-bench/experiments.git || true
git fetch upstream
git checkout main && git reset --hard upstream/main

# 创建提交分支
git checkout -B add-zhikuncode-lite-20260520
```

### 11.3 复制文件到目标目录

```bash
TARGET="evaluation/lite/20260520_zhikuncode"
mkdir -p "$TARGET/results"

# 注意：此命令的工作目录必须是 swe-bench/experiments/
# 上级目录 ../  即为 swe-bench/，其中包含 submission/ 目录
# 如果 experiments 克隆在其他位置，请相应调整路径
rsync -a \
    --exclude 'verify_submission.py' \
    --exclude 'VERIFICATION_REPORT.md' \
    --exclude '*_FINAL.json' \
    --exclude 'trajs' \
    --exclude 'logs' \
    ../submission/20260520_zhikuncode/ "$TARGET/"
```

### 11.4 生成 results/ 三件套

```bash
# gen_results.py 位于 docs/swe-bench/scripts/ 目录
# 从 ECS 评测产出的 per-instance report.json 重建三件套
cd docs/swe-bench/scripts
python3 gen_results.py \
    --logs-dir ../20260520/logs/zhikuncode_eval_300_FINAL/zhikuncode \
    --output-dir ../20260520/results
cd ../../..
```

### 11.5 提交推送

```bash
git add evaluation/lite/20260520_zhikuncode/
git commit -m "Add ZhikunCode results for SWE-bench Lite"
git push origin add-zhikuncode-lite-20260520
```

### 11.6 创建 PR

```bash
# PR_BODY.md 位于 docs/swe-bench/scripts/ 目录
# 当前工作目录在项目根目录下的 experiments/ 内（假设从项目根执行 gh repo clone）
gh pr create --repo SWE-bench/experiments \
    --title "Add ZhikunCode results for SWE-bench Lite" \
    --body-file ../docs/swe-bench/scripts/PR_BODY.md \
    --base main \
    --head "zhikunqingtao:add-zhikuncode-lite-20260520"
```

**PR_BODY.md 模板**：
```markdown
## ZhikunCode - SWE-bench Lite Results

- **System**: ZhikunCode (open-source AI Coding Agent)
- **Model**: qwen-3.6-max-preview (Alibaba Cloud DashScope)
- **Resolve Rate**: 46.3% (139/300)
- **Attempts per instance**: 1
- **Date**: 2026-05-20

### Highlights
- Only agent using Qwen (Chinese-developed) model on SWE-bench Lite leaderboard
- Open-source system: https://github.com/zhikunqingtao/zhikuncode
- MIT License

### Checklist
- [x] 300 predictions in all_preds.jsonl
- [x] metadata.yaml with info/assets/tags
- [x] results/ directory with results.json
- [x] README.md with statistics
```

---

## 12. 常见问题和解决方案

### 12.1 SSH 连接被重置

**症状**：`Connection reset by peer`

**解决**：加 `sleep 5` 后重试，或使用 SSH keep-alive：
```bash
ssh -o ServerAliveInterval=60 -o ServerAliveCountMax=3 root@$ECS_IP
```

### 12.2 expect 引号嵌套问题

**症状**：Python 命令在 expect 中引号转义混乱

**解决**：改用直接文件读取方式，不要在 expect 中嵌套 Python 命令：
```bash
# 先把命令写入脚本
echo 'python3 -c "import json; print(len(json.load(open('/data/file.json'))))"' > /tmp/cmd.sh
expect -c "spawn ssh root@$IP 'bash /tmp/cmd.sh'; expect password: {send $PASS\r}; expect eof"
```

### 12.3 GitHub 网络超时

**症状**：`gh` CLI 操作卡死或报 timeout

**解决**：确保 VPN 在线，或使用 token 嵌入 URL 直连：
```bash
git remote set-url origin https://<GITHUB_TOKEN>@github.com/zhikunqingtao/experiments.git
```

### 12.4 gh fork 参数冲突

**症状**：`--clone=true --remote=true` 在新版 gh CLI 报错

**解决**：分两步执行，先 fork 再手动 clone：
```bash
gh repo fork SWE-bench/experiments --clone=false || true
gh repo clone zhikunqingtao/experiments experiments
```

### 12.5 trajs/logs 被 .gitignore 排除

**症状**：`git add` 后 trajs/ 和 logs/ 没有被追踪

**解释**：这是**正常行为**。experiments 官方仓库已在 `.gitignore` 中排除这两个目录。提交 PR 时不需要包含它们，SWE-bench 团队合并后通过 S3 上传。

### 12.6 logs 目录缺失部分实例

**症状**：logs/ 中只有 280 个目录，而非 300 个

**解释**：空 patch（no_patch）的实例不会生成 logs 目录，这是已知行为，不影响合规。实测 20 个 no_patch 实例没有 logs。

### 12.7 VPN 断网导致空 patch

**症状**：`all_preds.jsonl` 中某些 `model_patch` 为空字符串

**解决**：
```bash
# 1. 找出空 patch 的 instance_id
python3 -c "
import json
with open('swe-bench/results-300/all_preds.jsonl') as f:  # 推理输出路径，无需更改
    empty = [json.loads(l)['instance_id'] for l in f if not json.loads(l).get('model_patch','').strip()]
print(f'空 patch: {len(empty)}')
for e in empty: print(e)
"
# 2. 修复 VPN 后使用 --resume 重跑
```

### 12.8 --dataset 传入 JSON 数组报错

**症状**：`json.decoder.JSONDecodeError: Extra data`

**解决**：必须转为 JSONL 格式，参见第 3 章。

### 12.9 ECS 评测 --namespace 漏填

**症状**：评测进程运行但 `docker images | grep sweb` 始终为空

**解决**：必须显式传 `--namespace none`，否则默认尝试从 Docker Hub 拉取（403 Forbidden）。

### 12.10 model_name_or_path 不一致

**症状**：合规性验证第 6 项失败

**解决**：统一修改：
```bash
python3 -c "
import json
lines = []
with open('all_preds.jsonl') as f:
    for l in f:
        d = json.loads(l)
        d['model_name_or_path'] = 'zhikuncode'
        lines.append(json.dumps(d, ensure_ascii=False))
with open('all_preds.jsonl', 'w') as f:
    f.write('\n'.join(lines) + '\n')
"
```

### 12.11 swe_bench.py API 调用三次超时后放弃

**症状**：日志显示 `API call timed out (attempt 3/3)`

**解决**：
- 增大 `--timeout` 到 1200 或 1500
- 降低 `--workers` 到 1，排除并发竞争
- 确认 DashScope 未返回 429 限流

### 12.12 ECS 评测 git clone 超时

**症状**：大量 instance 报 `Clone/checkout failed`

**解决**：在 ECS 上配置 git 增强：
```bash
git config --global http.postBuffer 524288000
git config --global http.lowSpeedLimit 1000
git config --global http.lowSpeedTime 300
```

---

## 13. 提交后跟进

### 13.1 PR 合并时间线

| 阶段 | 预期时间 | 说明 |
|---|---|---|
| PR 创建后 | 1-3 天 | SWE-bench 团队会自动运行 CI 校验 |
| CI 校验 | 几小时 | 验证 predictions 格式、metadata 完整性 |
| 人工 Review | 3-7 天 | 团队成员审查后合并或反馈 |
| Leaderboard 更新 | 合并后 1-2 天 | 网站自动从仓库同步数据 |

### 13.2 如何联系 SWE-bench 团队

- **PR 评论**：最直接的方式，在 PR 下留言回复
- **GitHub Issues**：[github.com/SWE-bench/experiments/issues](https://github.com/SWE-bench/experiments/issues)
- **官方邮箱**：查看 SWE-bench 仓库 README 中的联系方式

### 13.3 常见 Review 反馈及应对

| 反馈 | 应对 |
|---|---|
| "metadata.yaml format incorrect" | 对照第 9 章模板修正 |
| "Missing results.json" | 用 gen_results.py 重新生成 |
| "model_name_or_path inconsistent" | 统一为 "zhikuncode" |
| "Need more information about system" | 补充 README.md 中的系统描述 |

### 13.4 合并后验证

PR 合并后，在 [swebench.com](https://www.swebench.com/) 搜索 "ZhikunCode" 确认已上榜。

---

**文档结束** | 最后更新：2026-05-20 | 版本：v2.0
