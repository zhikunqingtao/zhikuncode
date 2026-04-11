package com.aicodeassistant.tool.impl;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.util.Map;
import java.util.StringJoiner;

/**
 * MonitorTool — 资源监控工具。
 * <p>
 * 提供 CPU、内存、磁盘使用状况等系统资源信息。
 * 通过 {@code feature('RESOURCE_MONITOR')} 门控，需显式启用。
 *
 * @see <a href="SPEC §11">MonitorTool</a>
 */
@Component
public class MonitorTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(MonitorTool.class);

    private final FeatureFlagService featureFlagService;

    public MonitorTool(FeatureFlagService featureFlagService) {
        this.featureFlagService = featureFlagService;
    }

    @Override
    public String getName() {
        return "Monitor";
    }

    @Override
    public String getDescription() {
        return "Monitor system resources including CPU, memory, and disk usage.";
    }

    @Override
    public String prompt() {
        return """
                Monitor system resources. Returns CPU load, memory usage, and disk space.
                Use this tool when you need to check system health or resource availability.
                Requires RESOURCE_MONITOR feature flag to be enabled.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "category", Map.of(
                                "type", "string",
                                "description", "Resource category to monitor: 'all', 'cpu', 'memory', 'disk', 'jvm'",
                                "enum", new String[]{"all", "cpu", "memory", "disk", "jvm"}
                        )
                ),
                "required", new String[]{}
        );
    }

    @Override
    public boolean isReadOnly(ToolInput input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return true;
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        // 门控检查
        if (!featureFlagService.isEnabled("RESOURCE_MONITOR")) {
            return ToolResult.error("MonitorTool is disabled. Enable feature flag 'RESOURCE_MONITOR' to use.");
        }

        String category = input.getOptionalString("category").orElse("all");

        try {
            String result = switch (category) {
                case "cpu" -> getCpuInfo();
                case "memory" -> getMemoryInfo();
                case "disk" -> getDiskInfo();
                case "jvm" -> getJvmInfo();
                default -> getAllInfo();
            };

            return ToolResult.success(result);
        } catch (Exception e) {
            log.error("MonitorTool error: {}", e.getMessage(), e);
            return ToolResult.error("Failed to collect system metrics: " + e.getMessage());
        }
    }

    // ── CPU ──────────────────────────────────────────────────

    private String getCpuInfo() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        StringBuilder sb = new StringBuilder();
        sb.append("## CPU\n");
        sb.append("- Available processors: ").append(os.getAvailableProcessors()).append("\n");
        sb.append("- System load average (1 min): ").append(
                String.format("%.2f", os.getSystemLoadAverage())).append("\n");
        sb.append("- Architecture: ").append(os.getArch()).append("\n");
        sb.append("- OS: ").append(os.getName()).append(" ").append(os.getVersion()).append("\n");

        // 如果是 com.sun.management 扩展（大多数 JVM 支持）
        if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            sb.append("- Process CPU load: ").append(
                    String.format("%.1f%%", sunOs.getProcessCpuLoad() * 100)).append("\n");
            sb.append("- System CPU load: ").append(
                    String.format("%.1f%%", sunOs.getCpuLoad() * 100)).append("\n");
        }

        return sb.toString();
    }

    // ── Memory ──────────────────────────────────────────────

    private String getMemoryInfo() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();

        Runtime rt = Runtime.getRuntime();

        StringBuilder sb = new StringBuilder();
        sb.append("## Memory\n");
        sb.append("### Heap\n");
        sb.append("- Used: ").append(formatBytes(heap.getUsed())).append("\n");
        sb.append("- Committed: ").append(formatBytes(heap.getCommitted())).append("\n");
        sb.append("- Max: ").append(formatBytes(heap.getMax())).append("\n");
        sb.append("- Usage: ").append(String.format("%.1f%%",
                (double) heap.getUsed() / heap.getMax() * 100)).append("\n");

        sb.append("### Non-Heap\n");
        sb.append("- Used: ").append(formatBytes(nonHeap.getUsed())).append("\n");
        sb.append("- Committed: ").append(formatBytes(nonHeap.getCommitted())).append("\n");

        sb.append("### Runtime\n");
        sb.append("- Free memory: ").append(formatBytes(rt.freeMemory())).append("\n");
        sb.append("- Total memory: ").append(formatBytes(rt.totalMemory())).append("\n");
        sb.append("- Max memory: ").append(formatBytes(rt.maxMemory())).append("\n");

        // 系统物理内存
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            sb.append("### Physical\n");
            sb.append("- Total: ").append(formatBytes(sunOs.getTotalMemorySize())).append("\n");
            sb.append("- Free: ").append(formatBytes(sunOs.getFreeMemorySize())).append("\n");
        }

        return sb.toString();
    }

    // ── Disk ──────────────────────────────────────────────

    private String getDiskInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("## Disk\n");

        try {
            for (FileStore store : FileSystems.getDefault().getFileStores()) {
                long total = store.getTotalSpace();
                long usable = store.getUsableSpace();
                if (total <= 0) continue;

                sb.append("- **").append(store.name()).append("** (")
                        .append(store.type()).append("): ");
                sb.append(formatBytes(total - usable)).append(" used / ")
                        .append(formatBytes(total)).append(" total (")
                        .append(String.format("%.1f%%", (double) (total - usable) / total * 100))
                        .append(" used)\n");
            }
        } catch (Exception e) {
            sb.append("- Error reading disk info: ").append(e.getMessage()).append("\n");
        }

        return sb.toString();
    }

    // ── JVM ──────────────────────────────────────────────

    private String getJvmInfo() {
        Runtime rt = Runtime.getRuntime();
        StringBuilder sb = new StringBuilder();
        sb.append("## JVM\n");
        sb.append("- Version: ").append(System.getProperty("java.version")).append("\n");
        sb.append("- Vendor: ").append(System.getProperty("java.vendor")).append("\n");
        sb.append("- VM Name: ").append(System.getProperty("java.vm.name")).append("\n");
        sb.append("- Uptime: ").append(
                ManagementFactory.getRuntimeMXBean().getUptime() / 1000).append(" seconds\n");
        sb.append("- Active threads: ").append(Thread.activeCount()).append("\n");
        sb.append("- Available processors: ").append(rt.availableProcessors()).append("\n");
        sb.append("- PID: ").append(ProcessHandle.current().pid()).append("\n");
        return sb.toString();
    }

    // ── All ──────────────────────────────────────────────

    private String getAllInfo() {
        StringJoiner sj = new StringJoiner("\n");
        sj.add(getCpuInfo());
        sj.add(getMemoryInfo());
        sj.add(getDiskInfo());
        sj.add(getJvmInfo());
        return sj.toString();
    }

    // ── Util ──────────────────────────────────────────────

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
