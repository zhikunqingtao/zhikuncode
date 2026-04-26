package com.aicodeassistant.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static com.aicodeassistant.security.SensitivePathRegistry.OperationType.*;
import static com.aicodeassistant.security.SensitivePathRegistry.ProtectionLevel.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SensitivePathRegistry 单元测试。
 * 覆盖四级保护体系 + 命令路径提取。
 */
@DisplayName("SensitivePathRegistry Tests")
class SensitivePathRegistryTest {

    private SensitivePathRegistry registry;
    private Path cwd;

    @BeforeEach
    void setUp() {
        SecurityAuditLogger auditLogger = new SecurityAuditLogger();
        registry = new SensitivePathRegistry(auditLogger);
        cwd = Path.of(System.getProperty("user.dir"));
    }

    // ===== FORBIDDEN 路径测试 =====

    @Nested
    @DisplayName("FORBIDDEN paths")
    class ForbiddenPathTests {

        @Test
        @DisplayName("~/.ssh/id_rsa should be blocked for READ")
        void denyAll_sshPrivateKeyRead() {
            var result = registry.checkPath("~/.ssh/id_rsa", READ, cwd);
            assertThat(result.blocked()).isTrue();
        }

        @Test
        @DisplayName("~/.ssh/id_rsa should be blocked for WRITE")
        void denyAll_sshPrivateKeyWrite() {
            var result = registry.checkPath("~/.ssh/id_rsa", WRITE, cwd);
            assertThat(result.blocked()).isTrue();
        }

        @Test
        @DisplayName("~/.ssh/id_ed25519 should be blocked")
        void denyAll_sshEd25519() {
            assertThat(registry.checkPath("~/.ssh/id_ed25519", READ, cwd).blocked()).isTrue();
        }

        @Test
        @DisplayName("~/.aws/credentials should be blocked")
        void denyAll_awsCredentials() {
            assertThat(registry.checkPath("~/.aws/credentials", READ, cwd).blocked()).isTrue();
        }

        @Test
        @DisplayName("~/.kube/config should be blocked")
        void denyAll_kubeConfig() {
            assertThat(registry.checkPath("~/.kube/config", READ, cwd).blocked()).isTrue();
        }

        @Test
        @DisplayName("~/.gnupg/ dir should be blocked")
        void denyAll_gnupgDir() {
            assertThat(registry.checkPath("~/.gnupg/pubring.kbx", READ, cwd).blocked()).isTrue();
        }

        @Test
        @DisplayName("~/.npmrc should be blocked")
        void denyAll_npmrc() {
            assertThat(registry.checkPath("~/.npmrc", READ, cwd).blocked()).isTrue();
        }

        @Test
        @DisplayName("~/.pgpass should be blocked")
        void denyAll_pgpass() {
            assertThat(registry.checkPath("~/.pgpass", READ, cwd).blocked()).isTrue();
        }

        @Test
        @DisplayName("~/.git-credentials should be blocked")
        void denyAll_gitCredentials() {
            assertThat(registry.checkPath("~/.git-credentials", READ, cwd).blocked()).isTrue();
        }

        @Test
        @DisplayName("~/.docker/config.json should be blocked")
        void denyAll_dockerConfig() {
            assertThat(registry.checkPath("~/.docker/config.json", READ, cwd).blocked()).isTrue();
        }
    }

    // ===== 系统配置（读审计，写禁止）=====

    @Nested
    @DisplayName("System config paths")
    class SystemConfigTests {

        @Test
        @DisplayName("/etc/shadow WRITE should be blocked")
        void denyWrite_etcShadow() {
            assertThat(registry.checkPath("/etc/shadow", WRITE, cwd).blocked()).isTrue();
        }

        @Test
        @DisplayName("/etc/shadow READ should be audit level")
        void auditRead_etcShadow() {
            var result = registry.checkPath("/etc/shadow", READ, cwd);
            assertThat(result.level()).isEqualTo(AUDIT);
        }

        @Test
        @DisplayName("/etc/passwd WRITE should be blocked")
        void denyWrite_etcPasswd() {
            assertThat(registry.checkPath("/etc/passwd", WRITE, cwd).blocked()).isTrue();
        }
    }

    // ===== PROTECTED 路径测试 =====

    @Nested
    @DisplayName("PROTECTED paths")
    class ProtectedPathTests {

        @Test
        @DisplayName("~/.bashrc WRITE should need confirmation")
        void askWrite_bashrc() {
            assertThat(registry.checkPath("~/.bashrc", WRITE, cwd).needsConfirmation()).isTrue();
        }

        @Test
        @DisplayName("~/.bashrc READ should be allowed")
        void allowRead_bashrc() {
            var result = registry.checkPath("~/.bashrc", READ, cwd);
            assertThat(result.blocked()).isFalse();
            assertThat(result.needsConfirmation()).isFalse();
        }

        @Test
        @DisplayName("~/.zshrc WRITE should need confirmation")
        void askWrite_zshrc() {
            assertThat(registry.checkPath("~/.zshrc", WRITE, cwd).needsConfirmation()).isTrue();
        }

        @Test
        @DisplayName(".env WRITE should need confirmation")
        void askWrite_envFile() {
            assertThat(registry.checkPath(".env", WRITE, cwd).needsConfirmation()).isTrue();
        }

        @Test
        @DisplayName(".env.production WRITE should need confirmation")
        void askWrite_envProd() {
            assertThat(registry.checkPath(".env.production", WRITE, cwd).needsConfirmation()).isTrue();
        }
    }

    // ===== ALLOWED 路径测试 =====

    @Nested
    @DisplayName("ALLOWED paths")
    class AllowedPathTests {

        @Test
        @DisplayName("src/main/App.java should be allowed for WRITE")
        void allowed_normalProjectFile() {
            var result = registry.checkPath("src/main/App.java", WRITE, cwd);
            assertThat(result.blocked()).isFalse();
            assertThat(result.needsConfirmation()).isFalse();
        }

        @Test
        @DisplayName("README.md should be allowed")
        void allowed_readme() {
            var result = registry.checkPath("README.md", WRITE, cwd);
            assertThat(result.blocked()).isFalse();
        }

        @Test
        @DisplayName("/tmp/test.txt should be allowed")
        void allowed_tmpFile() {
            var result = registry.checkPath("/tmp/test.txt", WRITE, cwd);
            assertThat(result.blocked()).isFalse();
        }

        @Test
        @DisplayName("null path should be allowed")
        void allowed_nullPath() {
            assertThat(registry.checkPath(null, READ, cwd).level()).isEqualTo(ALLOWED);
        }

        @Test
        @DisplayName("empty path should be allowed")
        void allowed_emptyPath() {
            assertThat(registry.checkPath("", READ, cwd).level()).isEqualTo(ALLOWED);
        }
    }

    // ===== 命令路径提取测试 =====

    @Nested
    @DisplayName("Command path extraction")
    class CommandPathExtractionTests {

        @Test
        @DisplayName("cat ~/.ssh/id_rsa should detect sensitive path")
        void command_catSshKey() {
            String result = registry.checkCommandPaths("cat ~/.ssh/id_rsa");
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("cat src/main/App.java should be safe")
        void command_catNormalFile() {
            String result = registry.checkCommandPaths("cat src/main/App.java");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("null command should be safe")
        void command_nullCommand() {
            assertThat(registry.checkCommandPaths(null)).isNull();
        }
    }
}
