# 阿里云 ECS 环境 SWE-bench 评测手册

> **版本**: v3.0（300 实例实战版）
> **作者**: ZhikunCode 
> **最后验证日期**: 2026-05-20
> **实测规模**: SWE-bench Lite 全集 **300 实例**
> **典型耗时**: 约 3-4 小时（64 vCPU / 32 并发）
> **适用场景**: 中国大陆网络环境下，使用阿里云 ECS 完成 SWE-bench Lite 官方评测 harness 全流程
> **数据目录基准**: `/data/swe-bench/`

---

## 🚨 VPN 依赖三层警告（务必先读）

> **警告 #1（准备阶段）**：⚠️ **本地推理 + ECS 评测都强依赖访问 GitHub / HuggingFace / DockerHub**。所有节点（macOS 本机生成 patch、ECS 执行 harness）都必须具备稳定的国际网络通道，**首选企业 VPN 或可信代理**，禁止使用免费/不稳定 VPN。
>
> **警告 #2（执行阶段）**：⚠️ ECS 上 `git clone`、`docker pull` 即使配置了镜像源，仍可能间歇性超时。务必在评测启动前先做"网络冒烟测试"（见 [§3.5](#35-网络冒烟测试)），并在 `tmux/screen` 中后台执行评测，避免 SSH 断线导致进程被杀。
>
> **警告 #3（遇到超时时）**：⚠️ 评测期间一旦发现 `error 实例数 > 5%`，**立即停下来**检查是否 VPN 中断或镜像源被限流。直接重跑只会浪费时间，必须先恢复网络（重连 VPN、切换镜像源、增加重试）后再续跑（见 [§11.3](#113-评测期间-git-clone-超时error-实例)）。

---

## 📋 目录

