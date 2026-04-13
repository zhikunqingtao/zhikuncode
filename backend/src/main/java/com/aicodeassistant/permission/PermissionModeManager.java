package com.aicodeassistant.permission;

import com.aicodeassistant.model.PermissionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 权限模式管理器 — 规则引擎实现。
 * <p>
 * 对齐 Claude Code permissionMode.ts:
 * <ul>
 *   <li>5 种用户模式 + 2 种内部模式</li>
 *   <li>DANGEROUS_EXEC_PATTERNS: 22+ 种危险命令模式</li>
 *   <li>AUTO 模式: 剥离危险 allow 规则</li>
 * </ul>
 * 注意: Sonnet LLM 分类器暂不实施，仅实现规则引擎部分。
 */
@Service
public class PermissionModeManager {

    private static final Logger log = LoggerFactory.getLogger(PermissionModeManager.class);

    /** 当前会话权限模式 — sessionId → PermissionMode */
    private final ConcurrentHashMap<String, PermissionMode> sessionModes = new ConcurrentHashMap<>();

    /** 危险执行模式 — AUTO 模式下即使有 allow 规则也强制 ask */
    private static final List<Pattern> DANGEROUS_EXEC_PATTERNS = List.of(
            // 文件系统破坏
            Pattern.compile("rm\\s+(-[rRf]+\\s+)*[/~]"),
            Pattern.compile("rmdir\\s+"),
            Pattern.compile("chmod\\s+(-R\\s+)?[0-7]{3,4}\\s+/"),
            Pattern.compile("chown\\s+(-R\\s+)?"),
            Pattern.compile("mkfs\\."),
            Pattern.compile("dd\\s+.*of=/dev/"),
            Pattern.compile("shred\\s+"),
            Pattern.compile("truncate\\s+"),
            // Git 危险操作
            Pattern.compile("git\\s+push\\s+.*--force"),
            Pattern.compile("git\\s+(reset|clean)\\s+--hard"),
            Pattern.compile("git\\s+checkout\\s+--\\s+\\."),
            // 数据库危险操作
            Pattern.compile("DROP\\s+(TABLE|DATABASE|INDEX)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("TRUNCATE\\s+TABLE", Pattern.CASE_INSENSITIVE),
            Pattern.compile("DELETE\\s+FROM\\s+\\w+\\s*;", Pattern.CASE_INSENSITIVE),
            // 进程/系统操作
            Pattern.compile("kill\\s+-9\\s+"),
            Pattern.compile("killall\\s+"),
            Pattern.compile("pkill\\s+"),
            Pattern.compile("reboot|shutdown|halt|poweroff"),
            // Fork bomb
            Pattern.compile(":\\(\\)\\{\\s*:\\|:&\\s*\\};:"),
            // 网络危险操作
            Pattern.compile("curl\\s+.*\\|\\s*(bash|sh|zsh)"),
            Pattern.compile("wget\\s+.*\\|\\s*(bash|sh|zsh)"),
            // 环境破坏
            Pattern.compile("unset\\s+(PATH|HOME|USER)")
    );

    /**
     * 获取当前模式。
     */
    public PermissionMode getMode(String sessionId) {
        return sessionModes.getOrDefault(sessionId, PermissionMode.DEFAULT);
    }

    /**
     * 检查会话是否已显式设置过权限模式（区别于 getMode 的默认 DEFAULT 回退）。
     */
    public boolean hasExplicitMode(String sessionId) {
        return sessionModes.containsKey(sessionId);
    }

    /**
     * 设置权限模式。
     */
    public void setMode(String sessionId, PermissionMode mode) {
        PermissionMode previous = sessionModes.put(sessionId, mode);
        log.info("Permission mode changed: session={}, {} → {}", sessionId, previous, mode);
    }

    /**
     * 进入 AUTO 模式 — 剥离危险 allow 规则。
     */
    public void enterAutoMode(String sessionId) {
        setMode(sessionId, PermissionMode.AUTO);
        log.info("Entered AUTO mode for session={}, dangerous patterns will be force-asked", sessionId);
    }

    /**
     * 检查命令是否匹配危险模式。
     * AUTO 模式下，匹配危险模式的命令即使有 allow 规则也强制 ask。
     */
    public boolean isDangerousExecution(String command) {
        if (command == null || command.isBlank()) return false;
        return DANGEROUS_EXEC_PATTERNS.stream()
                .anyMatch(pattern -> pattern.matcher(command).find());
    }

    /**
     * 判断当前模式是否需要跳过权限检查。
     */
    public boolean shouldSkipPermission(String sessionId, String toolName, Map<String, Object> input) {
        PermissionMode mode = getMode(sessionId);
        return switch (mode) {
            case BYPASS_PERMISSIONS -> true;
            case DONT_ASK -> false;  // DONT_ASK = 不弹窗但自动拒绝，不能跳过权限检查
            case ACCEPT_EDITS -> isEditTool(toolName);
            case AUTO -> {
                // AUTO 模式: 非危险操作自动允许
                String command = input != null ? String.valueOf(input.getOrDefault("command", "")) : "";
                yield !isDangerousExecution(command) && !isEditTool(toolName);
            }
            default -> false;
        };
    }

    /**
     * 判断当前模式下指定操作是否需要用户确认。
     * <p>
     * 与 shouldSkipPermission() 不同，此方法返回 true 表示"需要弹窗确认"，
     * 而非"跳过权限检查"。
     *
     * @param sessionId 会话 ID
     * @param toolName  工具名称
     * @param isReadOnly 是否为只读操作
     * @return true = 需要弹窗确认, false = 自动决定（允许或拒绝）
     */
    public boolean needsUserConfirmation(String sessionId, String toolName, boolean isReadOnly) {
        PermissionMode mode = getMode(sessionId);
        return switch (mode) {
            case DEFAULT -> true;
            case PLAN -> !isReadOnly;
            case ACCEPT_EDITS -> !isEditTool(toolName);
            case DONT_ASK -> false;           // 不弹窗（自动拒绝写操作）
            case BYPASS_PERMISSIONS -> false;  // 不弹窗（自动允许）
            case AUTO -> false;               // LLM 自动判定
            case BUBBLE -> true;              // 冒泡到父代理确认
        };
    }

    private boolean isEditTool(String toolName) {
        return "Edit".equals(toolName) || "Write".equals(toolName)
                || "FileEdit".equals(toolName) || "FileWrite".equals(toolName);
    }

    /** 清除会话模式 */
    public void clearSession(String sessionId) {
        sessionModes.remove(sessionId);
    }
}
