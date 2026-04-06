package com.aicodeassistant.command;

import com.aicodeassistant.command.slash.SlashCommandParser;
import com.aicodeassistant.command.slash.SlashCommandParser.ParsedSlashCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 命令路由器 — 解析用户输入并分发到对应命令执行。
 * <p>
 * 执行路径 (按 command.type):
 * <ul>
 *     <li>PROMPT → 返回提示词结果，由引擎发送给 LLM</li>
 *     <li>LOCAL → 直接执行并返回结果</li>
 *     <li>LOCAL_JSX → 执行并返回需前端渲染的数据</li>
 * </ul>
 * <p>
 * 安全检查:
 * <ul>
 *     <li>远程模式 → 仅允许 REMOTE_SAFE_COMMANDS</li>
 *     <li>桥接模式 → 仅允许 BRIDGE_SAFE_COMMANDS</li>
 *     <li>命令可用性 → 认证/特性标志检查</li>
 * </ul>
 *
 * @see <a href="SPEC §3.3.4a.3">命令查找与执行流程</a>
 */
@Service
public class CommandRouter {

    private static final Logger log = LoggerFactory.getLogger(CommandRouter.class);

    private final CommandRegistry registry;

    public CommandRouter(CommandRegistry registry) {
        this.registry = registry;
    }

    /**
     * 路由并执行斜杠命令。
     *
     * @param input   用户原始输入（如 "/help compact"）
     * @param context 命令执行上下文
     * @return 命令执行结果
     */
    public CommandResult route(String input, CommandContext context) {
        // 1. 解析斜杠命令
        ParsedSlashCommand parsed = SlashCommandParser.parse(input);
        if (parsed == null) {
            return CommandResult.error("Invalid command format. Commands start with /");
        }

        // 2. 查找命令
        Command command;
        try {
            command = registry.getCommand(parsed.commandName());
        } catch (CommandRegistry.CommandNotFoundException e) {
            return CommandResult.error(e.getMessage());
        }

        // 3. 安全检查 — 远程/桥接模式
        if (context.isRemoteMode() && !registry.isRemoteSafe(command.getName())) {
            return CommandResult.error(
                    "Command /" + command.getName() + " is not available in remote mode.");
        }
        if (context.isBridgeMode() && !registry.isBridgeSafe(command.getName())) {
            return CommandResult.error(
                    "Command /" + command.getName() + " is not available in bridge mode.");
        }

        // 4. 可用性检查
        if (command.getAvailability() == CommandAvailability.REQUIRES_AUTH
                && !context.isAuthenticated()) {
            return CommandResult.error(
                    "Command /" + command.getName() + " requires authentication. Use /login first.");
        }

        // 5. 执行命令
        try {
            log.debug("Executing command: /{} args='{}' type={}",
                    command.getName(), parsed.args(), command.getType());

            CommandResult result = command.execute(parsed.args(), context);

            log.debug("Command /{} completed: type={}", command.getName(), result.type());
            return result;

        } catch (Exception e) {
            log.error("Command /{} failed: {}", command.getName(), e.getMessage(), e);
            return CommandResult.error("Command /" + command.getName() + " failed: " + e.getMessage());
        }
    }

    /**
     * 检查输入是否为斜杠命令。
     */
    public boolean isSlashCommand(String input) {
        return SlashCommandParser.isSlashCommand(input);
    }
}
