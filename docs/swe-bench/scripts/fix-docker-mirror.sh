#!/bin/bash
# 修复 Docker 镜像加速器配置
set -e

echo ">>> 更新 Docker daemon.json..."
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
    "log-opts": {
        "max-size": "50m",
        "max-file": "3"
    }
}
EOF

echo ">>> 重启 Docker..."
systemctl restart docker
sleep 3

echo ">>> 验证 Docker 镜像加速器..."
docker info 2>/dev/null | grep -A6 "Registry Mirrors"

echo ">>> 测试拉取 ubuntu:22.04..."
timeout 60 docker pull ubuntu:22.04

echo ">>> Docker 镜像修复完成！"
echo ""
echo ">>> 重新启动 SWE-bench 评测..."

# 清理之前的构建缓存
rm -rf /root/logs/build_images

# 重新启动评测
source /data/swe-bench/venv/bin/activate
nohup python -m swebench.harness.run_evaluation \
    --dataset_name /data/swe-bench/swe-bench-lite-array.json \
    --predictions_path /data/swe-bench/all_preds.jsonl \
    --max_workers 4 \
    --timeout 900 \
    --run_id zhikuncode_eval \
    --namespace none \
    --report_dir /data/swe-bench/results \
    > /root/eval.log 2>&1 &

echo "EVAL_PID=$!"
echo ">>> 评测已在后台启动，查看日志: tail -f /root/eval.log"
