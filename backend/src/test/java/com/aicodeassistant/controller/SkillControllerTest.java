package com.aicodeassistant.controller;

import com.aicodeassistant.skill.SkillDefinition;
import com.aicodeassistant.skill.SkillRegistry;
import com.aicodeassistant.skill.SkillStateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SkillControllerTest {
    @TempDir Path directory;
    private SkillRegistry registry;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        registry = new SkillRegistry();
        configureMvc();
    }

    private void configureMvc() {
        registry.register(SkillDefinition.fromMarkdown("stable-id.md",
                "---\nname: DisplayAlias\ndescription: Example description\n---\nSKILL_BODY",
                SkillDefinition.SkillSource.PROJECT, "/example/stable-id.md"));
        mvc = MockMvcBuilders.standaloneSetup(new SkillController(registry))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach void close() { registry.stopWatching(); }

    @Test void managementKeepsDisabledDefinitionsWhileRuntimeEndpointsHideThem() throws Exception {
        mvc.perform(get("/api/skills/manage"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.enabledCount").value(1))
                .andExpect(jsonPath("$.stateError").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.skills[0].id").value("stable-id"))
                .andExpect(jsonPath("$.skills[0].name").value("DisplayAlias"));
        mvc.perform(patch("/api/skills/manage/stable-id/toggle").param("enabled", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get("/api/skills")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/skills/DisplayAlias")).andExpect(status().isNotFound());
        mvc.perform(get("/api/skills/stable-id")).andExpect(status().isNotFound());
        mvc.perform(get("/api/skills/detail/stable-id")).andExpect(status().isNotFound());
        mvc.perform(get("/api/skills/manage"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabledCount").value(0));
        mvc.perform(get("/api/skills/manage/stable-id"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.content").value("SKILL_BODY"))
                .andExpect(jsonPath("$.filePath").value("/example/stable-id.md"));
        mvc.perform(patch("/api/skills/manage/stable-id/toggle").param("enabled", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(get("/api/skills/DisplayAlias"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("SKILL_BODY"));
        mvc.perform(get("/api/skills/detail/DisplayAlias"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("SKILL_BODY"));
    }

    @Test void requiresExistingCanonicalIdAndEnabledParameter() throws Exception {
        mvc.perform(patch("/api/skills/manage/missing/toggle").param("enabled", "false"))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/skills/manage/DisplayAlias/toggle").param("enabled", "false"))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/skills/manage/stable-id/toggle"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/skills/manage/stable-id/toggle").param("enabled", "nonsense"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/skills/manage/stable-id"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test void saveFailureIsNotReportedAsSuccessfulToggle() throws Exception {
        registry.stopWatching();
        Path parent = Files.createDirectory(directory.resolve("settings"));
        registry = new SkillRegistry(new SkillStateService(new ObjectMapper(), parent.resolve("state.json").toString()));
        configureMvc();
        Files.delete(parent);
        Files.writeString(parent, "blocks settings directory creation");
        mvc.perform(patch("/api/skills/manage/stable-id/toggle").param("enabled", "false"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("SKILL_SETTINGS_SAVE_FAILED"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("原有状态保持不变")));
        mvc.perform(get("/api/skills/manage"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabledCount").value(1));
    }

    @Test void reservedManagementNameHasAnUnambiguousRuntimeDetailRoute() throws Exception {
        registry.register(SkillDefinition.fromMarkdown("manage.md", "MANAGE_SKILL_BODY",
                SkillDefinition.SkillSource.USER, "/example/manage.md"));
        mvc.perform(get("/api/skills/manage"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.skills").isArray());
        mvc.perform(get("/api/skills/detail/manage"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("manage"))
                .andExpect(jsonPath("$.content").value("MANAGE_SKILL_BODY"));
        registry.setEnabled("manage", false);
        mvc.perform(get("/api/skills/detail/manage")).andExpect(status().isNotFound());
        mvc.perform(get("/api/skills/manage/manage"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.content").value("MANAGE_SKILL_BODY"));
    }

    @Test void corruptedStateKeepsManagementReadableAndRejectsEverySaveWithoutOverwriting() throws Exception {
        registry.stopWatching();
        Path file = directory.resolve("damaged.json");
        String broken = "{broken-json";
        Files.writeString(file, broken);
        registry = new SkillRegistry(new SkillStateService(new ObjectMapper(), file.toString()));
        configureMvc();

        mvc.perform(get("/api/skills/manage"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabledCount").value(0))
                .andExpect(jsonPath("$.stateError").value(org.hamcrest.Matchers.containsString("所有 Skill 已暂时关闭")))
                .andExpect(jsonPath("$.skills[0].enabled").value(false));
        mvc.perform(get("/api/skills")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/skills/detail/stable-id")).andExpect(status().isNotFound());
        mvc.perform(get("/api/skills/manage/stable-id"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("SKILL_BODY"));
        for (String enabled : new String[] {"true", "false"}) {
            mvc.perform(patch("/api/skills/manage/stable-id/toggle").param("enabled", enabled))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error.code").value("SKILL_STATE_UNAVAILABLE"))
                    .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("修复文件后重启后端")));
        }
        org.assertj.core.api.Assertions.assertThat(Files.readString(file)).isEqualTo(broken);
    }
}
