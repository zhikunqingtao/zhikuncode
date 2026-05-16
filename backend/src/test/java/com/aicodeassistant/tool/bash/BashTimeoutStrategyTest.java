package com.aicodeassistant.tool.bash;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BashTool 动态超时策略单元测试。
 * 验证 BashCommandClassifier.classifyForTimeout() 能正确识别命令类型并推荐超时时间。
 */
class BashTimeoutStrategyTest {

    private BashCommandClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new BashCommandClassifier();
    }

    @Nested
    @DisplayName("编译命令 → 300s")
    class CompilationCommands {

        @Test
        void mvnCompile() {
            assertThat(classifier.classifyForTimeout("mvn compile").getRecommendedTimeoutMs())
                .isEqualTo(300_000L);
        }

        @Test
        void mvnwCleanPackage() {
            assertThat(classifier.classifyForTimeout("./mvnw clean package -DskipTests").getRecommendedTimeoutMs())
                .isEqualTo(300_000L);
        }

        @Test
        void npmRunBuild() {
            assertThat(classifier.classifyForTimeout("npm run build").getRecommendedTimeoutMs())
                .isEqualTo(300_000L);
        }

        @Test
        void cargoBuild() {
            assertThat(classifier.classifyForTimeout("cargo build --release").getRecommendedTimeoutMs())
                .isEqualTo(300_000L);
        }

        @Test
        void make() {
            assertThat(classifier.classifyForTimeout("make -j4").getRecommendedTimeoutMs())
                .isEqualTo(300_000L);
        }

        @Test
        void gradleBuild() {
            assertThat(classifier.classifyForTimeout("./gradlew build").getRecommendedTimeoutMs())
                .isEqualTo(300_000L);
        }

        @Test
        void goBuild() {
            assertThat(classifier.classifyForTimeout("go build ./...").getRecommendedTimeoutMs())
                .isEqualTo(300_000L);
        }
    }

    @Nested
    @DisplayName("测试命令 → 600s")
    class TestCommands {

        @Test
        void mvnTest() {
            assertThat(classifier.classifyForTimeout("mvn test").getRecommendedTimeoutMs())
                .isEqualTo(600_000L);
        }

        @Test
        void mvnwTest() {
            assertThat(classifier.classifyForTimeout("./mvnw test -pl backend").getRecommendedTimeoutMs())
                .isEqualTo(600_000L);
        }

        @Test
        void pytest() {
            assertThat(classifier.classifyForTimeout("pytest tests/").getRecommendedTimeoutMs())
                .isEqualTo(600_000L);
        }

        @Test
        void npxJest() {
            assertThat(classifier.classifyForTimeout("npx jest --coverage").getRecommendedTimeoutMs())
                .isEqualTo(600_000L);
        }

        @Test
        void npxPlaywright() {
            assertThat(classifier.classifyForTimeout("npx playwright test").getRecommendedTimeoutMs())
                .isEqualTo(600_000L);
        }

        @Test
        void cargoTest() {
            assertThat(classifier.classifyForTimeout("cargo test").getRecommendedTimeoutMs())
                .isEqualTo(600_000L);
        }

        @Test
        void goTest() {
            assertThat(classifier.classifyForTimeout("go test ./...").getRecommendedTimeoutMs())
                .isEqualTo(600_000L);
        }

        @Test
        void mvnVerify() {
            assertThat(classifier.classifyForTimeout("mvn verify").getRecommendedTimeoutMs())
                .isEqualTo(600_000L);
        }
    }

    @Nested
    @DisplayName("包安装命令 → 300s")
    class PackageInstallCommands {

        @Test
        void npmInstall() {
            assertThat(classifier.classifyForTimeout("npm install").getRecommendedTimeoutMs())
                .isEqualTo(300_000L);
        }

        @Test
        void npmCi() {
            assertThat(classifier.classifyForTimeout("npm ci").getRecommendedTimeoutMs())
                .isEqualTo(300_000L);
        }

        @Test
        void pipInstall() {
            assertThat(classifier.classifyForTimeout("pip install -r requirements.txt").getRecommendedTimeoutMs())
                .isEqualTo(300_000L);
        }

        @Test
        void yarnInstall() {
            assertThat(classifier.classifyForTimeout("yarn install").getRecommendedTimeoutMs())
                .isEqualTo(300_000L);
        }

        @Test
        void mvnDependency() {
            assertThat(classifier.classifyForTimeout("mvn dependency:resolve").getRecommendedTimeoutMs())
                .isEqualTo(300_000L);
        }
    }

    @Nested
    @DisplayName("Git操作 → 60s")
    class GitCommands {

        @Test
        void gitStatus() {
            assertThat(classifier.classifyForTimeout("git status").getRecommendedTimeoutMs())
                .isEqualTo(60_000L);
        }

        @Test
        void gitDiff() {
            assertThat(classifier.classifyForTimeout("git diff --stat").getRecommendedTimeoutMs())
                .isEqualTo(60_000L);
        }

        @Test
        void gitLog() {
            assertThat(classifier.classifyForTimeout("git log --oneline -20").getRecommendedTimeoutMs())
                .isEqualTo(60_000L);
        }
    }

    @Nested
    @DisplayName("服务启动 → 120s")
    class ServerStartCommands {

        @Test
        void npmStart() {
            assertThat(classifier.classifyForTimeout("npm start").getRecommendedTimeoutMs())
                .isEqualTo(120_000L);
        }

        @Test
        void npmRunDev() {
            assertThat(classifier.classifyForTimeout("npm run dev").getRecommendedTimeoutMs())
                .isEqualTo(120_000L);
        }

        @Test
        void javaJar() {
            assertThat(classifier.classifyForTimeout("java -jar app.jar").getRecommendedTimeoutMs())
                .isEqualTo(120_000L);
        }

        @Test
        void mvnwSpringBoot() {
            assertThat(classifier.classifyForTimeout("./mvnw spring-boot:run").getRecommendedTimeoutMs())
                .isEqualTo(120_000L);
        }
    }

    @Nested
    @DisplayName("只读命令 → 30s")
    class ReadOnlyCommands {

        @Test
        void catCommand() {
            assertThat(classifier.classifyForTimeout("cat /etc/hosts").getRecommendedTimeoutMs())
                .isEqualTo(30_000L);
        }

        @Test
        void lsCommand() {
            assertThat(classifier.classifyForTimeout("ls -la").getRecommendedTimeoutMs())
                .isEqualTo(30_000L);
        }

        @Test
        void headCommand() {
            assertThat(classifier.classifyForTimeout("head -50 file.txt").getRecommendedTimeoutMs())
                .isEqualTo(30_000L);
        }
    }

    @Nested
    @DisplayName("搜索命令 → 60s")
    class SearchCommands {

        @Test
        void findCommand() {
            assertThat(classifier.classifyForTimeout("find . -name '*.java'").getRecommendedTimeoutMs())
                .isEqualTo(60_000L);
        }

        @Test
        void rgCommand() {
            assertThat(classifier.classifyForTimeout("rg pattern src/").getRecommendedTimeoutMs())
                .isEqualTo(60_000L);
        }

        @Test
        void grepCommand() {
            assertThat(classifier.classifyForTimeout("grep -r foo .").getRecommendedTimeoutMs())
                .isEqualTo(60_000L);
        }
    }

    @Nested
    @DisplayName("边界情况")
    class EdgeCases {

        @Test
        void nullCommand() {
            assertThat(classifier.classifyForTimeout(null).getRecommendedTimeoutMs())
                .isEqualTo(120_000L);
        }

        @Test
        void emptyCommand() {
            assertThat(classifier.classifyForTimeout("").getRecommendedTimeoutMs())
                .isEqualTo(120_000L);
        }

        @Test
        void blankCommand() {
            assertThat(classifier.classifyForTimeout("   ").getRecommendedTimeoutMs())
                .isEqualTo(120_000L);
        }

        @Test
        void unknownCommand() {
            assertThat(classifier.classifyForTimeout("some-random-tool --flag").getRecommendedTimeoutMs())
                .isEqualTo(120_000L);
        }
    }

    @Nested
    @DisplayName("CommandCategory 枚举向后兼容")
    class BackwardCompatibility {

        @Test
        void displayLabelPreserved() {
            assertThat(CommandCategory.READ_ONLY.getDisplayLabel()).isEqualTo("read");
            assertThat(CommandCategory.SEARCH.getDisplayLabel()).isEqualTo("search");
            assertThat(CommandCategory.MODIFICATION.getDisplayLabel()).isEqualTo("write");
            assertThat(CommandCategory.SYSTEM_INFO.getDisplayLabel()).isEqualTo("info");
            assertThat(CommandCategory.UNKNOWN.getDisplayLabel()).isEqualTo("command");
        }

        @Test
        void classifyForUIStillWorks() {
            assertThat(classifier.classifyForUI("grep -r foo .")).isEqualTo(CommandCategory.SEARCH);
            assertThat(classifier.classifyForUI("cat file.txt")).isEqualTo(CommandCategory.READ_ONLY);
            assertThat(classifier.classifyForUI("rm -f temp")).isEqualTo(CommandCategory.MODIFICATION);
            assertThat(classifier.classifyForUI("uname -a")).isEqualTo(CommandCategory.SYSTEM_INFO);
        }
    }
}
