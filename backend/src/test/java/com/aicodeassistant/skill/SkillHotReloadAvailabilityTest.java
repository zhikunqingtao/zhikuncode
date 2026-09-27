package com.aicodeassistant.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SkillHotReloadAvailabilityTest {
    @TempDir Path project;

    @Test
    void realFileWatcherRefreshesDefinitionsWithoutResettingDisabledState() throws Exception {
        Path directory = Files.createDirectories(project.resolve(".zhikun/skills"));
        Path existing = directory.resolve("watched.md");
        Files.writeString(existing, markdown("old-alias", "original body"));
        Files.writeString(directory.resolve(" .md"), markdown("invalid", "invalid body"));
        Path statePath = project.resolve("skill-states.json");
        SkillStateService states = new SkillStateService(new ObjectMapper(), statePath.toString());
        SkillRegistry registry = new SkillRegistry(states);
        try {
            registry.getProjectSkills(project.toString()).forEach(registry::register);
            registry.setEnabled("watched", false);
            String savedSettings = Files.readString(statePath);
            registry.startWatching(project.toString());

            Files.writeString(existing, markdown("updated-alias", "updated body"));
            Files.writeString(directory.resolve("new-skill.md"), markdown("new-alias", "new body"));
            Files.writeString(directory.resolve("  .md"), markdown("invalid-reload", "invalid body"));

            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(registry.getManagedSkill("watched").content()).isEqualTo("updated body");
                assertThat(registry.resolve("new-skill")).isNotNull();
            });
            assertThat(registry.resolve("watched")).isNull();
            assertThat(registry.resolve("UPDATED-ALIAS")).isNull();
            assertThat(registry.getEnabledSkills()).extracting(SkillDefinition::id).containsExactly("new-skill");
            assertThat(Files.readString(statePath)).isEqualTo(savedSettings);
            assertThat(new SkillStateService(new ObjectMapper(), statePath.toString()).isEnabled("watched")).isFalse();

            registry.setEnabled("watched", true);
            assertThat(registry.resolve("updated-alias").content()).isEqualTo("updated body");
        } finally {
            registry.stopWatching();
        }
    }

    private String markdown(String alias, String content) {
        return "---\nname: " + alias + "\ndescription: Watch fixture\n---\n" + content;
    }
}
