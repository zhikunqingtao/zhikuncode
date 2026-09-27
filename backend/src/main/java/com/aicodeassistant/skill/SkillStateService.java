package com.aicodeassistant.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Backend-wide skill choices. Missing entries, including newly discovered skills, are enabled. */
@Service
public class SkillStateService {
    private static final Logger log = LoggerFactory.getLogger(SkillStateService.class);
    private final ObjectMapper objectMapper;
    private final Path statePath;
    private volatile Set<String> disabledSkills = Set.of();
    private String stateError;

    /** Isolated, in-memory state for standalone registries and unit tests. */
    public SkillStateService() {
        objectMapper = new ObjectMapper();
        statePath = null;
    }

    @Autowired
    public SkillStateService(ObjectMapper objectMapper,
            @Value("${skill.state-path:${user.home}/.zhikun/skill-states.json}") String statePath) {
        this.objectMapper = objectMapper;
        this.statePath = Path.of(statePath).toAbsolutePath().normalize();
        load();
    }

    public boolean isEnabled(String id) {
        return stateError == null && !disabledSkills.contains(canonicalId(id));
    }

    /** A damaged settings file disables skills without making the rest of the backend unavailable. */
    public String getStateError() {
        return stateError;
    }

    /** Serialize updates, persist first, then publish one immutable snapshot to readers. */
    public synchronized void setEnabled(String id, boolean enabled) {
        if (stateError != null) throw new SettingsUnavailableException(stateError);
        String canonicalId = canonicalId(id);
        Set<String> updated = new HashSet<>(disabledSkills);
        if (enabled) updated.remove(canonicalId);
        else updated.add(canonicalId);
        if (updated.equals(disabledSkills)) return;
        persist(updated);
        disabledSkills = Set.copyOf(updated);
    }

    static String canonicalId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Skill id is required");
        return id.toLowerCase(Locale.ROOT);
    }

    private void load() {
        try {
            if (Files.notExists(statePath)) return;
            JsonNode root = objectMapper.readTree(statePath.toFile());
            if (root == null || !root.isObject() || !root.path("version").isIntegralNumber()
                    || root.path("version").intValue() != 1 || !root.path("disabledSkills").isArray()) {
                throw new IOException("Invalid skill state format");
            }
            Set<String> loaded = new HashSet<>();
            for (JsonNode id : root.path("disabledSkills")) {
                if (!id.isTextual() || id.textValue().isBlank()) {
                    throw new IOException("Invalid disabled skill id");
                }
                loaded.add(canonicalId(id.textValue()));
            }
            disabledSkills = Set.copyOf(loaded);
        } catch (IOException | SecurityException e) {
            // Preserve the file and fail closed only for skills, keeping the backend usable.
            stateError = "技能设置文件损坏或无法读取，所有 Skill 已暂时关闭，无法保存设置。请修复文件后重启后端：" + statePath;
            log.error("Unable to read skill settings at {}; all skills are disabled and settings are read-only",
                    statePath, e);
        }
    }

    private void persist(Set<String> updated) {
        if (statePath == null) return;
        Path temporary = null;
        try {
            Files.createDirectories(statePath.getParent());
            temporary = Files.createTempFile(statePath.getParent(), ".skill-states-", ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(),
                    Map.of("version", 1, "disabledSkills", new TreeSet<>(updated)));
            Files.move(temporary, statePath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | SecurityException e) {
            log.warn("Unable to save skill settings at {}; the previous state remains active", statePath, e);
            throw new SettingsSaveException("保存技能设置失败，原有状态保持不变。请检查配置目录权限和磁盘空间后重试。", e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException | SecurityException ignored) { /* The existing settings file remains authoritative. */ }
            }
        }
    }

    public static final class SettingsUnavailableException extends IllegalStateException {
        public SettingsUnavailableException(String message) { super(message); }
    }

    public static final class SettingsSaveException extends IllegalStateException {
        public SettingsSaveException(String message, Throwable cause) { super(message, cause); }
    }
}
