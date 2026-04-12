package com.aicodeassistant.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * MCP 配置解析器 — 多来源配置的统一加载与合并。
 * <p>
 * 当前实现 4 级优先级（从低到高，后加载的覆盖先加载的同名服务器）:
 * <ol>
 *   <li>ENV — 环境变量 {@code MCP_SERVERS}（JSON 格式）</li>
 *   <li>ENTERPRISE — 企业级 {@code /etc/ai-code-assistant/mcp.json}</li>
 *   <li>USER — 用户级 {@code ~/.config/ai-code-assistant/mcp.json}</li>
 *   <li>LOCAL — 项目本地 {@code .ai-code-assistant/mcp.json}（最高优先级）</li>
 * </ol>
 * <p>
 * 后续 Roadmap（当前不在 ZhikuCode 实现范围）:
 * <ul>
 *   <li>CLAUDEAI — Claude AI 平台托管配置</li>
 *   <li>MANAGED — Marketplace 管理配置</li>
 * </ul>
 * <p>
 * 合并规则：高优先级覆盖低优先级的同名服务器配置。
 *
 * @see McpClientManager
 * @see McpConfigScope
 */
@Component
public class McpConfigurationResolver {

    private static final Logger log = LoggerFactory.getLogger(McpConfigurationResolver.class);

    private static final String PROJECT_MCP_FILE = ".ai-code-assistant/mcp.json";
    private static final String USER_MCP_FILE = ".config/ai-code-assistant/mcp.json";
    private static final String ENTERPRISE_MCP_FILE = "/etc/ai-code-assistant/mcp.json";
    private static final String ENV_VAR_MCP_SERVERS = "MCP_SERVERS";

    private final ObjectMapper objectMapper;
    private final Environment environment;

    @Value("${app.working-dir:}")
    private String workingDir;

