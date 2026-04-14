package com.aicodeassistant.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 命令注册表 — Spring @Component 自动发现 + 运行时注册。
 * <p>
 * 启动时通过 Spring 依赖注入自动收集所有实现 {@link Command} 接口的 Bean，
 * 按名称和别名建立索引。支持运行时动态注册/注销命令（MCP/插件命令）。
 * <p>
 * 查找算法 (对照源码 findCommand):
 * <ol>
 *     <li>精确匹配 name</li>
 *     <li>匹配 aliases</li>
 *     <li>未找到 → 模糊匹配建议 (Levenshtein)</li>
 * </ol>
 *
 * @see <a href="SPEC §3.3.4a.3">命令查找与执行流程</a>
 */
@Service
public class CommandRegistry {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistry.class);

    /** 主索引: name → Command */
    private final Map<String, Command> commandsByName = new ConcurrentHashMap<>();

    /** 别名索引: alias → Command */
    private final Map<String, Command> commandsByAlias = new ConcurrentHashMap<>();

    /** 远程安全命令集 — 可在远程模式下安全执行 */
    public static final Set<String> REMOTE_SAFE_COMMANDS = Set.of(
            "clear", "compact", "cost", "diff", "effort", "exit", "export",
            "fast", "files", "help", "keybindings", "model", "output-style",
            "permissions", "session", "status", "theme", "usage", "vim",
            // 新增安全命令
            "doctor", "allowed-tools",
            "mcp-servers", "mcp-tools", "mcp-resources",
            "symbols", "explain"
    );

    /** 动态命令源 — 支持 MCP/插件等运行时注册命令 */
    private final Map<String, Supplier<List<Command>>> dynamicSources = new ConcurrentHashMap<>();

    /** 桥接安全命令集 — 可在 IDE 桥接模式下安全执行 */
    public static final Set<String> BRIDGE_SAFE_COMMANDS;
    static {
        Set<String> bridge = new HashSet<>(REMOTE_SAFE_COMMANDS);
        bridge.addAll(Set.of("config", "context", "memory", "resume", "tasks", "agents"));
        BRIDGE_SAFE_COMMANDS = Collections.unmodifiableSet(bridge);
    }

    /**
     * Spring 自动注入所有 Command Bean — 构建索引。
     */
    public CommandRegistry(List<Command> commands) {
        for (Command cmd : commands) {
            register(cmd);
        }
        log.info("CommandRegistry initialized: {} commands registered", commandsByName.size());
    }

    // ───── 注册/注销 ─────

    /**
     * 注册命令 — 同时建立名称和别名索引。
     */
    public void register(Command command) {
        String name = command.getName().toLowerCase();
        Command existing = commandsByName.put(name, command);
        if (existing != null) {
            log.warn("Command '{}' overridden by {}", name, command.getClass().getSimpleName());
        }

        for (String alias : command.getAliases()) {
            commandsByAlias.put(alias.toLowerCase(), command);
        }

        log.debug("Registered command: /{} (type={}, aliases={})",
                name, command.getType(), command.getAliases());
    }

    /**
     * 注销命令 — 移除名称和别名索引。
     */
    public void unregister(String name) {
        Command removed = commandsByName.remove(name.toLowerCase());
        if (removed != null) {
            for (String alias : removed.getAliases()) {
                commandsByAlias.remove(alias.toLowerCase());
            }
            log.debug("Unregistered command: /{}", name);
        }
    }

    // ───── 查找 ─────

    /**
     * 查找命令 — 先精确匹配名称，再匹配别名。
     */
    public Optional<Command> findCommand(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String lower = name.toLowerCase();

        Command cmd = commandsByName.get(lower);
        if (cmd != null) return Optional.of(cmd);

        cmd = commandsByAlias.get(lower);
        if (cmd != null) return Optional.of(cmd);

        return Optional.empty();
    }

    /**
     * 获取命令 — 未找到时抛出异常并附带模糊匹配建议。
     */
    public Command getCommand(String name) {
        return findCommand(name).orElseThrow(() -> {
            String suggestions = suggestCommands(name);
            return new CommandNotFoundException(
                    "Unknown command: /" + name + ". " + suggestions);
        });
    }

    /**
     * 模糊匹配建议 — 基于 Levenshtein 距离。
     */
    public String suggestCommands(String input) {
        if (input == null || input.isBlank()) return "Type /help for available commands.";

        String lower = input.toLowerCase();
        List<String> suggestions = commandsByName.keySet().stream()
                .filter(name -> levenshteinDistance(lower, name) <= 3
                        || name.contains(lower) || lower.contains(name))
                .sorted(Comparator.comparingInt(name -> levenshteinDistance(lower, name)))
                .limit(3)
                .toList();

        if (suggestions.isEmpty()) {
            return "Type /help for available commands.";
        }
        return "Did you mean: " + suggestions.stream()
                .map(s -> "/" + s)
                .collect(Collectors.joining(", ")) + "?";
    }

    // ───── 列表查询 ─────

    /**
     * 获取所有已注册命令。
     */
    public Collection<Command> getAllCommands() {
        return Collections.unmodifiableCollection(commandsByName.values());
    }

    /**
     * 注册动态命令源。
     */
    public void registerDynamicCommandSource(String sourceName,
            Supplier<List<Command>> commandSupplier) {
        dynamicSources.put(sourceName, commandSupplier);
        log.info("Registered dynamic command source: {}", sourceName);
    }

    /**
     * 合并静态命令 + 动态命令源。
     */
    public List<Command> getVisibleCommandsWithDynamic() {
        List<Command> all = new ArrayList<>(getVisibleCommands());
        dynamicSources.values().forEach(supplier -> {
            try { all.addAll(supplier.get()); }
            catch (Exception e) { log.warn("动态命令源加载失败", e); }
        });
        return all;
    }

    /**
     * 获取所有可见命令 — 排除 hidden 和不可用命令。
     */
    public List<Command> getVisibleCommands() {
        return commandsByName.values().stream()
                .filter(cmd -> !cmd.isHidden())
                .filter(Command::isUserInvocable)
                .sorted(Comparator.comparing(Command::getName))
                .toList();
    }

    /**
     * 按类型获取命令。
     */
    public List<Command> getCommandsByType(CommandType type) {
        return commandsByName.values().stream()
                .filter(cmd -> cmd.getType() == type)
                .sorted(Comparator.comparing(Command::getName))
                .toList();
    }

    /**
     * 检查命令是否为远程安全命令。
     */
    public boolean isRemoteSafe(String name) {
        return REMOTE_SAFE_COMMANDS.contains(name.toLowerCase());
    }

    /**
     * 检查命令是否为桥接安全命令。
     */
    public boolean isBridgeSafe(String name) {
        return BRIDGE_SAFE_COMMANDS.contains(name.toLowerCase());
    }

    /**
     * 已注册命令数量。
     */
    public int size() {
        return commandsByName.size();
    }

    // ───── Levenshtein 距离 ─────

    private static int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    // ───── 异常类 ─────

    public static class CommandNotFoundException extends RuntimeException {
        public CommandNotFoundException(String message) {
            super(message);
        }
    }
}
