package com.aicodeassistant.tool.bash;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BashCommandClassifier 独立单元测试。
 *
 * 覆盖：三层只读验证、管道/复合命令拆分、安全加固（变量展开/花括号检测）、
 * Git/GH/Docker/外部命令分类、classify() 分类逻辑、边界情况。
 */
class BashCommandClassifierTest {

    private BashCommandClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new BashCommandClassifier();
    }

    // ══════════════════════════════════════════════════════════════
    // isReadOnlyCommand — 三层只读验证
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("层1: 纯只读命令 (READONLY_COMMANDS)")
    class Layer1ReadOnlyCommands {

        @Test
        @DisplayName("系统信息命令识别为只读")
        void systemInfoCommandsAreReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("cal"));
            assertTrue(classifier.isReadOnlyCommand("uptime"));
            assertTrue(classifier.isReadOnlyCommand("id"));
            assertTrue(classifier.isReadOnlyCommand("uname"));
            assertTrue(classifier.isReadOnlyCommand("free"));
            assertTrue(classifier.isReadOnlyCommand("df"));
            assertTrue(classifier.isReadOnlyCommand("du"));
            assertTrue(classifier.isReadOnlyCommand("nproc"));
        }

        @Test
        @DisplayName("文件查看命令识别为只读")
        void fileViewCommandsAreReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("cat"));
            assertTrue(classifier.isReadOnlyCommand("head"));
            assertTrue(classifier.isReadOnlyCommand("tail"));
            assertTrue(classifier.isReadOnlyCommand("wc"));
            assertTrue(classifier.isReadOnlyCommand("stat"));
            assertTrue(classifier.isReadOnlyCommand("strings"));
            assertTrue(classifier.isReadOnlyCommand("nl"));
            assertTrue(classifier.isReadOnlyCommand("readlink"));
        }

        @Test
        @DisplayName("文本处理命令识别为只读")
        void textProcessingCommandsAreReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("cut"));
            assertTrue(classifier.isReadOnlyCommand("paste"));
            assertTrue(classifier.isReadOnlyCommand("tr"));
            assertTrue(classifier.isReadOnlyCommand("column"));
            assertTrue(classifier.isReadOnlyCommand("tac"));
            assertTrue(classifier.isReadOnlyCommand("rev"));
            assertTrue(classifier.isReadOnlyCommand("diff"));
        }

        @Test
        @DisplayName("路径操作命令识别为只读")
        void pathCommandsAreReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("basename"));
            assertTrue(classifier.isReadOnlyCommand("dirname"));
            assertTrue(classifier.isReadOnlyCommand("realpath"));
        }

        @Test
        @DisplayName("安全工具命令识别为只读")
        void securityToolsAreReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("md5sum"));
            assertTrue(classifier.isReadOnlyCommand("sha1sum"));
            assertTrue(classifier.isReadOnlyCommand("openssl"));
            assertTrue(classifier.isReadOnlyCommand("xxd"));
        }

        @Test
        @DisplayName("带参数的纯只读命令仍为只读")
        void readOnlyCommandsWithArgsStillReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("cat file.txt"));
            assertTrue(classifier.isReadOnlyCommand("head -n 10 file.txt"));
            assertTrue(classifier.isReadOnlyCommand("wc -l file.txt"));
            assertTrue(classifier.isReadOnlyCommand("diff file1 file2"));
        }
    }

    @Nested
    @DisplayName("层2: 正则匹配只读 (READONLY_REGEXES)")
    class Layer2RegexReadOnly {

        @Test
        @DisplayName("echo 命令识别为只读")
        void echoIsReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("echo hello"));
            assertTrue(classifier.isReadOnlyCommand("echo 'some text'"));
        }

        @Test
        @DisplayName("pwd/whoami 识别为只读")
        void pwdWhoamiAreReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("pwd"));
            assertTrue(classifier.isReadOnlyCommand("whoami"));
        }

        @Test
        @DisplayName("版本查询命令识别为只读")
        void versionCommandsAreReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("node -v"));
            assertTrue(classifier.isReadOnlyCommand("node --version"));
            assertTrue(classifier.isReadOnlyCommand("python3 --version"));
            assertTrue(classifier.isReadOnlyCommand("java --version"));
            assertTrue(classifier.isReadOnlyCommand("mvn --version"));
            assertTrue(classifier.isReadOnlyCommand("gradle --version"));
        }

        @Test
        @DisplayName("uniq 命令识别为只读")
        void uniqIsReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("uniq"));
            assertTrue(classifier.isReadOnlyCommand("uniq -c"));
        }
    }

    @Nested
    @DisplayName("层3: flag级别白名单验证 (COMMAND_ALLOWLIST)")
    class Layer3FlagValidation {

        @Test
        @DisplayName("sort 带安全 flag 为只读")
        void sortWithSafeFlagsIsReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("sort -r"));
            assertTrue(classifier.isReadOnlyCommand("sort -n"));
            assertTrue(classifier.isReadOnlyCommand("sort -u"));
            assertTrue(classifier.isReadOnlyCommand("sort -k 2"));
        }

        @Test
        @DisplayName("grep 带安全 flag 为只读")
        void grepWithSafeFlagsIsReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("grep -r pattern ."));
            assertTrue(classifier.isReadOnlyCommand("grep -i pattern file.txt"));
            assertTrue(classifier.isReadOnlyCommand("grep -n pattern file.txt"));
            assertTrue(classifier.isReadOnlyCommand("grep -A 5 pattern file.txt"));
        }

        @Test
        @DisplayName("grep 组合短 flag (-rn) 为只读")
        void grepCombinedShortFlagsIsReadOnly() {
            // -rn 是 -r + -n 的组合，两者均为 NONE 类型
            assertTrue(classifier.isReadOnlyCommand("grep -rn pattern ."),
                    "grep -rn should be read-only (combined -r + -n)");
        }

        @Test
        @DisplayName("grep --include=glob 为只读")
        void grepIncludeGlobIsReadOnly() {
            // --include=*.java 中的 * 不应触发 containsUnquotedExpansion，因为在引号内
            assertTrue(classifier.isReadOnlyCommand("grep --include='*.java' pattern ."));
        }

        @Test
        @DisplayName("rg 带安全 flag 为只读")
        void rgWithSafeFlagsIsReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("rg -i pattern"));
            assertTrue(classifier.isReadOnlyCommand("rg --hidden pattern"));
            assertTrue(classifier.isReadOnlyCommand("rg -t java pattern"));
            assertTrue(classifier.isReadOnlyCommand("rg -C 3 pattern"));
        }

        @Test
        @DisplayName("grep/rg 附着数字参数 (-A20) 为只读")
        void grepAttachedNumberArgIsReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("grep -A20 pattern file"));
            assertTrue(classifier.isReadOnlyCommand("grep -B5 pattern file"));
            assertTrue(classifier.isReadOnlyCommand("rg -C10 pattern"));
        }

        @Test
        @DisplayName("tree 带安全 flag 为只读")
        void treeWithSafeFlagsIsReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("tree -L 3"));
            assertTrue(classifier.isReadOnlyCommand("tree -d"));
            assertTrue(classifier.isReadOnlyCommand("tree -a"));
        }

        @Test
        @DisplayName("ps 带安全 flag 为只读")
        void psWithSafeFlagsIsReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("ps -ef"));
            assertTrue(classifier.isReadOnlyCommand("ps -A"));
        }

        @Test
        @DisplayName("sed -n (只读模式) 为只读")
        void sedReadOnlyModeIsReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("sed -n '1,10p'"));
        }

        @Test
        @DisplayName("未知 flag 导致拒绝")
        void unknownFlagsCauseRejection() {
            assertFalse(classifier.isReadOnlyCommand("sort --dangerous-flag"));
            assertFalse(classifier.isReadOnlyCommand("grep --unknown-flag pattern"));
        }

        @Test
        @DisplayName("xargs 带安全目标命令为只读")
        void xargsWithSafeTargetIsReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("xargs echo"));
            assertTrue(classifier.isReadOnlyCommand("xargs grep pattern"));
            assertTrue(classifier.isReadOnlyCommand("xargs -I {} echo {}"));
        }

        @Test
        @DisplayName("xargs 带不安全目标命令被拒绝")
        void xargsWithUnsafeTargetIsRejected() {
            assertFalse(classifier.isReadOnlyCommand("xargs rm"));
            assertFalse(classifier.isReadOnlyCommand("xargs mv"));
        }

        @Test
        @DisplayName("tput 安全能力为只读，危险能力被拒绝")
        void tputSafeAndDangerousCapabilities() {
            assertTrue(classifier.isReadOnlyCommand("tput cols"));
            assertTrue(classifier.isReadOnlyCommand("tput lines"));
            assertFalse(classifier.isReadOnlyCommand("tput init"));
            assertFalse(classifier.isReadOnlyCommand("tput reset"));
        }
    }

    @Nested
    @DisplayName("find 命令特殊处理")
    class FindCommandHandling {

        @Test
        @DisplayName("安全的 find 命令为只读")
        void safeFindIsReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("find . -name '*.java'"));
            assertTrue(classifier.isReadOnlyCommand("find /tmp -type f"));
            assertTrue(classifier.isReadOnlyCommand("find . -maxdepth 2 -name '*.txt'"));
        }

        @Test
        @DisplayName("带危险参数的 find 被拒绝")
        void dangerousFindIsRejected() {
            assertFalse(classifier.isReadOnlyCommand("find . -delete"));
            assertFalse(classifier.isReadOnlyCommand("find . -exec rm {} ;"));
            assertFalse(classifier.isReadOnlyCommand("find . -execdir mv {} /tmp ;"));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 管道和复合命令
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("管道命令处理")
    class PipeCommandHandling {

        @Test
        @DisplayName("全只读管道链为只读")
        void allReadOnlyPipeIsReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("cat file.txt | grep pattern"));
            assertTrue(classifier.isReadOnlyCommand("cat file | sort | head"));
            assertTrue(classifier.isReadOnlyCommand("echo hello | wc -l"));
        }

        @Test
        @DisplayName("管道中含危险命令被拒绝")
        void pipeWithDangerousCommandIsRejected() {
            assertFalse(classifier.isReadOnlyCommand("cat file | rm something"));
        }
    }

    @Nested
    @DisplayName("复合命令处理 (&&, ||, ;)")
    class CompoundCommandHandling {

        @Test
        @DisplayName("全只读复合命令为只读")
        void allReadOnlyCompoundIsReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("pwd && whoami"));
            assertTrue(classifier.isReadOnlyCommand("echo hello; pwd"));
        }

        @Test
        @DisplayName("复合命令含危险命令被拒绝")
        void compoundWithDangerousIsRejected() {
            assertFalse(classifier.isReadOnlyCommand("pwd && rm file"));
            assertFalse(classifier.isReadOnlyCommand("echo hello; rm -rf /"));
            assertFalse(classifier.isReadOnlyCommand("cat file || rm file"));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 安全加固特性
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("安全加固: 变量展开/花括号检测")
    class SecurityHardening {

        @Test
        @DisplayName("containsUnquotedExpansion 检测未引用 $ 变量")
        void detectsUnquotedVariableExpansion() {
            assertTrue(classifier.containsUnquotedExpansion("echo $HOME"));
            assertTrue(classifier.containsUnquotedExpansion("cat ${file}"));
            assertTrue(classifier.containsUnquotedExpansion("echo $(whoami)"));
        }

        @Test
        @DisplayName("单引号内 $ 不触发检测")
        void singleQuotedVariableNotDetected() {
            assertFalse(classifier.containsUnquotedExpansion("echo '$HOME'"));
            assertFalse(classifier.containsUnquotedExpansion("echo '${file}'"));
        }

        @Test
        @DisplayName("检测未引用 glob 通配符")
        void detectsUnquotedGlob() {
            assertTrue(classifier.containsUnquotedExpansion("ls *.txt"));
            assertTrue(classifier.containsUnquotedExpansion("cat file?.txt"));
        }

        @Test
        @DisplayName("检测花括号展开")
        void detectsBraceExpansion() {
            assertTrue(classifier.containsUnquotedExpansion("echo {a,b,c}"));
        }

        @Test
        @DisplayName("转义字符不触发检测")
        void escapedCharactersNotDetected() {
            assertFalse(classifier.containsUnquotedExpansion("echo \\$HOME"));
        }

        @Test
        @DisplayName("isReadOnlyCommand 拒绝含未引用展开的命令")
        void readOnlyRejectsUnquotedExpansion() {
            assertFalse(classifier.isReadOnlyCommand("sort $HOME/file"));
            assertFalse(classifier.isReadOnlyCommand("grep pattern ${file}"));
        }

        @Test
        @DisplayName("flag 中含 $ 被拒绝")
        void dollarInFlagTokenRejected() {
            assertFalse(classifier.isReadOnlyCommand("sort $FILE"));
            assertFalse(classifier.isReadOnlyCommand("grep -r $PATTERN ."));
        }

        @Test
        @DisplayName("flag 中含花括号展开被拒绝")
        void braceExpansionInFlagTokenRejected() {
            assertFalse(classifier.isReadOnlyCommand("sort {a,b}"));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Git 命令分类
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Git 命令分类")
    class GitCommandClassification {

        @Test
        @DisplayName("git 只读子命令为只读")
        void gitReadOnlySubcommandsAreReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("git status"));
            assertTrue(classifier.isReadOnlyCommand("git log"));
            assertTrue(classifier.isReadOnlyCommand("git diff"));
            assertTrue(classifier.isReadOnlyCommand("git show"));
            assertTrue(classifier.isReadOnlyCommand("git branch"));
            assertTrue(classifier.isReadOnlyCommand("git remote -v"));
            assertTrue(classifier.isReadOnlyCommand("git blame file.java"));
            assertTrue(classifier.isReadOnlyCommand("git ls-files"));
            assertTrue(classifier.isReadOnlyCommand("git rev-parse HEAD"));
            assertTrue(classifier.isReadOnlyCommand("git config --get user.name"));
        }

        @Test
        @DisplayName("git log 带安全 flag 为只读")
        void gitLogWithSafeFlagsIsReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("git log --oneline"));
            assertTrue(classifier.isReadOnlyCommand("git log -n 10"));
            assertTrue(classifier.isReadOnlyCommand("git log --graph --stat"));
            assertTrue(classifier.isReadOnlyCommand("git log --format=oneline"));
        }

        @Test
        @DisplayName("git diff 带安全 flag 为只读")
        void gitDiffWithSafeFlagsIsReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("git diff --cached"));
            assertTrue(classifier.isReadOnlyCommand("git diff --stat"));
            assertTrue(classifier.isReadOnlyCommand("git diff --name-only"));
        }

        @Test
        @DisplayName("git 数字简写 (-n) 为只读")
        void gitNumberShortcutIsReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("git log -5"));
            assertTrue(classifier.isReadOnlyCommand("git log -20"));
        }

        @Test
        @DisplayName("git branch 不带参数为只读，带参数创建分支被拒绝")
        void gitBranchReadOnlyVsCreate() {
            assertTrue(classifier.isReadOnlyCommand("git branch"));
            assertTrue(classifier.isReadOnlyCommand("git branch -a"));
            assertTrue(classifier.isReadOnlyCommand("git branch --list"));
            assertFalse(classifier.isReadOnlyCommand("git branch new-feature"));
        }

        @Test
        @DisplayName("git tag 列表为只读，创建标签被拒绝")
        void gitTagListVsCreate() {
            assertTrue(classifier.isReadOnlyCommand("git tag -l"));
            assertTrue(classifier.isReadOnlyCommand("git tag --list"));
            assertFalse(classifier.isReadOnlyCommand("git tag v1.0"));
        }

        @Test
        @DisplayName("git push/commit/merge 等写操作被拒绝")
        void gitWriteCommandsAreRejected() {
            assertFalse(classifier.isReadOnlyCommand("git push"));
            assertFalse(classifier.isReadOnlyCommand("git commit -m 'msg'"));
            assertFalse(classifier.isReadOnlyCommand("git merge feature"));
            assertFalse(classifier.isReadOnlyCommand("git rebase main"));
            assertFalse(classifier.isReadOnlyCommand("git reset HEAD~1"));
        }

        @Test
        @DisplayName("git -c 注入被 isGitCommandSafe 检测")
        void gitConfigInjectionDetected() {
            assertFalse(classifier.isGitCommandSafe("git -c core.pager=less log"));
            assertFalse(classifier.isGitCommandSafe("git --exec-path=/tmp log"));
            assertFalse(classifier.isGitCommandSafe("git --config-env=X=Y log"));
        }

        @Test
        @DisplayName("git 命令含 $ 被拒绝")
        void gitCommandWithDollarRejected() {
            assertFalse(classifier.isReadOnlyCommand("git log $BRANCH"));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 外部命令前缀
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("外部只读命令前缀")
    class ExternalReadOnlyPrefixes {

        @Test
        @DisplayName("docker/kubectl/npm/yarn/pip 只读前缀为只读")
        void externalReadOnlyPrefixesAreReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("docker ps"));
            assertTrue(classifier.isReadOnlyCommand("docker images"));
            assertTrue(classifier.isReadOnlyCommand("kubectl get pods"));
            assertTrue(classifier.isReadOnlyCommand("kubectl describe pod my-pod"));
            assertTrue(classifier.isReadOnlyCommand("kubectl logs my-pod"));
            assertTrue(classifier.isReadOnlyCommand("npm list"));
            assertTrue(classifier.isReadOnlyCommand("npm info express"));
            assertTrue(classifier.isReadOnlyCommand("npm outdated"));
            assertTrue(classifier.isReadOnlyCommand("npm audit"));
            assertTrue(classifier.isReadOnlyCommand("yarn list"));
            assertTrue(classifier.isReadOnlyCommand("pip list"));
            assertTrue(classifier.isReadOnlyCommand("pip show flask"));
            assertTrue(classifier.isReadOnlyCommand("pip freeze"));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Docker 只读命令 (flag 验证)
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Docker 只读命令 (flag 验证)")
    class DockerReadOnlyCommands {

        @Test
        @DisplayName("docker logs 带安全 flag 为只读")
        void dockerLogsWithSafeFlagsIsReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("docker logs -f container"));
            assertTrue(classifier.isReadOnlyCommand("docker logs --tail 100 container"));
        }

        @Test
        @DisplayName("docker inspect 带安全 flag 为只读")
        void dockerInspectIsReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("docker inspect container"));
            assertTrue(classifier.isReadOnlyCommand("docker inspect --format '{{.State}}' container"));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // GH CLI 只读命令
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GH CLI 只读命令")
    class GhCliReadOnlyCommands {

        @Test
        @DisplayName("gh pr/issue 只读子命令为只读")
        void ghPrIssueReadOnlySubcommands() {
            assertTrue(classifier.isReadOnlyCommand("gh pr list"));
            assertTrue(classifier.isReadOnlyCommand("gh pr view 123"));
            assertTrue(classifier.isReadOnlyCommand("gh pr diff 123"));
            assertTrue(classifier.isReadOnlyCommand("gh pr status"));
            assertTrue(classifier.isReadOnlyCommand("gh issue list"));
            assertTrue(classifier.isReadOnlyCommand("gh issue view 456"));
        }

        @Test
        @DisplayName("gh 含 URL 参数被拒绝 (DNS exfiltration 防护)")
        void ghWithUrlArgIsRejected() {
            assertFalse(classifier.isReadOnlyCommand("gh pr list --repo=http://evil.com/owner/repo"));
            assertFalse(classifier.isReadOnlyCommand("gh pr list --repo=user@host:repo"));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Pyright 只读命令
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Pyright 只读命令")
    class PyrightReadOnlyCommands {

        @Test
        @DisplayName("pyright 带安全 flag 为只读")
        void pyrightWithSafeFlagsIsReadOnly() {
            assertTrue(classifier.isReadOnlyCommand("pyright --version"));
            assertTrue(classifier.isReadOnlyCommand("pyright --outputjson src/"));
            assertTrue(classifier.isReadOnlyCommand("pyright --stats"));
        }

        @Test
        @DisplayName("pyright --watch 被拒绝")
        void pyrightWatchIsRejected() {
            assertFalse(classifier.isReadOnlyCommand("pyright --watch"));
            assertFalse(classifier.isReadOnlyCommand("pyright -w"));
        }

        @Test
        @DisplayName("pyright --createstub 被拒绝")
        void pyrightCreateStubIsRejected() {
            assertFalse(classifier.isReadOnlyCommand("pyright --createstub numpy"));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // classify() 方法测试
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("classify() 分类逻辑")
    class ClassifyMethod {

        @Test
        @DisplayName("搜索命令分类为 isSearch")
        void searchCommandsClassifiedAsSearch() {
            var result = classifier.classify("find . -name '*.java'");
            assertTrue(result.isSearch());
            assertTrue(result.isReadOnly());
        }

        @Test
        @DisplayName("读取命令分类为 isRead")
        void readCommandsClassifiedAsRead() {
            var result = classifier.classify("cat file.txt");
            assertTrue(result.isRead());
            assertTrue(result.isReadOnly());
        }

        @Test
        @DisplayName("列表命令分类为 isList")
        void listCommandsClassifiedAsList() {
            var result = classifier.classify("ls -la");
            assertTrue(result.isList());
            assertTrue(result.isReadOnly());

            var treeResult = classifier.classify("tree");
            assertTrue(treeResult.isList());
        }

        @Test
        @DisplayName("危险命令不为只读")
        void dangerousCommandsNotReadOnly() {
            var rmResult = classifier.classify("rm file.txt");
            assertFalse(rmResult.isReadOnly());

            var mvResult = classifier.classify("mv a b");
            assertFalse(mvResult.isReadOnly());
        }

        @Test
        @DisplayName("管道全只读子命令 → 整体只读")
        void pipeAllReadOnlySubcommands() {
            var result = classifier.classify("cat file | grep pattern | sort");
            assertTrue(result.isReadOnly());
        }

        @Test
        @DisplayName("管道含危险命令 → 非只读")
        void pipeWithDangerousCommand() {
            var result = classifier.classify("cat file | rm something");
            assertFalse(result.isReadOnly());
        }

        @Test
        @DisplayName("复合命令 (&&) 全只读")
        void compoundCommandAllReadOnly() {
            var result = classifier.classify("grep pattern file && wc -l file");
            assertTrue(result.isReadOnly());
        }

        @Test
        @DisplayName("复合命令 (;) 含危险")
        void compoundCommandWithDangerous() {
            var result = classifier.classify("cat file; rm file");
            assertFalse(result.isReadOnly());
        }

        @Test
        @DisplayName("中性命令 (echo/printf) 单独使用返回非只读分类")
        void neutralCommandsAloneNotClassified() {
            // echo/printf 是 NEUTRAL_CMDS，单独使用无 non-neutral → (false,false,false)
            var result = classifier.classify("echo hello");
            assertFalse(result.isSearch());
            assertFalse(result.isRead());
            assertFalse(result.isList());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // isSearchOrReadCommand
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("isSearchOrReadCommand")
    class IsSearchOrReadCommand {

        @Test
        @DisplayName("搜索/读取/列表命令返回 true")
        void searchReadListCommandsReturnTrue() {
            assertTrue(classifier.isSearchOrReadCommand("grep"));
            assertTrue(classifier.isSearchOrReadCommand("find"));
            assertTrue(classifier.isSearchOrReadCommand("cat"));
            assertTrue(classifier.isSearchOrReadCommand("ls"));
            assertTrue(classifier.isSearchOrReadCommand("tree"));
            assertTrue(classifier.isSearchOrReadCommand("sort"));
        }

        @Test
        @DisplayName("危险命令返回 false")
        void dangerousCommandsReturnFalse() {
            assertFalse(classifier.isSearchOrReadCommand("rm"));
            assertFalse(classifier.isSearchOrReadCommand("mv"));
            assertFalse(classifier.isSearchOrReadCommand("chmod"));
        }

        @Test
        @DisplayName("空/null 返回 false")
        void emptyOrNullReturnsFalse() {
            assertFalse(classifier.isSearchOrReadCommand(null));
            assertFalse(classifier.isSearchOrReadCommand(""));
            assertFalse(classifier.isSearchOrReadCommand("   "));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // isCompoundCommandReadOnly
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("isCompoundCommandReadOnly")
    class IsCompoundCommandReadOnly {

        @Test
        @DisplayName("全只读复合命令返回 true")
        void allReadOnlyCompoundReturnsTrue() {
            assertTrue(classifier.isCompoundCommandReadOnly("cat file && head file"));
        }

        @Test
        @DisplayName("含 rm/mv 的复合命令返回 false")
        void compoundWithDangerousReturnsFalse() {
            assertFalse(classifier.isCompoundCommandReadOnly("cat file; rm file"));
            assertFalse(classifier.isCompoundCommandReadOnly("ls && mv a b"));
        }

        @Test
        @DisplayName("重定向到非 /dev/ 返回 false")
        void redirectToNonDevReturnsFalse() {
            assertFalse(classifier.isCompoundCommandReadOnly("echo hello > output.txt"));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 边界情况
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("边界情况")
    class EdgeCases {

        @Test
        @DisplayName("null 输入不崩溃")
        void nullInputDoesNotCrash() {
            assertFalse(classifier.isReadOnlyCommand(null));
            var result = classifier.classify(null);
            assertFalse(result.isReadOnly());
        }

        @Test
        @DisplayName("空字符串输入")
        void emptyStringInput() {
            assertFalse(classifier.isReadOnlyCommand(""));
            var result = classifier.classify("");
            assertFalse(result.isReadOnly());
        }

        @Test
        @DisplayName("仅空白字符输入")
        void whitespaceOnlyInput() {
            assertFalse(classifier.isReadOnlyCommand("   "));
            assertFalse(classifier.isReadOnlyCommand("\t\n"));
            var result = classifier.classify("   ");
            assertFalse(result.isReadOnly());
        }

        @Test
        @DisplayName("未知命令被拒绝")
        void unknownCommandsRejected() {
            assertFalse(classifier.isReadOnlyCommand("someRandomCommand"));
            assertFalse(classifier.isReadOnlyCommand("customtool --flag"));
        }

        @Test
        @DisplayName("env/printenv 不在只读命令中 (安全移除)")
        void envPrintenvNotReadOnly() {
            assertFalse(classifier.isReadOnlyCommand("env"));
            assertFalse(classifier.isReadOnlyCommand("printenv"));
        }

        @Test
        @DisplayName("isGitCommandSafe 非 git 命令返回 true")
        void nonGitCommandIsSafe() {
            assertTrue(classifier.isGitCommandSafe("ls -la"));
            assertTrue(classifier.isGitCommandSafe("cat file"));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // --flag=value 格式验证
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("--flag=value 内联值解析")
    class FlagEqualsValueParsing {

        @Test
        @DisplayName("--flag=value 格式被正确解析")
        void flagEqualsValueParsedCorrectly() {
            assertTrue(classifier.isReadOnlyCommand("grep --color=auto pattern file"));
            assertTrue(classifier.isReadOnlyCommand("rg --color=always pattern"));
        }

        @Test
        @DisplayName("NUMBER 类型的 --flag=非数字 被拒绝")
        void flagEqualsNonNumericRejectedForNumber() {
            assertFalse(classifier.isReadOnlyCommand("rg --max-count=abc pattern"));
        }
    }
}
