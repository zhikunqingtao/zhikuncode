#!/bin/bash
set -e
source /data/swe-bench/venv/bin/activate
DOCKER_BUILD_PY=$(python3 -c "import swebench.harness.docker_build; print(swebench.harness.docker_build.__file__)")
echo "Target: $DOCKER_BUILD_PY"

# Restore from backup first to ensure clean state
if [ -f "${DOCKER_BUILD_PY}.bak.github" ]; then
    cp "${DOCKER_BUILD_PY}.bak.github" "$DOCKER_BUILD_PY"
    echo "Restored from backup (clean state)"
else
    cp "$DOCKER_BUILD_PY" "${DOCKER_BUILD_PY}.bak.github"
    echo "Backup created: ${DOCKER_BUILD_PY}.bak.github"
fi

python3 << 'PYEOF'
import swebench.harness.docker_build as db
filepath = db.__file__

with open(filepath, "r") as f:
    content = f.read()

# Anchor: insert git config injection BEFORE "Write the dockerfile to the build directory"
anchor = "        # Write the dockerfile to the build directory"

github_inject_block = '''
        # [GITHUB MIRROR] Git network resilience: large buffer + retry wrapper
        github_mirror_lines = (
            'RUN git config --global http.postBuffer 524288000\\n'
        )
        # Replace setup_repo.sh with retry wrapper (3 attempts, 15s pause)
        setup_retry_cmd = (
            'RUN for attempt in 1 2 3; do '
            '/bin/bash /root/setup_repo.sh && break || '
            '{ echo "[RETRY] Attempt $attempt failed, retrying in 15s..."; sleep 15; }; done\\n'
        )
        if 'setup_repo.sh' in dockerfile:
            dockerfile = dockerfile.replace(
                'RUN /bin/bash /root/setup_repo.sh',
                github_mirror_lines + setup_retry_cmd
            )
'''

if "GITHUB MIRROR" in content:
    print("SKIP: Already injected (idempotent)")
elif anchor in content:
    new_content = content.replace(anchor, github_inject_block + "\n" + anchor, 1)
    with open(filepath, "w") as f:
        f.write(new_content)
    print("OK: Git retry + buffer config injected")
else:
    print("ERROR: Anchor not found")
    exit(1)
PYEOF

echo "=== Verification ==="
grep -n "GITHUB MIRROR\|github_mirror_lines\|setup_repo\|setup_retry" "$DOCKER_BUILD_PY" | head -15