1. [ECS 规格要求](#1-ecs-规格要求)
2. [初始环境配置](#2-初始环境配置)
3. [网络问题处理](#3-网络问题处理)
4. [数据准备](#4-数据准备)
5. [评测命令](#5-评测命令)
6. [进度监控](#6-进度监控)
7. [评测结果分析](#7-评测结果分析)
8. [日志管理](#8-日志管理)
9. [磁盘管理](#9-磁盘管理)
10. [分层采样策略](#10-分层采样策略)
11. [常见问题（实战 12 条）](#11-常见问题实战-12-条)
12. [结果导出到本地](#12-结果导出到本地)

---

## 1. ECS 规格要求

### 1.1 推荐配置（300 实例实战版）

| 项目 | 最低 | **推荐（实战）** | 说明 |
|---|---|---|---|
| 实例规格 | 16 vCPU / 64 GB | **64 vCPU / 256 GB**（`ecs.r7.16xlarge`） | 32 并发评测时单 worker 内存峰值约 4-6 GB |
| CPU 架构 | x86_64 | x86_64（Intel Ice Lake / Sapphire Rapids） | **不可使用 ARM**：SWE-bench Docker 镜像仅 linux/amd64 |
| 操作系统 | Ubuntu 22.04 LTS | Ubuntu 22.04 LTS | 实测内核 5.15.x |
| 系统盘 | 60 GB ESSD | 60 GB ESSD PL1 | 仅装 conda + swebench 客户端 |
| 数据盘 | 200 GB ESSD | **300 GB ESSD PL1（NVMe）** | Docker 镜像 + 日志，挂载到 `/data` |
| 公网带宽 | 10 Mbps | 10-25 Mbps（按使用流量） | conda/pip 包下载需要 |
| 地域 | 任意 | 华东 1 / 华北 2 | 同地域内网镜像源加速 |

### 1.2 不同规模磁盘预算

| 规模 | env 镜像总占用 | instance 镜像 | 缓存+日志 | **/data 建议** |
|---|---|---|---|---|
| 50 实例 | ~40 GB | ~25 GB | ~10 GB | ≥ 100 GB |
| 100 实例 | ~70 GB | ~40 GB | ~15 GB | ≥ 150 GB |
| **300 实例** | **~140 GB** | **~80 GB** | **~30 GB** | **≥ 300 GB** |

> 💡 数据盘满会导致 Docker 写盘失败、镜像构建静默卡死 → 留 20% 缓冲。

### 1.3 安全组规则

仅开放 **22 (SSH)**（来源限制为本地公网 IP），其余 TCP 出方向默认放行。

---

## 2. 初始环境配置

### 2.1 SSH 登录与数据盘挂载

```bash
# 本地连接 ECS
ssh root@<ECS_IP>

# 检测数据盘（NVMe 为新代实例标准）
lsblk
# 预期看到 /dev/nvme1n1（数据盘，未挂载）

# 一键格式化并挂载
DISK=/dev/nvme1n1
[ -z "$(blkid -s TYPE -o value $DISK 2>/dev/null)" ] && mkfs.ext4 -F $DISK
mkdir -p /data && mount $DISK /data
UUID=$(blkid -s UUID -o value $DISK)
grep -q "$UUID" /etc/fstab || echo "UUID=$UUID /data ext4 defaults,noatime 0 2" >> /etc/fstab
df -h /data
```

> 📌 阿里云 c9i / r7 / g8i 等较新代实例使用 NVMe 协议，数据盘设备为 `/dev/nvme1n1`，**不是** `/dev/vdb`。

### 2.2 系统软件安装

```bash
# 替换 apt 为阿里云镜像源
cat > /etc/apt/sources.list << 'EOF'
deb https://mirrors.aliyun.com/ubuntu/ jammy main restricted universe multiverse
deb https://mirrors.aliyun.com/ubuntu/ jammy-updates main restricted universe multiverse
deb https://mirrors.aliyun.com/ubuntu/ jammy-backports main restricted universe multiverse
deb https://mirrors.aliyun.com/ubuntu/ jammy-security main restricted universe multiverse
EOF

apt-get update -q
apt-get install -y ca-certificates curl gnupg lsb-release jq python3-pip python3-venv tmux
```

### 2.3 安装 Docker（数据盘 + 镜像加速）

```bash
# 安装 docker-ce
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://mirrors.aliyun.com/docker-ce/linux/ubuntu/gpg | \
    gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
    https://mirrors.aliyun.com/docker-ce/linux/ubuntu $(lsb_release -cs) stable" \
    > /etc/apt/sources.list.d/docker.list
apt-get update -q && apt-get install -y docker-ce docker-ce-cli containerd.io

# 配置 data-root 到数据盘 + 国内镜像加速器
mkdir -p /etc/docker
cat > /etc/docker/daemon.json << 'EOF'
{
    "data-root": "/data/docker",
    "registry-mirrors": [
        "https://docker.1panel.live",
        "https://hub-mirror.c.163.com",
        "https://docker.m.daocloud.io",
        "https://mirror.ccs.tencentyun.com"
    ],
    "storage-driver": "overlay2",
    "log-driver": "json-file",
    "log-opts": {"max-size": "50m", "max-file": "3"}
}
EOF
systemctl enable docker && systemctl restart docker && sleep 3
```

### 2.4 安装 swebench（Python 3.11 虚拟环境）

```bash
mkdir -p /data/swe-bench && cd /data/swe-bench

# 必须使用 Python 3.11+：3.10 不支持 PEP 604（X|Y 语法）
apt-get install -y python3.11 python3.11-venv
python3.11 -m venv /data/swe-bench/venv
source /data/swe-bench/venv/bin/activate

# pip 镜像源
mkdir -p /root/.pip
cat > /root/.pip/pip.conf << 'EOF'
[global]
index-url = https://mirrors.aliyun.com/pypi/simple/
trusted-host = mirrors.aliyun.com
timeout = 120
EOF

pip install --upgrade pip
pip install swebench
pip show swebench | grep Version    # 预期 4.1.0+
```

---

## 3. 网络问题处理

### 3.1 GitHub 镜像加速（修复 git clone 超时）

> ⚠️ **警告（执行阶段）**：ECS 直连 GitHub 经常超时，尤其在评测大型仓库（matplotlib、django）时。

```bash
git config --global url."https://ghproxy.com/https://github.com/".insteadOf "https://github.com/"
# 备用方案
# git config --global url."https://hub.fastgit.xyz/".insteadOf "https://github.com/"

# 增强 git 超时与重试
git config --global http.postBuffer 524288000
git config --global http.lowSpeedLimit 1000
git config --global http.lowSpeedTime 60
```

### 3.2 HuggingFace 镜像（数据集与模型加载兜底）

```bash
export HF_ENDPOINT=https://hf-mirror.com
export HF_HOME=/data/swe-bench/hf_cache
# 持久化
echo 'export HF_ENDPOINT=https://hf-mirror.com' >> /root/.bashrc
echo 'export HF_HOME=/data/swe-bench/hf_cache' >> /root/.bashrc
```

### 3.3 Docker 容器内 pip 镜像源注入（关键 monkey-patch）

> ⚠️ swebench 默认 Dockerfile 在容器内执行 `setup_env.sh`，pip 直连 `files.pythonhosted.org`，国内必超时。必须 patch `docker_build.py`。

将以下内容保存为 `/root/inject-pip-mirror.sh`：

```bash
#!/bin/bash
set -e
source /data/swe-bench/venv/bin/activate
DOCKER_BUILD_PY=$(python3 -c "import swebench.harness.docker_build as d; print(d.__file__)")
[ -f "${DOCKER_BUILD_PY}.bak" ] || cp "$DOCKER_BUILD_PY" "${DOCKER_BUILD_PY}.bak"

python3 << PYEOF
fp = "$DOCKER_BUILD_PY"
content = open(fp).read()
old = '''        # Write the dockerfile to the build directory
        dockerfile_path = build_dir / "Dockerfile"
        with open(dockerfile_path, "w") as f:
            f.write(dockerfile)'''
inject = (
    'RUN pip config set global.index-url https://mirrors.aliyun.com/pypi/simple/ '
    '&& pip config set global.trusted-host mirrors.aliyun.com\\n'
    'RUN /opt/miniconda3/bin/conda config --set remote_connect_timeout_secs 60 '
    '&& /opt/miniconda3/bin/conda config --set remote_read_timeout_secs 120\\n'
)
new = '''        # [CHINA MIRROR]
        if 'setup_env.sh' in dockerfile:
            dockerfile = dockerfile.replace(
                'RUN /bin/bash -c "source ~/.bashrc && /root/setup_env.sh"',
                "''' + inject + '''" + 'RUN /bin/bash -c "source ~/.bashrc && /root/setup_env.sh"'
            )
        # Write the dockerfile to the build directory
        dockerfile_path = build_dir / "Dockerfile"
        with open(dockerfile_path, "w") as f:
            f.write(dockerfile)'''
if 'CHINA MIRROR' not in content and old in content:
    open(fp, 'w').write(content.replace(old, new))
    print("✅ 已注入 pip 镜像源")
elif 'CHINA MIRROR' in content:
    print("✅ 已注入过，跳过")
else:
    print("❌ 未找到注入点，请检查 swebench 版本"); exit(1)
PYEOF
```

执行：
```bash
chmod +x /root/inject-pip-mirror.sh && /root/inject-pip-mirror.sh
```

### 3.4 Docker 镜像源对 swebench 官方镜像的限制

> 📌 阿里云 / 腾讯云镜像源**不收录** swebench 官方镜像（`swebench/sweb.eval.x86_64.*`）。这意味着：
>
> - **无法直接拉取 swebench 预构建镜像**
> - 解决方案：使用 `--namespace none`（本地构建）或 `--namespace default`（按官方约定走 DockerHub 直连）
> - 实测对国内 ECS 而言，**`--namespace none` 本地构建最稳**

### 3.5 网络冒烟测试

```bash
# 评测启动前，三连测：
timeout 30 docker pull ubuntu:22.04                                  # Docker 镜像
timeout 30 git ls-remote https://github.com/django/django.git | head -1   # GitHub
timeout 30 curl -sI $HF_ENDPOINT | head -1                            # HuggingFace 镜像
```

任一失败 → 处理网络后再启动评测。

---

## 4. 数据准备

### 4.1 上传数据集与 predictions 到 ECS

需要 2 个核心文件传到 `/data/swe-bench/`：

| 文件 | 用途 |
|---|---|
| `swe-bench-lite-full.jsonl` | SWE-bench Lite 数据集（300 实例 JSONL）|
| `all_preds.jsonl` | 模型生成的 patch 预测（每行一个 JSON）|

```bash
# 本地（macOS）端执行
ECS_IP="<ECS_IP>"
PROJECT_ROOT="/path/to/zhikuncode"  # 替换为你的项目根目录绝对路径

# 数据集文件（JSONL 格式，300 实例）
scp $PROJECT_ROOT/docs/swe-bench/scripts/swe-bench-lite-full.jsonl \
    root@$ECS_IP:/data/swe-bench/swe-bench-lite-full.jsonl
# predictions（本地推理后生成的文件）
scp $PROJECT_ROOT/swe-bench/results-final-300/all_preds.jsonl \
    root@$ECS_IP:/data/swe-bench/all_preds.jsonl
```

> 📌 **数据集格式说明**：`swe-bench-lite-full.jsonl` 是 JSONL 格式（每行一条实例），swebench 4.x 支持直接使用。评测命令中 `--dataset_name` 参数使用该文件路径即可。

### 4.2 上传前空 patch 检查

```bash
# 本地端排查空 patch（VPN 中断会产生空 patch）
python3 << 'PYEOF'
import json
preds = [json.loads(l) for l in open("all_preds.jsonl") if l.strip()]
empty = sum(1 for p in preds if not p.get("model_patch", "").strip())
print(f"总数: {len(preds)} | 空 patch: {empty} ({empty/len(preds)*100:.1f}%) | 有效: {len(preds)-empty}")
if empty / len(preds) > 0.10:
    print("⚠️  空 patch 率 > 10%，建议先排查 VPN 后重跑")
PYEOF
```

阈值：≤ 5% 可上传；5-10% 记录后上传；> 10% 必须排查根因。

---

## 5. 评测命令

### 5.1 关键参数说明

| 参数 | 取值 | 重要性 | 说明 |
|---|---|---|---|
| `--dataset_name` | **本地 .json 路径** | ⭐⭐⭐⭐⭐ | 避免 HuggingFace 网络黑洞 |
| `--predictions_path` | `all_preds.jsonl` | ⭐⭐⭐⭐⭐ | 模型预测文件 |
| `--namespace` | **`none`** | ⭐⭐⭐⭐⭐ | 触发本地镜像构建（关键，少了必失败）|
| `--max_workers` / `--num_workers` | **`32`**（64vCPU 实例）| ⭐⭐⭐⭐ | swebench 4.x 用 `--max_workers`，3.x 用 `--num_workers`。本手册实测版本（4.1.0+）使用 `--max_workers` |
| `--timeout` | `1800` | ⭐⭐⭐ | 单 instance 测试超时（秒）|
| `--run_id` | 自定义 | ⭐⭐ | 结果目录标识 |
| `--report_dir` | 输出目录 | ⭐⭐ | report.json 汇总位置 |
| `--instance_ids` | 指定实例列表 | ⭐⭐ | 续跑/重跑场景使用 |

### 5.2 启动评测（300 实例）

```bash
# 务必在 tmux 中执行，防止 SSH 断线
tmux new -s eval

source /data/swe-bench/venv/bin/activate
export HF_ENDPOINT=https://hf-mirror.com
export HF_HOME=/data/swe-bench/hf_cache

cd /data/swe-bench
python -m swebench.harness.run_evaluation \
    --dataset_name /data/swe-bench/swe-bench-lite-full.jsonl \
    --predictions_path /data/swe-bench/all_preds.jsonl \
    --max_workers 32 \      # swebench 4.x 参数名，3.x 版本请改为 --num_workers
    --timeout 1800 \
    --run_id zhikuncode_eval_300_FINAL \
    --namespace none \
    --report_dir /data/swe-bench/results \
    2>&1 | tee /data/swe-bench/eval_300.log

# Ctrl+B D 脱离 tmux；tmux attach -t eval 重新接入
```

### 5.3 续跑：仅重跑指定 instance

```bash
# 提取 error 实例 ID
RUN_ID=zhikuncode_eval_300_FINAL
MODEL=$(ls /data/swe-bench/logs/run_evaluation/$RUN_ID | head -1)
python3 << PYEOF > /tmp/error_ids.txt
import json, os, glob
base = "/data/swe-bench/logs/run_evaluation/$RUN_ID/$MODEL"
ids = []
for d in glob.glob(f"{base}/*"):
    rep = os.path.join(d, "report.json")
    if not os.path.exists(rep):
        ids.append(os.path.basename(d))
        continue
    r = json.load(open(rep))
    v = list(r.values())[0]
    if v.get("resolved") is None or "error" in str(v).lower():
        ids.append(os.path.basename(d))
print("\n".join(ids))
PYEOF

# 用 --instance_ids 重跑（复用已构建的 env 镜像）
python -m swebench.harness.run_evaluation \
    --dataset_name /data/swe-bench/swe-bench-lite-full.jsonl \
    --predictions_path /data/swe-bench/all_preds.jsonl \
    --max_workers 32 --timeout 1800 \
    --run_id zhikuncode_eval_300_RETRY1 \
    --namespace none \
    --instance_ids $(cat /tmp/error_ids.txt | tr '\n' ' ')
```

---

## 6. 进度监控

### 6.1 实时日志

```bash
tail -f /data/swe-bench/eval_300.log
# 关键标志:
# Base images built successfully.
# All environment images built successfully.
# Running 300 instances...
# Evaluation: 100%|██████████| 300/300 [3:20:00<00:00, ✓=N, ✖=M, error=K]
```

### 6.2 阶段进度查询

```bash
# Docker 镜像构建进度
docker images | grep -c sweb.base    # 1 = base 完成
docker images | grep -c sweb.env     # 预期 ~50（300 实例跨 ~12-15 仓库 × 多版本）
docker images | grep -c sweb.eval    # instance 镜像，运行时动态构建

# 实例完成数（实时）
RUN_ID=zhikuncode_eval_300_FINAL
find /data/swe-bench/logs/run_evaluation/$RUN_ID -name "report.json" | wc -l

# 进程健康
PID=$(pgrep -f swebench.harness.run_evaluation | head -1)
cat /proc/$PID/status | grep -E "State|Threads"
```

### 6.3 资源占用

```bash
docker system df                    # Docker 镜像/容器/缓存
df -h /data                         # 数据盘
free -h                             # 内存（32 并发约 130 GB 占用）
top -bn1 | head -20                 # CPU
```

---

## 7. 评测结果分析

### 7.1 汇总报告 results.json

swebench 在 `--report_dir` 下生成总报告（文件名形如 `<model>.<run_id>.json`）：

```bash
cat /data/swe-bench/results/*.json | python3 -m json.tool
# 关键字段:
# {
#   "total_instances": 300,
#   "submitted_instances": 300,
#   "completed_instances": 297,
#   "resolved_instances": 150,
#   "unresolved_instances": 147,
#   "empty_patch_instances": 0,
#   "error_instances": 3,
#   "schema_version": 2
# }
```

### 7.2 一键 resolve 数统计

```bash
RUN_ID=zhikuncode_eval_300_FINAL
MODEL=$(ls /data/swe-bench/logs/run_evaluation/$RUN_ID | head -1)
REP=/data/swe-bench/logs/run_evaluation/$RUN_ID/$MODEL

PASS=$(find $REP -name "report.json" -exec python3 -c \
  "import json,sys; r=json.load(open(sys.argv[1])); print(list(r.values())[0].get('resolved', False))" {} \; \
  | grep -c True)
TOTAL=$(find $REP -name "report.json" | wc -l)
echo "Resolved: $PASS / $TOTAL = $(python3 -c 'import sys; p,t=int(sys.argv[1]),int(sys.argv[2]); print(f"{p*100/t:.1f}%")' $PASS $TOTAL)"
```

### 7.3 仓库维度分布

```bash
python3 << 'PYEOF'
import json, glob, os
from collections import defaultdict
base = "/data/swe-bench/logs/run_evaluation/zhikuncode_eval_300_FINAL"
model = os.listdir(base)[0]
stats = defaultdict(lambda: [0, 0])  # repo -> [resolved, total]
for rp in glob.glob(f"{base}/{model}/*/report.json"):
    iid = os.path.basename(os.path.dirname(rp))
    repo = iid.split("__")[0]
    r = list(json.load(open(rp)).values())[0]
    stats[repo][1] += 1
    if r.get("resolved"): stats[repo][0] += 1
for repo, (p, t) in sorted(stats.items(), key=lambda x: -x[1][1]):
    print(f"  {repo:30s} {p:3d}/{t:3d}  ({p*100/t:.0f}%)")
PYEOF
```

---

## 8. 日志管理

### 8.1 日志目录结构

```
/data/swe-bench/logs/run_evaluation/<run_id>/<model_name>/
├── <instance_id>/
│   ├── report.json        # 单实例评测结果（含 resolved: true/false）
│   ├── patch.diff         # 实际应用的补丁
│   ├── run_instance.log   # 容器内运行日志
│   └── test_output.txt    # 测试用例 stdout/stderr
```

> 📌 **空 patch 实例不会生成日志目录**（已知行为，不影响合规性）。
> 📌 路径基准是 `/data/swe-bench/logs/run_evaluation/`，**不是** `/data/swe-bench/eval-logs/`。

### 8.2 关键日志位置速查

| 用途 | 路径 |
|---|---|
| 评测主日志 | `/data/swe-bench/eval_300.log` |
| base 镜像构建 | `/data/swe-bench/logs/build_images/base/.../build_image.log` |
| env 镜像构建 | `/data/swe-bench/logs/build_images/env/sweb.env.*/build_image.log` |
| instance 实例日志 | `/data/swe-bench/logs/run_evaluation/<run_id>/<model>/<iid>/run_instance.log` |
| 单实例测试输出 | `.../test_output.txt` |
| 单实例评测结果 | `.../report.json` |

---

## 9. 磁盘管理

### 9.1 实时占用查看

```bash
df -h /data
docker system df
du -sh /data/docker /data/swe-bench/logs
```

### 9.2 占用估算（300 实例）

| 项目 | 占用 |
|---|---|
| base 镜像 | ~1.5 GB（仅一次） |
| env 镜像（~50 个 × 2.7 GB） | ~135 GB |
| instance 镜像（运行时） | ~80 GB |
| 日志（300 × ~100 KB） | ~30 MB |
| predictions JSONL | ~1 MB |
| **合计** | **~220 GB** |

### 9.3 评测后清理

```bash
# 保留 env 镜像供下次复用，仅清理悬空层
docker image prune -f
docker container prune -f

# 全部清理（不再复用时）
docker system prune -a --volumes
```

---

## 10. 分层采样策略

### 10.1 50 实例先验证可行性

> 💡 第一次跑直接上 300 实例风险大（耗时长、问题难定位）。建议先 50 实例验证全链路，再扩到 300。

按仓库占比分层采样脚本 `/data/swe-bench/sample_dataset.py`：

```python
#!/usr/bin/env python3
"""按仓库占比分层采样 SWE-bench Lite，生成 sampled_N.json"""
import json, sys, random
from collections import Counter, defaultdict

def stratified_sample(input_path, output_path, target_n, seed=42):
    data = json.load(open(input_path))
    random.seed(seed)
    by_repo = defaultdict(list)
    for item in data:
        by_repo[item["repo"]].append(item)
    total = len(data)
    sampled = []
    for repo, items in by_repo.items():
        quota = max(1, round(len(items) / total * target_n))
        sampled.extend(random.sample(items, min(quota, len(items))))
    if len(sampled) > target_n:
        sampled = random.sample(sampled, target_n)
    elif len(sampled) < target_n:
        pool = [x for x in data if x not in sampled]
        sampled.extend(random.sample(pool, target_n - len(sampled)))
    json.dump(sampled, open(output_path, "w"), ensure_ascii=False, indent=2)
    print(f"✅ {output_path}: {len(sampled)} 条")
    for repo, n in Counter(x["repo"] for x in sampled).most_common():
        print(f"  {repo}: {n}")

if __name__ == "__main__":
    stratified_sample(sys.argv[1], sys.argv[2], int(sys.argv[3]))
```

使用：
```bash
python sample_dataset.py swe-bench-lite-array.json sampled_50.json 50
# 然后用 --dataset_name /data/swe-bench/sampled_50.json 启动评测
```

### 10.2 50→300 扩展路径

1. 先跑 50 实例，验证 monkey-patch、镜像构建、网络都正常
2. 全部 50 实例 resolve 率 ≥ 30% 后，再上 300 实例全集
3. 50 实例阶段构建好的 **env 镜像**会被 300 实例评测自动复用，节省 30-50% 时间

---

## 11. 常见问题（实战 12 条）

### 11.1 swebench 包 Python 版本不兼容

**症状**：`SyntaxError: unsupported operand type(s) for |: 'type' and 'type'`
**根因**：系统 Python 3.9 不支持 PEP 604 的 `X | Y` 类型注解
**解决**：`apt install python3.11 python3.11-venv` 创建 3.11 虚拟环境（见 §2.4）

### 11.2 Docker 镜像拉取 403 Forbidden

**症状**：`Get "https://registry-1.docker.io/v2/": 403 Forbidden`
**根因**：阿里云镜像源不收录 swebench 官方镜像
**解决**：使用 `--namespace none` 本地构建；或逐个测试 [§2.3](#23-安装-docker数据盘--镜像加速) 的 4 个镜像源

### 11.3 评测期间 git clone 超时（error 实例）

**症状**：`run_instance.log` 报 `fatal: unable to access 'https://github.com/...': Failed to connect`
**根因**：ECS 直连 GitHub 不稳定，大仓库（matplotlib、django）尤其严重
**解决**：
- 先注入 GitHub 镜像（[§3.1](#31-github-镜像加速修复-git-clone-超时)）
- 增大 `--timeout` 到 1800-3600
- 评测完成后用 `--instance_ids` 重跑 error 实例（env 镜像已就绪，重跑很快）

### 11.4 评测进程卡在 1/300 不动

**症状**：进程在跑但 `docker images` 看不到 `sweb.env.*`
**根因**：`--namespace` 默认为 `swebench`，跳过本地构建去拉 DockerHub 预构建镜像（403）
**解决**：必须显式 `--namespace none`

### 11.5 内存不足 OOM

**症状**：`Killed`，dmesg 显示 `Out of memory: Killed process`
**根因**：32 并发 × 4-6 GB ≈ 128-192 GB，64 GB 实例不够
**解决**：256 GB 实例 / 减少 `--max_workers` 到 16 或 8

### 11.6 评测中断续跑

**症状**：SSH 断线、ECS 重启、进程被 kill
**解决**：
- 始终在 tmux 中运行评测
- 重跑时同 `run_id`：swebench 会自动跳过已有 `report.json` 的实例
- 或换新 `run_id` + `--instance_ids` 指定缺失实例

### 11.7 SSH 连接被重置 / 限流

**症状**：`scp` 多次后 `Connection reset by peer`
**解决**：每次 `scp` 间加 `sleep 5`；改用 SSH key 替代密码登录；走 ECS 控制台 VNC 兜底

### 11.8 eval-logs 目录为空 / 路径错误

**症状**：找不到日志，按 `/data/swe-bench/eval-logs/` 查看为空
**根因**：错误的目录约定
**解决**：正确路径是 `/data/swe-bench/logs/run_evaluation/<run_id>/<model>/`

### 11.9 数据盘未挂载导致系统盘爆满

**症状**：`no space left on device`，`/var/lib/docker` 占满 60 GB 系统盘
**解决**：见 [§2.1](#21-ssh-登录与数据盘挂载) 挂载 `/data` 并配置 Docker `data-root`；已爆盘需先 `systemctl stop docker && rm -rf /var/lib/docker/*`

### 11.10 conda 环境创建失败

**症状**：`CondaHTTPError: HTTP 000 CONNECTION FAILED`
**解决**：在 monkey-patch 中追加 conda 镜像源：
```python
'RUN /opt/miniconda3/bin/conda config --add channels https://mirrors.tuna.tsinghua.edu.cn/anaconda/pkgs/main\n'
'RUN /opt/miniconda3/bin/conda config --set show_channel_urls yes\n'
```

### 11.11 单 instance 测试超时

**症状**：实例报 `timeout`，常见于 sympy、matplotlib
**解决**：`--timeout 1800 → 3600`；或对特定实例单独 `--instance_ids` 重跑加大超时

### 11.12 patch apply 失败（非网络）

**症状**：`error: patch failed`、`error: corrupt patch`
**根因**：模型生成的 patch 与原始仓库差异（行号、上下文）不匹配
**解决**：本地推理时增加 patch 自检；这部分实例重跑也无效，统计上算 unresolved/error

---

## 12. 结果导出到本地

### 12.1 ECS 端打包

```bash
RUN_ID=zhikuncode_eval_300_FINAL
MODEL=$(ls /data/swe-bench/logs/run_evaluation/$RUN_ID | head -1)

# 打包 per-instance 日志（约 30 MB）
tar -czf /root/eval_300_logs.tar.gz \
    -C /data/swe-bench/logs/run_evaluation $RUN_ID

# 单独 results 汇总报告
cp /data/swe-bench/results/*.json /root/results.json
```

### 12.2 本地下载（macOS）

```bash
ECS_IP="<ECS_IP>"
LOCAL_OUT="./docs/swe-bench/20260520"
mkdir -p $LOCAL_OUT/logs

# 1) predictions（生成 patch 的源）
scp root@$ECS_IP:/data/swe-bench/all_preds.jsonl \
    ./swe-bench/results-final-300/

# 2) 评测汇总报告
scp root@$ECS_IP:/data/swe-bench/results/*.json $LOCAL_OUT/results/

# 3) per-instance 日志（含 report.json / patch.diff / test_output.txt）
scp -r root@$ECS_IP:/data/swe-bench/logs/run_evaluation/zhikuncode_eval_300_FINAL/ \
    $LOCAL_OUT/logs/

# 或直接拉打包文件
scp root@$ECS_IP:/root/eval_300_logs.tar.gz $LOCAL_OUT/
tar -xzf $LOCAL_OUT/eval_300_logs.tar.gz -C $LOCAL_OUT/logs/
```

### 12.3 SSH 密码登录辅助

> ⚠️ ECS 默认密码登录，多次连接可能被限流；推荐：

**macOS 使用 sshpass**：
```bash
brew install hudochenkov/sshpass/sshpass    # macOS 原版 brew 已下架
sshpass -p '<PASSWORD>' scp root@$ECS_IP:/data/swe-bench/all_preds.jsonl ./
```

**或配置 SSH key**：
```bash
ssh-copy-id root@$ECS_IP   # 一次性配置
# 之后 scp/ssh 免密
```

**或使用 expect 自动化**（macOS 自带）：
```bash
expect -c "set timeout 60; spawn scp root@$ECS_IP:/data/swe-bench/all_preds.jsonl ./; \
    expect \"password:\" {send \"<PASSWORD>\r\"}; expect eof"
```

### 12.4 提交合规性检查清单

下载完成后，最终交付目录应包含：

```
swe-bench/submission/20260520_zhikuncode/
├── all_preds.jsonl              # 模型预测（300 行）
├── results.json                 # 汇总报告
├── logs/
│   └── zhikuncode_eval_300_FINAL/
│       └── <model>/
│           └── <instance_id>/
│               ├── report.json
│               ├── patch.diff
│               ├── run_instance.log
│               └── test_output.txt
└── README.md                    # 提交说明（trajectory、模型、版本）
```

> 📌 **合规性提醒**：空 patch 实例不会生成日志目录，是 SWE-bench 已知行为；提交时无需为这些实例补造文件。

---

## 📎 附录：一键部署脚本骨架

`ecs-deploy.sh` 已保存在 `docs/swe-bench/scripts/ecs-deploy.sh`。将其上传到 ECS 并执行：

```bash
scp $PROJECT_ROOT/docs/swe-bench/scripts/ecs-deploy.sh root@<ECS_IP>:/root/
```

完整脚本内容（也可直接使用上传的文件）：

```bash
#!/bin/bash
set -e
log() { echo "[$(date '+%H:%M:%S')] $1"; }

log "[1/5] 数据盘挂载"
DISK=/dev/nvme1n1
[ -b $DISK ] || { log "❌ 无 NVMe 数据盘，请检查实例规格"; exit 1; }
[ -z "$(blkid -s TYPE -o value $DISK 2>/dev/null)" ] && mkfs.ext4 -F $DISK
mountpoint -q /data || { mkdir -p /data && mount $DISK /data; }
UUID=$(blkid -s UUID -o value $DISK)
grep -q "$UUID" /etc/fstab || echo "UUID=$UUID /data ext4 defaults,noatime 0 2" >> /etc/fstab

log "[2/5] 系统软件 + Docker"
sed -i 's|http://archive.ubuntu.com|https://mirrors.aliyun.com|g; s|http://security.ubuntu.com|https://mirrors.aliyun.com|g' /etc/apt/sources.list
apt-get update -q
apt-get install -y -q ca-certificates curl gnupg lsb-release jq python3.11 python3.11-venv tmux
if ! command -v docker &>/dev/null; then
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://mirrors.aliyun.com/docker-ce/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/docker.gpg] https://mirrors.aliyun.com/docker-ce/linux/ubuntu $(lsb_release -cs) stable" \
        > /etc/apt/sources.list.d/docker.list
    apt-get update -q && apt-get install -y -q docker-ce docker-ce-cli containerd.io
fi

log "[3/5] Docker 配置（data-root + 镜像加速）"
mkdir -p /etc/docker
cat > /etc/docker/daemon.json << 'EOF'
{
    "data-root": "/data/docker",
    "registry-mirrors": [
        "https://docker.1panel.live",
        "https://hub-mirror.c.163.com",
        "https://docker.m.daocloud.io",
        "https://mirror.ccs.tencentyun.com"
    ],
    "storage-driver": "overlay2"
}
EOF
systemctl enable docker && systemctl restart docker && sleep 3

log "[4/5] swebench + Python 3.11 venv"
mkdir -p /data/swe-bench && cd /data/swe-bench
[ -d venv ] || python3.11 -m venv venv
source venv/bin/activate
mkdir -p ~/.pip
cat > ~/.pip/pip.conf << 'EOF'
[global]
index-url = https://mirrors.aliyun.com/pypi/simple/
trusted-host = mirrors.aliyun.com
EOF
pip install -q --upgrade pip
pip install -q swebench

log "[5/5] Git + HF 镜像 + monkey-patch"
git config --global url."https://ghproxy.com/https://github.com/".insteadOf "https://github.com/"
git config --global http.postBuffer 524288000
git config --global http.lowSpeedLimit 1000 && git config --global http.lowSpeedTime 60
echo 'export HF_ENDPOINT=https://hf-mirror.com' >> /root/.bashrc
echo 'export HF_HOME=/data/swe-bench/hf_cache' >> /root/.bashrc
[ -f /root/inject-pip-mirror.sh ] && bash /root/inject-pip-mirror.sh

log "✅ 环境就绪。请上传 all_preds.jsonl 与 swe-bench-lite-full.jsonl 到 /data/swe-bench/，然后参考 §5.2 启动评测。"
```

---

**文档结束** | 版本 v3.0 | 最后更新 2026-05-20

> 🔁 与本手册配套的 `swe-bench/SWE-bench打榜可执行手册.md`（macOS 本地推理流程）和 `阿里云ECS部署SWE-bench Lite评测手册.md`（如另存）共同构成完整打榜链路。
