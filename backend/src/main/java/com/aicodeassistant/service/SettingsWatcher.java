package com.aicodeassistant.service;

import com.aicodeassistant.config.ClaudeMdLoader;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;

/**
 * 配置文件变更监控 — 对齐原版 settings watcher。
 * 监视 CLAUDE.md 和配置文件变更，自动触发重载。
 */
@Service
public class SettingsWatcher {

    private static final Logger log = LoggerFactory.getLogger(SettingsWatcher.class);

    private WatchService watchService;
    private final ConfigService configService;
    private final ClaudeMdLoader claudeMdLoader;

    public SettingsWatcher(ConfigService configService, ClaudeMdLoader claudeMdLoader) {
        this.configService = configService;
        this.claudeMdLoader = claudeMdLoader;
    }

    @PostConstruct
    public void init() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            Path configDir = Path.of(System.getProperty("user.home"), ".claude");
            if (Files.isDirectory(configDir)) {
                configDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE);
                log.info("SettingsWatcher initialized, watching: {}", configDir);
            } else {
                log.debug("Config directory does not exist, skipping watch: {}", configDir);
                return;
            }
            Thread.ofVirtual().name("settings-watcher").start(this::watchLoop);
        } catch (IOException e) {
            log.warn("Failed to initialize SettingsWatcher: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.debug("Error closing WatchService: {}", e.getMessage());
            }
        }
    }

    private void watchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changed = (Path) event.context();
                    if (changed.toString().endsWith(".md") || changed.toString().endsWith(".yml")) {
                        log.info("Settings file changed: {}", changed);
                        claudeMdLoader.clearCache();
                        configService.reload();
                    }
                }
                key.reset();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            }
        }
    }
}
