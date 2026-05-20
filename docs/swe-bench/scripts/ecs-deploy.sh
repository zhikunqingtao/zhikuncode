#!/usr/bin/env bash
###############################################################################
#
#  ECS 一键 SWE-bench 评测部署脚本
#  ====================================
#
#  适用环境: 阿里云 ECS Ubuntu 22.04, ecs.c9i.4xlarge (16 vCPU, 32 GiB)
#  数据盘: 200GB ESSD (/dev/vdb)
#
#  用法:
#    1. 将此脚本和 predictions 文件上传到 ECS:
#       scp ecs-deploy.sh all_preds.jsonl root@<ECS_IP>:/root/
#
#    2. SSH 连接到 ECS 并执行:
#       ssh root@<ECS_IP>
#       chmod +x /root/ecs-deploy.sh
#       nohup /root/ecs-deploy.sh > /root/deploy.log 2>&1 &
#       tail -f /root/deploy.log
#
#    3. 评测完成后下载结果:
#       scp root@<ECS_IP>:/data/swe-bench/eval-results.tar.gz ./
#
#  Predictions 文件信息:
#    - 总 instance 数: 20
#    - 有效 patch 数: 15 (model_patch 非空)
#    - 空 patch 数: 5
#    - 有效 instance_id:
#      astropy__astropy-6938, astropy__astropy-14182, astropy__astropy-14365
#      django__django-11099, django__django-10914, django__django-11001
#      django__django-11019, django__django-11049, django__django-11039
#      django__django-11583, django__django-11179, django__django-11283
#      django__django-11422, django__django-11620, django__django-11564
#
#  预估总耗时: 2~4 小时 (取决于镜像构建速度)
#    - 系统初始化: ~5 分钟
#    - Python 环境: ~3 分钟
#    - Docker 镜像构建: ~60-120 分钟
#    - 评测执行: ~60-90 分钟
#    - 结果收集: ~1 分钟
#
###############################################################################

set -e
set -o pipefail

# ============================================================================
# 辅助函数
# ============================================================================

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

log_section() {
    echo ""
    echo "============================================================================"
    log "$1"
    echo "============================================================================"
}

check_exit() {
    if [ $? -ne 0 ]; then
        log "❌ 错误: $1"
        exit 1
    fi
}

# ============================================================================
# 全局变量
# ============================================================================

DATA_DISK="/dev/vdb"  # 传统设备名，NVMe设备会自动检测
DATA_MOUNT="/data"
WORK_DIR="/data/swe-bench"
VENV_DIR="/data/swe-bench/venv"
PREDICTIONS_FILE="/data/swe-bench/all_preds.jsonl"
RESULTS_DIR="/data/swe-bench/results"
DOCKER_DATA_DIR="/data/docker"

# ============================================================================
# Part 1: 系统初始化
# ============================================================================

log_section "🚀 Part 1: 系统初始化 (预计 5 分钟)"

# --- 1.1 检测并格式化挂载数据盘 ---
log "📦 [1.1] 检测数据盘..."

if mountpoint -q "$DATA_MOUNT" 2>/dev/null; then
    log "✅ 数据盘已挂载到 $DATA_MOUNT，跳过"
else
    # 自动检测数据盘设备（支持传统和NVMe设备名）
    if [ -b "$DATA_DISK" ]; then
        DISK_DEVICE="$DATA_DISK"
    elif [ -b "/dev/xvdb" ]; then
        DISK_DEVICE="/dev/xvdb"
    elif [ -b "/dev/sdb" ]; then
        DISK_DEVICE="/dev/sdb"
    elif [ -b "/dev/nvme1n1" ]; then
        DISK_DEVICE="/dev/nvme1n1"
    elif [ -b "/dev/nvme2n1" ]; then
        DISK_DEVICE="/dev/nvme2n1"
    else
        # 尝试查找未挂载的非系统盘
        DISK_DEVICE=$(lsblk -dpno NAME,TYPE | awk '$2=="disk"' | while read dev _; do
            if ! lsblk -no MOUNTPOINT "$dev" 2>/dev/null | grep -q .; then
                echo "$dev"; break
            fi
        done)
        if [ -z "$DISK_DEVICE" ]; then
            log "❌ 未找到数据盘设备，请手动指定"
            exit 1
        fi
    fi
    log "  检测到数据盘: $DISK_DEVICE"

    # 检查是否已有文件系统
    FS_TYPE=$(blkid -s TYPE -o value "$DISK_DEVICE" 2>/dev/null || true)
    if [ -z "$FS_TYPE" ]; then
        log "  格式化数据盘为 ext4..."
        mkfs.ext4 -F "$DISK_DEVICE"
    else
        log "  数据盘已有文件系统: $FS_TYPE"
    fi

    # 创建挂载点并挂载
    mkdir -p "$DATA_MOUNT"
    mount "$DISK_DEVICE" "$DATA_MOUNT"

    # 添加到 fstab 实现开机自动挂载
    DISK_UUID=$(blkid -s UUID -o value "$DISK_DEVICE")
    if ! grep -q "$DISK_UUID" /etc/fstab 2>/dev/null; then
        echo "UUID=$DISK_UUID $DATA_MOUNT ext4 defaults,noatime 0 2" >> /etc/fstab
    fi
    log "✅ 数据盘已挂载到 $DATA_MOUNT (UUID=$DISK_UUID)"
