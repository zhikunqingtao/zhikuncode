#!/bin/bash
# 修复 swebench Docker 构建中的 pip 网络问题
# 通过在 Dockerfile 模板中注入国内 pip 镜像源
set -e

source /data/swe-bench/venv/bin/activate

echo "=== 杀掉当前 swebench 进程 ==="
pkill -f 'swebench.harness.run_evaluation' 2>/dev/null || true
sleep 2

echo "=== 定位 docker_build.py ==="
DOCKER_BUILD_PY=$(python3 -c "import swebench.harness.docker_build; print(swebench.harness.docker_build.__file__)")
echo "文件路径: $DOCKER_BUILD_PY"

echo "=== 备份原文件 ==="
cp "$DOCKER_BUILD_PY" "${DOCKER_BUILD_PY}.bak"

echo "=== 注入 pip 镜像源到 Dockerfile 模板 ==="
python3 << 'PYEOF'
import swebench.harness.docker_build
import inspect

filepath = swebench.harness.docker_build.__file__

with open(filepath, 'r') as f:
    content = f.read()

# 方法: 在 env Dockerfile 中，在 COPY setup_env.sh 之后，RUN setup_env.sh 之前
# 插入 pip 和 conda 镜像配置
# 找到 env image 的 Dockerfile 模板部分

# 搜索: 在 Dockerfile 中的 setup_env.sh RUN 命令之前添加 pip 配置
# Pattern: 'RUN /bin/bash -c "source ~/.bashrc && /root/setup_env.sh"'
# 替换为: 添加 pip 和 conda 镜像配置的行

old_pattern = '''RUN /bin/bash -c "source ~/.bashrc && /root/setup_env.sh"'''
new_pattern = '''RUN pip config set global.index-url https://mirrors.aliyun.com/pypi/simple/ && pip config set global.trusted-host mirrors.aliyun.com
RUN /opt/miniconda3/bin/conda config --add channels https://mirrors.tuna.tsinghua.edu.cn/anaconda/pkgs/main && /opt/miniconda3/bin/conda config --add channels https://mirrors.tuna.tsinghua.edu.cn/anaconda/pkgs/free && /opt/miniconda3/bin/conda config --set show_channel_urls yes
RUN /bin/bash -c "source ~/.bashrc && /root/setup_env.sh"'''

if old_pattern in content:
    content = content.replace(old_pattern, new_pattern)
    with open(filepath, 'w') as f:
        f.write(content)
    print("✅ 已成功注入 pip/conda 镜像源到 docker_build.py")
else:
    # 可能是 f-string 或其他格式，尝试不同的方式
    print("⚠️ 未找到精确模式，尝试其他方式...")
    
    # 找到 get_env_configs_imagefiles 或生成 Dockerfile 的函数
    # 查看是如何生成的
    lines = content.split('\n')
    for i, line in enumerate(lines):
        if 'setup_env' in line and 'bashrc' in line:
            print(f"  找到 line {i}: {line.strip()}")
    
    # 也搜索 Dockerfile 模板字符串
    for i, line in enumerate(lines):
        if 'setup_env.sh' in line and ('RUN' in line or 'dockerfile' in line.lower()):
            print(f"  相关 line {i}: {line.strip()}")

PYEOF

echo ""
echo "=== 清理之前失败的构建缓存 ==="
rm -rf /root/logs/build_images
docker rmi $(docker images | grep sweb.env | awk '{print $1}') 2>/dev/null || true

echo ""
echo "=== 重新启动评测 ==="
export HF_ENDPOINT=https://hf-mirror.com
export HF_HOME=/data/swe-bench/hf_cache
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
echo ">>> 评测已重新启动，查看日志: tail -f /root/eval.log"
