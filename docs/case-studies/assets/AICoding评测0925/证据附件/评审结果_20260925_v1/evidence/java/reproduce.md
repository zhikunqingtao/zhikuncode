# Java 证据复现索引

工作目录：`/Users/guoqingtao/Desktop/AICoding评测0925/评审结果_20260925_v1/evidence/java`。

所有执行都应保持在 archive 副本；不要在原仓库运行构建或写入 probe。

```sh
./source-head/backend/mvnw -f source-head/backend/pom.xml -q \
  -Djava.io.tmpdir='/Users/guoqingtao/Desktop/AICoding评测0925/评审结果_20260925_v1/evidence/java/tmp' \
  -Dtest=GroundTruthLifecycleProbeTest test > logs/lifecycle-maven.log 2>&1

./source-parent/backend/mvnw -f source-parent/backend/pom.xml -q \
  -Djava.io.tmpdir='/Users/guoqingtao/Desktop/AICoding评测0925/评审结果_20260925_v1/evidence/java/tmp' \
  -Dtest=GroundTruthParentWindowTest test > logs/parent-window-maven.log 2>&1

javac -d probe-classes \
  source-head/backend/src/main/java/com/aicodeassistant/tool/process/OwnedProcess.java \
  probes/OwnedProcessPortableProbe.java

java -cp probe-classes OwnedProcessPortableProbe \
  '/Users/guoqingtao/Desktop/AICoding评测0925/评审结果_20260925_v1/evidence/java/tmp/portable-mac' \
  > logs/portable-mac.log 2>&1

/usr/local/bin/docker run --rm --init --network none \
  --mount 'type=bind,src=/Users/guoqingtao/Desktop/AICoding评测0925/评审结果_20260925_v1/evidence/java,dst=/evidence' \
  eclipse-temurin:21-jdk java -cp /evidence/probe-classes \
  OwnedProcessPortableProbe /evidence/tmp/portable-linux > logs/portable-linux.log 2>&1
```

执行环境：macOS 26.5.2/aarch64，Amazon Corretto 21.0.10；Linux 使用已有 eclipse-temurin:21-jdk 镜像（本次 inspect image ID 为 1f79c73404fb）。

- `probes/GroundTruthLifecycleProbeTest.java`：当前可复现源，4 项。
- `probes/GroundTruthLifecycleProbeTest.executed.java`：成功日志对应的原始已执行源（之后仅限定 CWD probe 的临时文件目录；见报告边界）。
- `probes/GroundTruthParentWindowTest.java`：父提交启动窗口对照，1 项。
- `probes/OwnedProcessPortableProbe.java`：实际父退出/子存活对照，macOS 与 Linux 同源。
- `logs/lifecycle-maven.log`、`logs/parent-window-maven.log`、`logs/portable-*.log`：实际输出。
- `logs/surefire-head/`、`logs/surefire-parent/`：JUnit XML 与 TXT，证明通过数。
- `logs/lifecycle-maven-initial-syntax-error.log`：probe 初版语法错误原始输出，仅供审计。

archive 创建操作（已经完成，不需重复）来自原仓库 `git archive HEAD` 和 `git archive HEAD^` 解压到本目录对应 source 文件夹；两套工程只额外加入各自 probe 测试源。
