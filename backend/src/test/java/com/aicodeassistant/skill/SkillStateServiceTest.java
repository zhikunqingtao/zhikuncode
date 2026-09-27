package com.aicodeassistant.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

class SkillStateServiceTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultsNewSkillsToEnabledAndRestoresChoicesAcrossRestart() throws Exception {
        Path file = directory.resolve("nested/skill-states.json");
        SkillStateService state = new SkillStateService(mapper, file.toString());
        assertThat(state.isEnabled("existing")).isTrue();
        assertThat(state.getStateError()).isNull();
        assertThat(Files.exists(file)).isFalse();
        state.setEnabled("Existing", false);
        SkillStateService restarted = new SkillStateService(mapper, file.toString());
        assertThat(restarted.isEnabled("EXISTING")).isFalse();
        assertThat(restarted.isEnabled("new-skill")).isTrue();
        restarted.setEnabled("existing", true);
        assertThat(new SkillStateService(mapper, file.toString()).isEnabled("existing")).isTrue();
        assertThat(mapper.readTree(file.toFile()).path("disabledSkills")).isEmpty();
        try (var files = Files.list(file.getParent())) {
            assertThat(files.toList()).containsExactly(file);
        }
    }

    @Test
    void concurrentSavesDoNotLoseChoices() throws Exception {
        Path file = directory.resolve("states.json");
        SkillStateService state = new SkillStateService(mapper, file.toString());
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 16; i++) {
                String id = "skill-" + i;
                tasks.add(() -> { state.setEnabled(id, false); return null; });
            }
            for (var result : pool.invokeAll(tasks)) result.get();
        }
        SkillStateService restarted = new SkillStateService(mapper, file.toString());
        for (int i = 0; i < 16; i++) assertThat(restarted.isEnabled("skill-" + i)).isFalse();
    }

    @Test
    void failedSaveLeavesPriorRuntimeStateAndPriorFileUntouched() throws Exception {
        Path parent = Files.createDirectory(directory.resolve("settings"));
        Path file = parent.resolve("states.json");
        SkillStateService state = new SkillStateService(mapper, file.toString());
        state.setEnabled("first", false);
        String saved = Files.readString(file);
        Path movedParent = directory.resolve("saved-settings");
        Files.move(parent, movedParent);
        Files.writeString(parent, "blocks settings directory creation");

        assertThatThrownBy(() -> state.setEnabled("second", false))
                .isInstanceOf(SkillStateService.SettingsSaveException.class).hasMessageContaining("原有状态保持不变");
        assertThat(state.isEnabled("first")).isFalse();
        assertThat(state.isEnabled("second")).isTrue();
        assertThat(Files.readString(movedParent.resolve("states.json"))).isEqualTo(saved);
    }

    @Test
    void malformedStateKeepsContextAvailableButDisablesAllSkillsAndCannotBeOverwritten() throws Exception {
        Path file = directory.resolve("states.json");
        String broken = "{broken-json";
        Files.writeString(file, broken);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SkillStateService.class, () -> new SkillStateService(mapper, file.toString()));
            context.refresh();
            assertThat(context.isActive()).isTrue();
            SkillStateService state = context.getBean(SkillStateService.class);
            assertThat(state.getStateError()).contains("所有 Skill 已暂时关闭", file.toString());
            assertThat(state.isEnabled("existing")).isFalse();
            assertThat(state.isEnabled("new-skill")).isFalse();
            for (boolean enabled : new boolean[] {true, false}) {
                assertThatThrownBy(() -> state.setEnabled("existing", enabled))
                        .isInstanceOf(SkillStateService.SettingsUnavailableException.class);
            }
            assertThat(Files.readString(file)).isEqualTo(broken);

            // Repair does not implicitly remove the protection; a restart reloads the known-good file.
            Files.writeString(file, "{\"version\":1,\"disabledSkills\":[\"existing\"]}");
            assertThat(state.isEnabled("new-skill")).isFalse();
            SkillStateService restarted = new SkillStateService(mapper, file.toString());
            assertThat(restarted.getStateError()).isNull();
            assertThat(restarted.isEnabled("existing")).isFalse();
            assertThat(restarted.isEnabled("new-skill")).isTrue();
        }
    }

    @Test
    void unreadableStatePathAlsoFailsClosedWithoutReplacingIt() throws Exception {
        Path file = Files.createDirectory(directory.resolve("states.json"));
        SkillStateService state = new SkillStateService(mapper, file.toString());
        assertThat(state.getStateError()).contains("无法读取");
        assertThat(state.isEnabled("new-skill")).isFalse();
        assertThatThrownBy(() -> state.setEnabled("new-skill", true))
                .isInstanceOf(SkillStateService.SettingsUnavailableException.class);
        assertThat(Files.isDirectory(file)).isTrue();
    }

    @Test
    void emptyOrUnsupportedStateFormatsRemainReadOnly() throws Exception {
        Path file = directory.resolve("states.json");
        for (String broken : new String[] {"", "{}", "{\"version\":2,\"disabledSkills\":[]}",
                "{\"version\":1,\"disabledSkills\":[null]}"}) {
            Files.writeString(file, broken);
            SkillStateService state = new SkillStateService(mapper, file.toString());
            assertThat(state.getStateError()).isNotNull();
            assertThat(state.isEnabled("new-skill")).isFalse();
            assertThatThrownBy(() -> state.setEnabled("new-skill", true))
                    .isInstanceOf(SkillStateService.SettingsUnavailableException.class);
            assertThat(Files.readString(file)).isEqualTo(broken);
        }
    }
}
