package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.llm.LlmProviderRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * /model [model_name] — 列出可用 LLM 模型。
 * <p>
 * 无参数 → 显示可用模型列表（中性列表，不标记当前模型）；
 * 有参数 → 命令本身不切换会话模型，引导使用输入栏的模型选择器。
 *
 */
@Component
public class ModelCommand implements Command {

    private final LlmProviderRegistry providerRegistry;

    public ModelCommand(LlmProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    @Override public String getName() { return "model"; }
    @Override public String getDescription() {
        return "List available models. Switching uses the model selector.";
    }
    @Override public CommandType getType() { return CommandType.LOCAL_JSX; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        if (args == null || args.isBlank()) {
            // 显示可用模型列表 — 中性列表，不猜测/标记当前模型
            List<String> models = providerRegistry.listAvailableModels();
            StringBuilder sb = new StringBuilder("Available Models:\n\n");
            for (String model : models) {
                sb.append("  ").append(model).append("\n");
            }
            sb.append("\nTo switch models, use the model selector in the input bar.");
            return CommandResult.text(sb.toString());
        }

        String modelName = args.trim();

        // 验证模型是否存在
        List<String> available = providerRegistry.listAvailableModels();
        if (!available.contains(modelName)) {
            StringBuilder sb = new StringBuilder("Unknown model: " + modelName + "\n");
            sb.append("Available models: ");
            sb.append(String.join(", ", available));
            return CommandResult.error(sb.toString());
        }

        // 命令不切换会话模型：不写入任何状态，避免虚假成功回执
        return CommandResult.text(
                "This command does not switch models. Use the model selector in the input bar to switch the session model.");
    }
}
