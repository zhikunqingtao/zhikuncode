package com.aicodeassistant.hook;

import com.aicodeassistant.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 钩子执行服务 — 统一的钩子触发和执行入口。
 * <p>
 * 从 HookRegistry 获取已注册钩子，按优先级排序，
 * 通过正则匹配器过滤后链式执行。
 * <p>
 * 钩子可以:
 * <ul>
 *     <li>修改工具输入 (PreToolUse)</li>
 *     <li>修改工具输出 (PostToolUse)</li>
 *     <li>拒绝操作 (返回 proceed=false)</li>
 *     <li>注入额外上下文 (UserPromptSubmit)</li>
 * </ul>
 *
 * @see <a href="SPEC section 3.10">Hook 系统</a>
 * @see <a href="SPEC section 4.6.2">插件钩子注册</a>
 */
@Service
public class HookService {

    private static final Logger log = LoggerFactory.getLogger(HookService.class);

    private final HookRegistry hookRegistry;

    public HookService(HookRegistry hookRegistry) {
        this.hookRegistry = hookRegistry;
    }

    /**
     * 执行指定事件的所有匹配钩子。
     *
     * @param event   钩子事件类型
     * @param context 执行上下文
     * @return 执行结果（如果有钩子拒绝则 proceed=false）
     */
    public HookRegistry.HookResult execute(HookEvent event, HookRegistry.HookContext context) {
        List<HookRegistry.HookRegistration> hooks = hookRegistry.getHooks(event);
        if (hooks.isEmpty()) {
            return HookRegistry.HookResult.passThrough();
        }

        log.debug("Executing {} hooks for event: {}", hooks.size(), event);

        HookRegistry.HookContext currentContext = context;

        for (HookRegistry.HookRegistration hook : hooks) {
            // 匹配器检查
            if (!matchesContext(hook.matcher(), currentContext)) {
                continue;
            }

            try {
                HookRegistry.HookResult result = hook.handler().apply(currentContext);

                if (!result.proceed()) {
                    log.info("Hook denied operation: event={}, source={}, reason={}",
                            event, hook.source(), result.message());
                    return result;
                }

                // 如果钩子修改了输入，更新上下文供后续钩子使用
                if (result.modifiedInput() != null) {
                    currentContext = new HookRegistry.HookContext(
                            currentContext.toolName(),
                            result.modifiedInput(),
                            currentContext.output(),
                            currentContext.sessionId(),
                            currentContext.metadata()
                    );
                }

                // 如果钩子修改了输出
                if (result.modifiedOutput() != null) {
                    currentContext = new HookRegistry.HookContext(
                            currentContext.toolName(),
                            currentContext.input(),
                            result.modifiedOutput(),
                            currentContext.sessionId(),
                            currentContext.metadata()
                    );
                }

            } catch (Exception e) {
                log.warn("Hook execution failed: event={}, source={}, error={}",
                        event, hook.source(), e.getMessage());
                // 钩子执行失败不阻止主流程
            }
        }

        // 所有钩子都通过，返回可能被修改的结果
        if (currentContext != context) {
            // 上下文被修改过
            if (currentContext.input() != null && !currentContext.input().equals(context.input())) {
                return HookRegistry.HookResult.modifyInput(currentContext.input());
            }
            if (currentContext.output() != null && !currentContext.output().equals(context.output())) {
                return HookRegistry.HookResult.modifyOutput(currentContext.output());
            }
        }

        return HookRegistry.HookResult.passThrough();
    }

    /**
     * 执行 PreToolUse 钩子。
     */
    public HookRegistry.HookResult executePreToolUse(String toolName, String toolInput, String sessionId) {
        return execute(HookEvent.PRE_TOOL_USE,
                HookRegistry.HookContext.forTool(toolName, toolInput, sessionId));
    }

    /**
     * 执行 PostToolUse 钩子。
     */
    public HookRegistry.HookResult executePostToolUse(String toolName, String toolOutput, String sessionId) {
        return execute(HookEvent.POST_TOOL_USE,
                HookRegistry.HookContext.forToolOutput(toolName, toolOutput, sessionId));
    }

    /**
     * 执行 UserPromptSubmit 钩子。
     */
    public HookRegistry.HookResult executeUserPromptSubmit(String userInput, String sessionId) {
        return execute(HookEvent.USER_PROMPT_SUBMIT,
                HookRegistry.HookContext.forMessage(userInput, sessionId));
    }

    /**
     * 执行 Stop 钩子 — 返回 StopHookResult。
     * 对齐原版 stopHooks.ts:65-81 handleStopHooks()
     */
    public HookRegistry.StopHookResult executeStopHooks(List<Message> messages, String sessionId) {
        List<HookRegistry.HookRegistration> hooks = hookRegistry.getHooks(HookEvent.STOP);
        if (hooks.isEmpty()) {
            return HookRegistry.StopHookResult.ok();
        }

        List<String> blockingErrors = new ArrayList<>();
        boolean preventContinuation = false;

        for (HookRegistry.HookRegistration hook : hooks) {
            try {
                HookRegistry.HookContext context = HookRegistry.HookContext.forMessage(
                        messages.isEmpty() ? "" : messages.getLast().toString(),
                        sessionId);
                HookRegistry.HookResult result = hook.handler().apply(context);

                if (!result.proceed()) {
                    if (result.message() != null && result.message().startsWith("[PREVENT]")) {
                        preventContinuation = true;
                    } else {
                        blockingErrors.add(result.message() != null
                                ? result.message() : "Stop hook blocked continuation");
                    }
                }
            } catch (Exception e) {
                log.warn("Stop hook execution failed: source={}, error={}",
                        hook.source(), e.getMessage());
            }
        }

        if (preventContinuation) {
            return HookRegistry.StopHookResult.preventContinuation(
                    blockingErrors.isEmpty() ? "Hook prevented continuation"
                            : String.join("; ", blockingErrors));
        }
        if (!blockingErrors.isEmpty()) {
            return HookRegistry.StopHookResult.blocking(blockingErrors);
        }
        return HookRegistry.StopHookResult.ok();
    }

    /**
     * 执行 SessionStart 钩子 — 会话创建时触发。
     */
    public void executeSessionStart(String sessionId) {
        execute(HookEvent.SESSION_START,
                new HookRegistry.HookContext(null, null, null, sessionId,
                        Map.of("timestamp", Instant.now().toString())));
    }

    /**
     * 执行 SessionEnd 钩子 — 会话删除时触发。
     */
    public void executeSessionEnd(String sessionId, Map<String, Object> stats) {
        execute(HookEvent.SESSION_END,
                new HookRegistry.HookContext(null, null, null, sessionId,
                        stats != null ? stats : Map.of()));
    }

    /**
     * 执行 Notification 钩子 — 系统通知事件。
     */
    public void executeNotification(String level, String message) {
        execute(HookEvent.NOTIFICATION,
                new HookRegistry.HookContext(null, null, null, null,
                        Map.of("level", level, "message", message)));
    }

    /**
     * 匹配器检查 — 使用正则表达式匹配工具名。
     */
    private boolean matchesContext(String matcher, HookRegistry.HookContext context) {
        if (matcher == null || matcher.isEmpty()) {
            return true; // 空匹配器匹配所有
        }
        String target = context.toolName();
        if (target == null || target.isEmpty()) {
            return true; // 非工具事件（如 UserPromptSubmit）始终匹配
        }
        try {
            return Pattern.matches(matcher, target);
        } catch (Exception e) {
            log.warn("Invalid hook matcher pattern: {}", matcher);
            return false;
        }
    }
}
