package com.aicodeassistant.engine;

import com.aicodeassistant.command.CommandRegistry;
import com.aicodeassistant.command.slash.SlashCommandParser;
import com.aicodeassistant.hook.HookEvent;
import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.hook.HookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * 两阶段用户输入处理器。
 * <p>
 * 源码中 processUserInput() 分两步:
 * <ol>
 *     <li>processUserInputBase() — 纯数据处理（命令解析、附件、标准化）</li>
 *     <li>executeSubmitHooks() — 异步钩子执行（可阻塞提交）</li>
 * </ol>
 * <p>
 * 分离的原因:
 * <ul>
 *     <li>阶段1 是同步的、确定性的</li>
 *     <li>阶段2 是异步的、可能有副作用的</li>
 *     <li>hooks 需要看到标准化后的消息（而非原始输入）</li>
 * </ul>
 *
 * @see <a href="SPEC section 3.1.0">用户输入处理器</a>
 */
@Service
public class UserInputProcessor {

    private static final Logger log = LoggerFactory.getLogger(UserInputProcessor.class);

    private final CommandRegistry commandRegistry;
    private final ContextAttachmentResolver attachmentResolver;
    private final HookService hookService;

    public UserInputProcessor(CommandRegistry commandRegistry,
                               ContextAttachmentResolver attachmentResolver,
                               HookService hookService) {
        this.commandRegistry = commandRegistry;
        this.attachmentResolver = attachmentResolver;
        this.hookService = hookService;
    }

    /**
     * 阶段1: 基础处理 — 命令检测、附件解析、消息标准化。
     *
     * @param rawInput         原始用户输入
     * @param workingDirectory 当前工作目录
     * @return 处理结果
     */
    public ProcessedInput processUserInputBase(String rawInput, Path workingDirectory) {
        if (rawInput == null || rawInput.isBlank()) {
            return ProcessedInput.message("");
        }

        String trimmed = rawInput.trim();

        // 1. 检测是否为 /command
        if (trimmed.startsWith("/")) {
            var parsed = SlashCommandParser.parse(trimmed);
            if (parsed != null) {
                var command = commandRegistry.findCommand(parsed.commandName());
                if (command.isPresent()) {
                    log.debug("Detected command: /{} args='{}'",
                            parsed.commandName(), parsed.args());
                    return ProcessedInput.command(parsed.commandName(), parsed.args());
                }
            }
        }

        // 2. 解析上下文附件 (@file 引用)
        List<ProcessedInput.ContextAttachment> attachments = List.of();
        if (workingDirectory != null) {
            attachments = attachmentResolver.resolve(trimmed, workingDirectory);
        }

        // 3. 消息标准化 (修剪空白、移除 @file 引用)
        String normalized = normalizeMessage(trimmed);

        // 4. 构建标准化消息
        return ProcessedInput.message(normalized, attachments);
    }

    /**
     * 阶段2: Hook 执行 — UserPromptSubmit hooks。
     * <p>
     * hooks 可以:
     * <ul>
     *     <li>修改消息内容 (如自动注入代码风格提示)</li>
     *     <li>注入额外上下文 (如相关文件内容)</li>
     *     <li>阻止提交 (如检测到敏感信息)</li>
     *     <li>添加元数据标签</li>
     * </ul>
     *
     * @param input     阶段1 处理结果
     * @param sessionId 当前会话 ID
     * @return 钩子执行结果
     */
    public HookRegistry.HookResult executeSubmitHooks(ProcessedInput input, String sessionId) {
        if (input.isCommand()) {
            return HookRegistry.HookResult.passThrough();
        }

        String messageText = "";
        if (input.message() != null && input.message().content() != null) {
            messageText = input.message().content().stream()
                    .filter(b -> b instanceof com.aicodeassistant.model.ContentBlock.TextBlock)
                    .map(b -> ((com.aicodeassistant.model.ContentBlock.TextBlock) b).text())
                    .reduce("", (a, b) -> a + b);
        }

        return hookService.execute(HookEvent.USER_PROMPT_SUBMIT,
                HookRegistry.HookContext.forMessage(messageText, sessionId));
    }

    /**
     * 完整处理流程 — 阶段1 + 阶段2。
     */
    public ProcessedInput process(String rawInput, Path workingDirectory, String sessionId) {
        ProcessedInput result = processUserInputBase(rawInput, workingDirectory);

        if (result.isMessage()) {
            HookRegistry.HookResult hookResult = executeSubmitHooks(result, sessionId);
            if (!hookResult.proceed()) {
                log.info("UserPromptSubmit hook denied: {}", hookResult.message());
                return ProcessedInput.message("[blocked] " + hookResult.message());
            }
            // 如果钩子修改了输入，使用修改后的文本
            if (hookResult.modifiedInput() != null) {
                return ProcessedInput.message(hookResult.modifiedInput(), result.attachments());
            }
        }

        return result;
    }

    /**
     * 消息标准化 — 修剪空白、编码处理。
     */
    private String normalizeMessage(String raw) {
        // 移除 @file 引用
        String stripped = attachmentResolver.stripFileReferences(raw);
        // 修剪空白、规范化换行
        return stripped.trim()
                .replaceAll("\\r\\n", "\n")
                .replaceAll("\\r", "\n");
    }
}