    public McpConfigurationResolver(ObjectMapper objectMapper, Environment environment) {
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    /**
     * 解析并合并所有来源的 MCP 配置。
     * <p>
     * 按优先级从低到高加载，后加载的同名服务器覆盖先加载的。
     * 当前实现 ENV / ENTERPRISE / USER / LOCAL 四种来源。
     *
     * @return 合并后的 MCP 服务器配置列表
     */
    public List<McpServerConfig> resolveAll() {
        Map<String, McpServerConfig> merged = new LinkedHashMap<>();

        // 按优先级从低到高加载（后加载的覆盖先加载的）
        loadFromEnvironment().forEach(c -> merged.put(c.name(), c));                                          // ENV (最低)
        loadFromFile(McpConfigScope.ENTERPRISE, ENTERPRISE_MCP_FILE).forEach(c -> merged.put(c.name(), c));   // ENTERPRISE
        loadFromFile(McpConfigScope.USER, getUserMcpPath()).forEach(c -> merged.put(c.name(), c));             // USER
        loadFromFile(McpConfigScope.LOCAL, getProjectMcpPath()).forEach(c -> merged.put(c.name(), c));         // LOCAL (最高)

        log.info("Resolved {} MCP server configurations from {} sources",
                merged.size(), countSources(merged.values()));

        return new ArrayList<>(merged.values());
    }

    /**
     * 从 MCP 配置文件加载服务器配置。
     * <p>
     * 支持两种 JSON 结构:
     * <pre>
     * { "mcpServers": { "name": { ... } } }
     * { "name": { ... } }
     * </pre>
     *
     * @param scope    配置作用域
     * @param filePath 文件路径
     * @return 解析到的配置列表（文件不存在或解析失败返回空列表）
     */
    private List<McpServerConfig> loadFromFile(McpConfigScope scope, String filePath) {
        if (filePath == null) return List.of();
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            log.debug("MCP config file not found: {}", filePath);
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(path.toFile());
            JsonNode servers = root.has("mcpServers") ? root.get("mcpServers") : root;
            List<McpServerConfig> configs = new ArrayList<>();
            servers.fieldNames().forEachRemaining(name -> {
                try {
                    McpServerConfig config = parseServerNode(name, servers.get(name), scope);
                    if (config != null) {
                        configs.add(config);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse MCP config for '{}': {}", name, e.getMessage());
                }
            });
            log.debug("Loaded {} MCP configs from {} (scope={})", configs.size(), filePath, scope);
            return configs;
        } catch (IOException e) {
            log.warn("Failed to read MCP config file {}: {}", filePath, e.getMessage());
            return List.of();
        }
    }

    /**
     * 从环境变量 {@code MCP_SERVERS} 加载。
     * <p>
     * 格式: JSON 字符串，结构与 mcp.json 相同。
     */
    private List<McpServerConfig> loadFromEnvironment() {
        String envValue = environment.getProperty(ENV_VAR_MCP_SERVERS,
                System.getenv(ENV_VAR_MCP_SERVERS));
        if (envValue == null || envValue.isBlank()) return List.of();
        try {
            JsonNode root = objectMapper.readTree(envValue);
            List<McpServerConfig> configs = new ArrayList<>();
            root.fieldNames().forEachRemaining(name -> {
                try {
                    McpServerConfig config = parseServerNode(name, root.get(name), McpConfigScope.DYNAMIC);
                    if (config != null) {
                        configs.add(config);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse env MCP config for '{}': {}", name, e.getMessage());
                }
            });
            log.debug("Loaded {} MCP configs from env var {}", configs.size(), ENV_VAR_MCP_SERVERS);
            return configs;
        } catch (Exception e) {
            log.warn("Failed to parse MCP_SERVERS env var: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析单个 MCP 服务器 JSON 节点为 {@link McpServerConfig}。
     *
     * @param name       服务器名称
     * @param serverNode JSON 节点
     * @param scope      配置作用域
     * @return 解析后的配置；无法确定传输类型时返回 null
     */
    private McpServerConfig parseServerNode(String name, JsonNode serverNode, McpConfigScope scope) {
        // 判断传输类型: 有 command → STDIO, 有 url → SSE/HTTP
        McpTransportType type;
        String command = null;
        List<String> args = List.of();
        String url = null;
        Map<String, String> headers = Map.of();
        Map<String, String> env = Map.of();

        if (serverNode.has("command")) {
            type = McpTransportType.STDIO;
            command = serverNode.get("command").asText();
            if (serverNode.has("args")) {
                List<String> argList = new ArrayList<>();
                serverNode.get("args").forEach(n -> argList.add(n.asText()));
                args = argList;
            }
        } else if (serverNode.has("url")) {
            String typeStr = serverNode.has("type")
                    ? serverNode.get("type").asText().toUpperCase() : "SSE";
            type = switch (typeStr) {
                case "HTTP" -> McpTransportType.HTTP;
                case "WS" -> McpTransportType.WS;
                default -> McpTransportType.SSE;
            };
            url = serverNode.get("url").asText();
        } else {
            log.warn("MCP config '{}' has neither 'command' nor 'url', skipping", name);
            return null;
        }

        // 解析环境变量
        if (serverNode.has("env")) {
            Map<String, String> envMap = new LinkedHashMap<>();
            serverNode.get("env").fieldNames().forEachRemaining(
                    k -> envMap.put(k, serverNode.get("env").get(k).asText()));
            env = envMap;
        }

        // 解析 headers
        if (serverNode.has("headers")) {
            Map<String, String> headerMap = new LinkedHashMap<>();
            serverNode.get("headers").fieldNames().forEachRemaining(
                    k -> headerMap.put(k, serverNode.get("headers").get(k).asText()));
            headers = headerMap;
        }

        return new McpServerConfig(name, type, command, args, env, url, headers, scope);
    }

    // ===== 路径解析 =====

    private String getProjectMcpPath() {
        if (workingDir == null || workingDir.isBlank()) return null;
        return Path.of(workingDir, PROJECT_MCP_FILE).toString();
    }

    private String getUserMcpPath() {
        String home = System.getProperty("user.home");
        if (home == null) return null;
        return Path.of(home, USER_MCP_FILE).toString();
    }

    private long countSources(Collection<McpServerConfig> configs) {
        return configs.stream().map(McpServerConfig::scope).distinct().count();
    }

    // ===== Roadmap 预留 =====

    /**
     * 从 Claude AI 平台加载托管配置 — 后续 Roadmap，当前返回空。
     */
    @SuppressWarnings("unused")
    private List<McpServerConfig> loadFromClaudeAI() {
        // TODO: CLAUDEAI scope — 平台托管配置，后续版本实现
        return List.of();
    }

    /**
     * 从 Marketplace 加载管理配置 — 后续 Roadmap，当前返回空。
     */
    @SuppressWarnings("unused")
    private List<McpServerConfig> loadFromManaged() {
        // TODO: MANAGED scope — Marketplace 管理配置，后续版本实现
        return List.of();
    }
}