fi

# 显示磁盘空间
df -h "$DATA_MOUNT"

# --- 1.2 配置 apt 镜像源 ---
log "🔧 [1.2] 配置 apt 阿里云内网镜像源..."

cat > /etc/apt/sources.list << 'EOF'
deb http://mirrors.cloud.aliyuncs.com/ubuntu/ jammy main restricted universe multiverse
deb http://mirrors.cloud.aliyuncs.com/ubuntu/ jammy-updates main restricted universe multiverse
deb http://mirrors.cloud.aliyuncs.com/ubuntu/ jammy-backports main restricted universe multiverse
deb http://mirrors.cloud.aliyuncs.com/ubuntu/ jammy-security main restricted universe multiverse
EOF

log "✅ apt 源已配置"

# --- 1.3 安装系统包 ---
log "📦 [1.3] 安装系统包..."

apt-get update -qq

# 安装基础工具
apt-get install -y -qq software-properties-common curl wget git

# 添加 deadsnakes PPA 安装 Python 3.11
if ! command -v python3.11 &>/dev/null; then
    add-apt-repository -y ppa:deadsnakes/ppa
    apt-get update -qq
fi

apt-get install -y -qq \
    docker.io \
    python3.11 \
    python3.11-venv \
    python3.11-dev \
    python3.11-distutils \
    git \
    jq

log "✅ 系统包安装完成"
python3.11 --version
docker --version

# --- 1.4 配置 Docker ---
log "🐳 [1.4] 配置 Docker..."

# 停止 Docker (如果正在运行)
systemctl stop docker 2>/dev/null || true

# 创建 Docker 数据目录
mkdir -p "$DOCKER_DATA_DIR"

# 配置 Docker daemon
mkdir -p /etc/docker
cat > /etc/docker/daemon.json << EOF
{
    "data-root": "$DOCKER_DATA_DIR",
    "registry-mirrors": [
        "https://registry.cn-hangzhou.aliyuncs.com",
        "https://mirror.ccs.tencentyun.com"
    ],
    "storage-driver": "overlay2",
    "log-driver": "json-file",
    "log-opts": {
        "max-size": "50m",
        "max-file": "3"
    }
}
EOF

# --- 1.5 启动 Docker ---
log "🐳 [1.5] 启动 Docker 服务..."

systemctl daemon-reload
systemctl start docker
systemctl enable docker

# 验证 Docker
docker info | grep -i "docker root dir"
log "✅ Docker 已启动，数据目录: $DOCKER_DATA_DIR"

# --- 1.6 创建工作目录 ---
log "📁 [1.6] 创建工作目录..."
mkdir -p "$WORK_DIR"
mkdir -p "$RESULTS_DIR"
log "✅ 工作目录: $WORK_DIR"

# ============================================================================
# Part 2: Python 环境
# ============================================================================

log_section "🐍 Part 2: Python 环境配置 (预计 3 分钟)"

# --- 2.1 创建虚拟环境 ---
log "🐍 [2.1] 创建 Python 3.11 虚拟环境..."

if [ -d "$VENV_DIR" ] && [ -f "$VENV_DIR/bin/activate" ]; then
    log "✅ 虚拟环境已存在，跳过创建"
else
    python3.11 -m venv "$VENV_DIR"
    log "✅ 虚拟环境已创建: $VENV_DIR"
fi

# 激活虚拟环境
source "$VENV_DIR/bin/activate"

