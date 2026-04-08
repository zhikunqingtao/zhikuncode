package com.aicodeassistant.controller;

import com.aicodeassistant.plugin.LoadedPlugin;
import com.aicodeassistant.plugin.PluginManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 插件管理 Controller — 管理插件的安装、卸载和查询。
 *
 * @see <a href="SPEC §6.1.5">插件管理 API</a>
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    private final PluginManager pluginManager;

    public PluginController(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /** 列出已安装的插件 */
    @GetMapping
    public ResponseEntity<Map<String, List<PluginInfo>>> listPlugins() {
        List<PluginInfo> plugins = pluginManager.getLoadedPlugins().stream()
                .map(p -> new PluginInfo(
                        p.name(),
                        p.manifest() != null ? p.manifest().version() : "unknown",
                        p.manifest() != null ? p.manifest().description() : "",
                        p.enabled(),
                        p.commands().size(),
                        p.tools().size(),
                        p.hooks().size()))
                .toList();
        return ResponseEntity.ok(Map.of("plugins", plugins));
    }

    /** 安装插件 — 支持 local/marketplace/builtin 来源 */
    @PostMapping("/install")
    public ResponseEntity<Map<String, Object>> installPlugin(
            @RequestBody InstallPluginRequest request) {
        // P1: 插件安装逻辑需要 PluginLoader 支持动态加载
        // 当前返回 501 Not Implemented
        return ResponseEntity.status(501).body(
                Map.of("error", "Plugin installation not yet implemented (P1)"));
    }

    /** 删除插件 */
    @DeleteMapping("/{pluginId}")
    public ResponseEntity<Map<String, Boolean>> deletePlugin(@PathVariable String pluginId) {
        // P1: 插件卸载逻辑
        return ResponseEntity.status(501).body(
                Map.of("success", false));
    }

    /** 重新加载所有插件 */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Integer>> reloadPlugins() {
        pluginManager.reloadPlugins();
        return ResponseEntity.ok(Map.of(
                "loaded", pluginManager.getPluginCount(),
                "failed", 0));
    }

    // ═══ DTO Records ═══
    public record PluginInfo(String name, String version, String description,
                             boolean enabled, int commandCount, int toolCount, int hookCount) {}
    public record InstallPluginRequest(String source, String identifier) {}
}
