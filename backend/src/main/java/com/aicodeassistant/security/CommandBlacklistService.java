package com.aicodeassistant.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 统一命令黑名单服务 — 安全硬拦截层 (Layer 0)。
 * <p>
 * 三级拦截体系：
 * <ul>
 *   <li>{@link BlockLevel#ABSOLUTE_DENY} — 绝对禁止，任何模式下不可执行</li>
 *   <li>{@link BlockLevel#HIGH_RISK_ASK} — 高危命令，需用户确认（bypass 免疫）</li>
 *   <li>{@link BlockLevel#AUDIT_LOG} — 审计记录，不阻止执行</li>
 * </ul>
 * <p>
 * 内置规则硬编码在 Java 代码中，不可通过配置修改。
 * 自定义规则通过 {@code security-blacklist.json} 追加。
 */
@Service
@ConditionalOnProperty(
        name = "security.enhanced-blacklist.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class CommandBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(CommandBlacklistService.class);

    /**
     * 拦截级别。
     */
    public enum BlockLevel {
        ABSOLUTE_DENY,
        HIGH_RISK_ASK,
        AUDIT_LOG,
        ALLOWED
    }

    /**
     * 黑名单检查结果。
     */
    public record BlockResult(BlockLevel level, String rule, String reason) {
        public static BlockResult allowed() {
            return new BlockResult(BlockLevel.ALLOWED, null, null);
        }
        public static BlockResult deny(String rule, String reason) {
            return new BlockResult(BlockLevel.ABSOLUTE_DENY, rule, reason);
        }
        public static BlockResult ask(String rule, String reason) {
            return new BlockResult(BlockLevel.HIGH_RISK_ASK, rule, reason);
        }
        public static BlockResult audit(String rule, String reason) {
            return new BlockResult(BlockLevel.AUDIT_LOG, rule, reason);
        }
    }

    private record RuleEntry(Pattern pattern, String description) {}

    // ===== 内置规则（硬编码，不可通过配置修改） =====

    private static final List<RuleEntry> BUILTIN_ABSOLUTE_DENY = List.of(
            // 递归删除系统目录
            new RuleEntry(
                    Pattern.compile("rm\\s+(?:-[rRf]+\\s+){0,5}(/|/\\*|~|\\$HOME)(?:\\s|$)"),
                    "Recursive deletion of system/home directory"),
            // 磁盘格式化
            new RuleEntry(
                    Pattern.compile("\\bmkfs\\."),
                    "Disk formatting"),
            // 块设备直写
            new RuleEntry(
                    Pattern.compile("\\bdd\\s+[^\\n]{0,200}of=/dev/"),
                    "Block device direct write"),
            // 块设备重定向
            new RuleEntry(
                    Pattern.compile(">\\s*/dev/sd[a-z]"),
                    "Block device redirection"),
            // Fork 炸弹
            new RuleEntry(
                    Pattern.compile(":\\(\\)\\{\\s*:\\|:&\\s*\\};:"),
                    "Fork bomb"),
            // 远程代码执行 (curl | sh)
            new RuleEntry(
                    Pattern.compile("curl\\s+[^|]*\\|\\s*(ba)?sh"),
                    "Remote code execution via curl pipe"),
            // 远程代码执行 (wget | sh)
            new RuleEntry(
                    Pattern.compile("wget\\s+[^|]*\\|\\s*(ba)?sh"),
                    "Remote code execution via wget pipe"),
            // 远程代码执行 (bash -c "$(curl ...)")
            new RuleEntry(
                    Pattern.compile("bash\\s+-c\\s+[\"']?\\$\\(curl"),
                    "Remote code execution via bash -c curl"),
            // 全局权限修改
            new RuleEntry(
                    Pattern.compile("chmod\\s+(?:-R\\s+)?777\\s+/(?:\\s|$)"),
                    "Global permission destruction"),
            // 根目录 chown
            new RuleEntry(
                    Pattern.compile("chown\\s+-R\\s+.*\\s+/\\s*$"),
                    "Root directory ownership change"),
            // 系统关机重启
            new RuleEntry(
                    Pattern.compile("^\\s*(reboot|shutdown|halt|poweroff|init\\s+[06])\\b"),
                    "System shutdown/reboot"),
            // 磁盘擦除
            new RuleEntry(
                    Pattern.compile("\\b(shred|wipefs)\\s+"),
                    "Irreversible data erasure")
    );

    private static final List<RuleEntry> BUILTIN_HIGH_RISK_ASK = List.of(
            // 递归/强制删除
            new RuleEntry(
                    Pattern.compile("rm\\s+(?:-[rRf]+\\s+){1,5}"),
                    "Recursive/forced deletion"),
            // 危险权限
            new RuleEntry(
                    Pattern.compile("chmod\\s+(?:-R\\s+)?777\\b"),
                    "Dangerous permission change"),
            // Git 强推
            new RuleEntry(
                    Pattern.compile("\\bgit\\s+push\\s+.*--force"),
                    "Git force push"),
            // Git 硬重置
            new RuleEntry(
                    Pattern.compile("\\bgit\\s+(reset|clean)\\s+--hard"),
                    "Git hard reset/clean"),
            // SQL DROP
            new RuleEntry(
                    Pattern.compile("(?i)DROP\\s+(TABLE|DATABASE)"),
                    "SQL DROP operation"),
            // SQL TRUNCATE
            new RuleEntry(
                    Pattern.compile("(?i)TRUNCATE\\s+TABLE"),
                    "SQL TRUNCATE operation"),
            // 强杀进程
            new RuleEntry(
                    Pattern.compile("\\bkill\\s+-9\\s+"),
                    "Force kill process"),
            // 批量杀进程
            new RuleEntry(
                    Pattern.compile("\\bkillall\\s+"),
                    "Batch kill processes"),
            // 网络监听
            new RuleEntry(
                    Pattern.compile("\\bnc\\s+-[lp]"),
                    "Network listening"),
            // Docker 破坏
            new RuleEntry(
                    Pattern.compile("\\bdocker\\s+(rm|rmi|system\\s+prune)"),
                    "Docker destructive operation"),
            // NPM 发布
            new RuleEntry(
                    Pattern.compile("\\bnpm\\s+(publish|unpublish)"),
                    "NPM publish/unpublish")
    );

    private static final List<RuleEntry> BUILTIN_AUDIT_LOG = List.of(
            // 环境变量泄露
            new RuleEntry(
                    Pattern.compile("^\\s*(env|printenv|set)\\s*$"),
                    "Environment variable disclosure"),
            // 网络请求
            new RuleEntry(
                    Pattern.compile("\\b(curl|wget)\\s+"),
                    "Network request"),
            // SSH 连接
            new RuleEntry(
                    Pattern.compile("\\bssh\\s+"),
                    "SSH connection"),
            // 包安装
            new RuleEntry(
                    Pattern.compile("\\b(npm|pip|brew|apt|apt-get)\\s+install\\b"),
                    "Package installation"),
            // Git 写操作
            new RuleEntry(
                    Pattern.compile("\\bgit\\s+(push|commit|merge)\\b"),
                    "Git write operation")
    );

    // ===== 自定义规则（运行时从 JSON 加载） =====

    private final List<RuleEntry> customDenyPatterns = new ArrayList<>();
    private final List<RuleEntry> customAskPatterns = new ArrayList<>();

    private final ResourceLoader resourceLoader;
    private final SecurityAuditLogger auditLogger;

    @Value("${security.enhanced-blacklist.custom-config:security-blacklist.json}")
    private String customConfigPath;

    @Value("${security.enhanced-blacklist.audit-log-enabled:true}")
    private boolean auditLogEnabled;

    public CommandBlacklistService(ResourceLoader resourceLoader, SecurityAuditLogger auditLogger) {
        this.resourceLoader = resourceLoader;
        this.auditLogger = auditLogger;
    }

    @PostConstruct
    void loadCustomRules() {
        try {
            var resource = resourceLoader.getResource("classpath:" + customConfigPath);
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode root = mapper.readTree(is);

                    loadPatterns(root, "customDenyPatterns", customDenyPatterns);
                    loadPatterns(root, "customAskPatterns", customAskPatterns);

                    log.info("Loaded custom blacklist rules: {} deny, {} ask",
                            customDenyPatterns.size(), customAskPatterns.size());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load custom blacklist config from {}: {}", customConfigPath, e.getMessage());
        }
    }

    private void loadPatterns(JsonNode root, String fieldName, List<RuleEntry> target) {
        JsonNode arr = root.get(fieldName);
        if (arr != null && arr.isArray()) {
            for (JsonNode node : arr) {
                String pattern = node.has("pattern") ? node.get("pattern").asText() : null;
                String desc = node.has("description") ? node.get("description").asText() : "Custom rule";
                if (pattern != null && !pattern.isBlank()) {
                    try {
                        target.add(new RuleEntry(Pattern.compile(pattern), desc));
                    } catch (Exception e) {
                        log.warn("Invalid custom pattern '{}': {}", pattern, e.getMessage());
                    }
                }
            }
        }
    }

    // ===== 核心检查方法 =====

    /**
     * 检查命令是否命中黑名单规则。
     *
     * @param rawCommand 原始命令字符串
     * @return 检查结果
     */
    public BlockResult checkCommand(String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank()) {
            return BlockResult.allowed();
        }

        String command = rawCommand.trim();

        // 剥离常见包装前缀（/bin/rm → rm, command rm → rm）
        String stripped = stripCommandPrefix(command);

        // 1. ABSOLUTE_DENY — 内置规则
        for (RuleEntry rule : BUILTIN_ABSOLUTE_DENY) {
            if (rule.pattern().matcher(stripped).find()) {
                BlockResult result = BlockResult.deny(rule.pattern().pattern(), rule.description());
                if (auditLogEnabled) {
                    auditLogger.logBlocked(rawCommand, result);
                }
                return result;
            }
        }

        // 2. ABSOLUTE_DENY — 自定义规则
        for (RuleEntry rule : customDenyPatterns) {
            if (rule.pattern().matcher(stripped).find()) {
                BlockResult result = BlockResult.deny(rule.pattern().pattern(), rule.description());
                if (auditLogEnabled) {
                    auditLogger.logBlocked(rawCommand, result);
                }
                return result;
            }
        }

        // 3. HIGH_RISK_ASK — 内置规则
        for (RuleEntry rule : BUILTIN_HIGH_RISK_ASK) {
            if (rule.pattern().matcher(stripped).find()) {
                BlockResult result = BlockResult.ask(rule.pattern().pattern(), rule.description());
                if (auditLogEnabled) {
                    auditLogger.logBlocked(rawCommand, result);
                }
                return result;
            }
        }

        // 4. HIGH_RISK_ASK — 自定义规则
        for (RuleEntry rule : customAskPatterns) {
            if (rule.pattern().matcher(stripped).find()) {
                BlockResult result = BlockResult.ask(rule.pattern().pattern(), rule.description());
                if (auditLogEnabled) {
                    auditLogger.logBlocked(rawCommand, result);
                }
                return result;
            }
        }

        // 5. AUDIT_LOG — 内置规则
        for (RuleEntry rule : BUILTIN_AUDIT_LOG) {
            if (rule.pattern().matcher(stripped).find()) {
                BlockResult result = BlockResult.audit(rule.pattern().pattern(), rule.description());
                if (auditLogEnabled) {
                    auditLogger.logAuditEvent("command-audit", java.util.Map.of(
                            "command", rawCommand, "rule", rule.description()));
                }
                return result;
            }
        }

        return BlockResult.allowed();
    }

    /**
     * 检查 argv 列表是否命中黑名单规则。
     * 将 argv 合并为命令字符串后委托 {@link #checkCommand(String)}。
     *
     * @param argv 参数列表
     * @return 检查结果
     */
    public BlockResult checkArgv(List<String> argv) {
        if (argv == null || argv.isEmpty()) {
            return BlockResult.allowed();
        }
        String reconstructed = String.join(" ", argv);
        return checkCommand(reconstructed);
    }

    // ===== 辅助方法 =====

    /**
     * 剥离常见命令包装前缀。
     * <p>
     * 处理场景：
     * <ul>
     *   <li>/usr/bin/rm → rm</li>
     *   <li>/bin/rm → rm</li>
     *   <li>command rm → rm</li>
     *   <li>builtin cd → cd</li>
     * </ul>
     */
    private String stripCommandPrefix(String command) {
        String stripped = command;

        // 剥离绝对路径前缀：/usr/bin/rm -rf / → rm -rf /
        if (stripped.startsWith("/")) {
            int spaceIdx = stripped.indexOf(' ');
            String firstToken = spaceIdx > 0 ? stripped.substring(0, spaceIdx) : stripped;
            int lastSlash = firstToken.lastIndexOf('/');
            if (lastSlash >= 0) {
                String cmdName = firstToken.substring(lastSlash + 1);
                stripped = cmdName + (spaceIdx > 0 ? stripped.substring(spaceIdx) : "");
            }
        }

        // 剥离 command/builtin 前缀
        if (stripped.startsWith("command ") || stripped.startsWith("builtin ")) {
            stripped = stripped.substring(stripped.indexOf(' ') + 1).trim();
        }

        return stripped;
    }
}