# --- 2.2 配置 pip 镜像源 ---
log "📦 [2.2] 配置 pip 阿里云镜像源..."

mkdir -p ~/.pip
cat > ~/.pip/pip.conf << 'EOF'
[global]
index-url = https://mirrors.cloud.aliyuncs.com/pypi/simple/
trusted-host = mirrors.cloud.aliyuncs.com

[install]
trusted-host = mirrors.cloud.aliyuncs.com
EOF

# 升级 pip
pip install --upgrade pip -q

# --- 2.3 安装 swebench ---
log "📦 [2.3] 安装 swebench..."

pip install swebench -q
check_exit "swebench 安装失败"

log "✅ swebench 已安装"
pip show swebench | grep -E "^(Name|Version)"

# ============================================================================
# Part 3: 评测执行
# ============================================================================

log_section "🧪 Part 3: SWE-bench 评测执行 (预计 2-3 小时)"

# --- 3.1 检查 predictions 文件 ---
log "📋 [3.1] 检查 predictions 文件..."

# 如果文件在 /root/ 下，复制到工作目录
if [ -f "/root/all_preds.jsonl" ] && [ ! -f "$PREDICTIONS_FILE" ]; then
    cp /root/all_preds.jsonl "$PREDICTIONS_FILE"
    log "  已从 /root/ 复制 predictions 文件到工作目录"
fi

if [ ! -f "$PREDICTIONS_FILE" ]; then
    log "❌ 错误: predictions 文件不存在: $PREDICTIONS_FILE"
    log "  请先通过 scp 上传 all_preds.jsonl 到 /root/ 或 $PREDICTIONS_FILE"
    exit 1
fi

TOTAL_INSTANCES=$(wc -l < "$PREDICTIONS_FILE" | tr -d ' ')
VALID_PATCHES=$(grep -c '"model_patch": "[^"]' "$PREDICTIONS_FILE" || echo "0")
log "  总 instance 数: $TOTAL_INSTANCES"
log "  有效 patch 数: $VALID_PATCHES"
log "✅ predictions 文件就绪"

# --- 3.2 运行评测 ---
log "🔬 [3.2] 开始 SWE-bench 评测..."
log "  ⚠️  注意: 首次运行需要构建 Docker 环境镜像和实例镜像"
log "  ⚠️  使用 --namespace none 触发本地镜像构建"
log "  ⚠️  预计耗时 2-3 小时，请耐心等待"
log ""
log "  评测参数:"
log "    --dataset_name: princeton-nlp/SWE-bench_Lite"
log "    --predictions_path: $PREDICTIONS_FILE"
log "    --max_workers: 4"
log "    --timeout: 900 (15 分钟/instance)"
log "    --run_id: zhikuncode_eval"
log "    --namespace: none (本地构建镜像)"
log "    --report_dir: $RESULTS_DIR"
log ""

EVAL_START_TIME=$(date +%s)

python -m swebench.harness.run_evaluation \
    --dataset_name princeton-nlp/SWE-bench_Lite \
    --predictions_path "$PREDICTIONS_FILE" \
    --max_workers 4 \
    --timeout 900 \
    --run_id zhikuncode_eval \
    --namespace none \
    --report_dir "$RESULTS_DIR"

EVAL_END_TIME=$(date +%s)
EVAL_DURATION=$(( (EVAL_END_TIME - EVAL_START_TIME) / 60 ))
log "✅ 评测完成！耗时: ${EVAL_DURATION} 分钟"

# ============================================================================
# Part 4: 结果收集
# ============================================================================

log_section "📊 Part 4: 结果收集与汇总"

# --- 4.1 汇总结果 ---
log "📊 [4.1] 汇总评测结果..."

SUMMARY_FILE="$WORK_DIR/eval-summary.json"

# 查找所有 report.json 文件
REPORT_FILES=$(find "$RESULTS_DIR" -name "*.json" -type f 2>/dev/null)

if [ -z "$REPORT_FILES" ]; then
    log "⚠️  未找到 report 文件，尝试查找其他结果文件..."
    find "$RESULTS_DIR" -type f | head -20
else
    log "  找到结果文件:"
    echo "$REPORT_FILES" | while read f; do echo "    $f"; done
fi

# 生成汇总报告
python3.11 << 'PYTHON_SCRIPT'
import json
import os
import glob

