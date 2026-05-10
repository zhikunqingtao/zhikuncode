package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.Command;
import com.aicodeassistant.command.CommandContext;
import com.aicodeassistant.command.CommandResult;
import com.aicodeassistant.command.CommandType;
import com.aicodeassistant.service.VisualizationPayloadBuilder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /visualize (别名: /viz) — 将参数渲染为独立可视化消息。
 *
 * <p>对齐 ZhikunCode 差异化升级方案 v1.5 §4.7 "C 显式命令"。
 *
 * <p>用法:
 * <pre>
 *   /visualize mermaid graph TD; A-->B; B-->C;
 *   /visualize text 这是一段自由文本
 *   /visualize json {"viewType":"mermaid","props":{"content":"graph LR; X-->Y"}}
 *   /visualize schema {"fields":[{"name":"id","type":"string"}]}
 * </pre>
 *
 * <p>语义:
 * <ol>
 *   <li>第一个 token 为 viewType（大小写不敏感）</li>
 *   <li>剩余文本构成 props：
 *     <ul>
 *       <li>viewType=json 时尝试 JSON.parse，成功即作为 props；失败降级为 content 字段</li>
 *       <li>其他 viewType 一律打包为 {@code {"content": rest}}</li>
 *     </ul>
 *   </li>
 *   <li>推送成功后返回 {@link CommandResult#skip()} — visualization 独立消息已由
 *       {@link VisualizationPayloadBuilder} 经 /queue/messages 单播到前端；
 *       无需再推 command_result 造成重复显示</li>
 * </ol>
 */
@Component
public class VisualizeCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(VisualizeCommand.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final VisualizationPayloadBuilder builder;

    public VisualizeCommand(VisualizationPayloadBuilder builder) {
        this.builder = builder;
    }

    @Override public String getName() { return "visualize"; }
    @Override public List<String> getAliases() { return List.of("viz"); }
    @Override
    public String getDescription() {
        return "Render structured output as a visualization (viewTypes: mermaid, text, json, schema, timeline, ...)";
    }
    @Override public CommandType getType() { return CommandType.LOCAL; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        String sessionId = context.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return CommandResult.error("No active session.");
        }
        if (args == null || args.isBlank()) {
            return CommandResult.error(
                    "Usage: /visualize <viewType> <content>   "
                    + "(viewTypes: mermaid, text, json, schema, timeline, ...)");
        }

        String trimmed = args.trim();
        int sepIdx = firstWhitespace(trimmed);
        String viewType = (sepIdx < 0 ? trimmed : trimmed.substring(0, sepIdx)).toLowerCase();
        String rest = sepIdx < 0 ? "" : trimmed.substring(sepIdx + 1).trim();

        Map<String, Object> props = buildProps(viewType, rest);
        boolean ok = builder.publish(sessionId, viewType, props);
        if (!ok) {
            return CommandResult.error("Failed to publish visualization (no active WebSocket session?).");
        }
        log.info("/visualize dispatched: sessionId={}, viewType={}", sessionId, viewType);
        // SKIP: visualization 独立消息已由 Builder 单播推送，不再走 command_result 通路
        return CommandResult.skip();
    }

    /**
     * 构建 props:
     * <ul>
     *   <li>viewType=json 且 rest 可解析为 Map → 直接作为 props</li>
     *   <li>否则打包为 {"content": rest}（rest 可能是 Mermaid 源码、自由文本等）</li>
     * </ul>
     */
    private Map<String, Object> buildProps(String viewType, String rest) {
        if ("json".equals(viewType) && !rest.isBlank()) {
            try {
                return JSON.readValue(rest, new TypeReference<LinkedHashMap<String, Object>>() {});
            } catch (JsonProcessingException e) {
                log.debug("/visualize json parse failed, falling back to content: {}", e.getMessage());
            }
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("content", rest);
        return props;
    }

    private int firstWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }
}
