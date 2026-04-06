package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.state.AppStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * /model [model_name] — 切换 LLM 模型。
 * <p>
 * 无参数 → 显示模型选择 UI；有参数 → 直接切换模型。
 *
 * @see <a href="SPEC §3.3.2">/model 命令</a>
 */
@Component
public class ModelCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(ModelCommand.class);

    private final AppStateStore appStateStore;
    private final LlmProviderRegistry providerRegistry;

    public ModelCommand(AppStateStore appStateStore, LlmProviderRegistry providerRegistry) {
        this.appStateStore = appStateStore;
        this.providerRegistry = providerRegistry;
    }

    @Override public String getName() { return "model"; }
    @Override public String getDescription() {
        String current = appStateStore.getState().session().currentModel();
        return "Set the AI model (currently " + (current != null ? current : "none") + ")";
    }
    @Override public CommandType getType() { return CommandType.LOCAL_JSX; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        if (args == null || args.isBlank()) {
            // 显示可用模型列表
            List<String> models = providerRegistry.listAvailableModels();
            StringBuilder sb = new StringBuilder("Available Models:\n\n");
            for (String model : models) {
                String marker = model.equals(context.currentModel()) ? " (current)" : "";
                sb.append("  ").append(model).append(marker).append("\n");
            }
            sb.append("\nUsage: /model <model_name>");
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

        // 切换模型
        appStateStore.setState(state ->
                state.withSession(s -> s.withCurrentModel(modelName)));

        log.info("Model switched to: {}", modelName);
        return CommandResult.text("Model switched to: " + modelName);
    }
}