results_dir = "/data/swe-bench/results"
summary = {
    "run_id": "zhikuncode_eval",
    "total_instances": 0,
    "resolved": [],
    "failed": [],
    "error": [],
    "no_patch": [],
    "resolved_count": 0,
    "failed_count": 0,
    "error_count": 0,
    "resolve_rate": 0.0
}

# 查找所有结果 JSON 文件
report_files = glob.glob(os.path.join(results_dir, "**", "*.json"), recursive=True)

for report_file in report_files:
    try:
        with open(report_file, 'r') as f:
            data = json.load(f)
        
        # swebench report format
        if isinstance(data, dict):
            # Check for standard report format
            for instance_id, result in data.items():
                if isinstance(result, dict):
                    summary["total_instances"] += 1
                    status = result.get("resolved", result.get("status", False))
                    if status is True or status == "resolved":
                        summary["resolved"].append(instance_id)
                    elif status == "error" or result.get("error"):
                        summary["error"].append(instance_id)
                    else:
                        summary["failed"].append(instance_id)
                elif isinstance(result, bool):
                    summary["total_instances"] += 1
                    if result:
                        summary["resolved"].append(instance_id)
                    else:
                        summary["failed"].append(instance_id)
    except (json.JSONDecodeError, Exception) as e:
        print(f"  警告: 无法解析 {report_file}: {e}")

# Also check for the standard swebench output format
results_log = glob.glob(os.path.join(results_dir, "**", "results.json"), recursive=True)
for log_file in results_log:
    try:
        with open(log_file, 'r') as f:
            data = json.load(f)
        if "resolved" in data:
            summary["resolved"] = data["resolved"]
        if "applied" in data:
            summary["total_instances"] = len(data.get("applied", []))
    except Exception:
        pass

summary["resolved_count"] = len(summary["resolved"])
summary["failed_count"] = len(summary["failed"])
summary["error_count"] = len(summary["error"])

total_with_patch = summary["resolved_count"] + summary["failed_count"] + summary["error_count"]
if total_with_patch > 0:
    summary["resolve_rate"] = round(summary["resolved_count"] / total_with_patch * 100, 2)

# Write summary
with open("/data/swe-bench/eval-summary.json", 'w') as f:
    json.dump(summary, f, indent=2)

# Print summary
print("\n" + "=" * 60)
print("  SWE-bench 评测结果摘要")
print("=" * 60)
print(f"  Run ID:          zhikuncode_eval")
print(f"  总评测实例:      {total_with_patch}")
print(f"  ✅ Resolved:     {summary['resolved_count']}")
print(f"  ❌ Failed:       {summary['failed_count']}")
print(f"  ⚠️  Error:        {summary['error_count']}")
print(f"  📈 Resolve Rate: {summary['resolve_rate']}%")
print("=" * 60)

if summary["resolved"]:
    print("\n  Resolved instances:")
    for inst in sorted(summary["resolved"]):
        print(f"    ✅ {inst}")

if summary["failed"]:
    print("\n  Failed instances:")
    for inst in sorted(summary["failed"]):
        print(f"    ❌ {inst}")

if summary["error"]:
    print("\n  Error instances:")
    for inst in sorted(summary["error"]):
        print(f"    ⚠️  {inst}")

print("")
PYTHON_SCRIPT

# --- 4.2 打包结果 ---
log "📦 [4.2] 打包评测结果..."

ARCHIVE_FILE="/data/swe-bench/eval-results.tar.gz"
cd /data/swe-bench
tar -czf "$ARCHIVE_FILE" \
    results/ \
    eval-summary.json \
    all_preds.jsonl \
    2>/dev/null || true

ARCHIVE_SIZE=$(du -h "$ARCHIVE_FILE" | cut -f1)
log "✅ 结果已打包: $ARCHIVE_FILE ($ARCHIVE_SIZE)"

# --- 4.3 最终摘要 ---
log_section "🎉 评测完成！"

log "📁 结果文件位置:"
log "   汇总报告: /data/swe-bench/eval-summary.json"
log "   详细结果: /data/swe-bench/results/"
log "   打包文件: /data/swe-bench/eval-results.tar.gz"
log ""
log "📥 下载结果:"
log "   scp root@<ECS_IP>:/data/swe-bench/eval-results.tar.gz ./"
log ""
log "⏱️  总耗时: ${EVAL_DURATION:-N/A} 分钟"
log ""
log "Done! 🎊"
