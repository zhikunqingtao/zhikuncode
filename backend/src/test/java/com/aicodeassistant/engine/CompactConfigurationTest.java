package com.aicodeassistant.engine;

import com.aicodeassistant.llm.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompactConfigurationTest {
    @Test void defaultsAndEnvironmentBindingsAndDockerPropagation() throws Exception {
        var env = new MockEnvironment();
        new YamlPropertySourceLoader().load("application",new ClassPathResource("application.yml"))
                .forEach(s -> env.getPropertySources().addLast(s));
        var defaults = new CompactConfiguration(env).settings();
        assertEquals(new CompactConfiguration.Settings(true,"dashscope-token-plan","deepseek-v4.1-flash",
                SummaryRequest.ThinkingMode.MAX,8192,4096,90000,null),defaults);
        Map<String,Object> overrides = Map.of("LLM_COMPACT_LLM_ENABLED","true","LLM_COMPACT_PROVIDER","selected",
                "LLM_COMPACT_MODEL","qwen3.8-flash","LLM_COMPACT_THINKING_MODE","off",
                "LLM_COMPACT_MAX_COMPLETION_TOKENS","6000","LLM_COMPACT_MAX_SUMMARY_TOKENS","2000","LLM_COMPACT_TIMEOUT_MS","45000");
        env.getPropertySources().addFirst(new MapPropertySource("explicit-environment",overrides));
        assertEquals(new CompactConfiguration.Settings(true,"selected","qwen3.8-flash",SummaryRequest.ThinkingMode.OFF,
                6000,2000,45000,null),new CompactConfiguration(env).settings());
        String docker = Files.readString(Path.of("../docker-compose.yml"));
        String example = Files.readString(Path.of("../.env.example"));
        for (String key : overrides.keySet()) { assertTrue(docker.contains(key+":"),key); assertTrue(example.contains(key+"="),key); }
    }
    @Test void invalidOptionalConfigNeverThrowsOrSilentlyDefaults() {
        for (String[] setting : List.of(new String[]{"llm-enabled","maybe"},new String[]{"thinking-mode","high"},
                new String[]{"max-completion-tokens","abc"},new String[]{"max-summary-tokens","5000"},
                new String[]{"timeout-ms","0"},new String[]{"timeout-ms","99999999999999999999999"},new String[]{"provider",""},
                new String[]{"model","unknown-model"})) {
            var env = new MockEnvironment().withProperty("app.compact."+setting[0],setting[1]);
            var config = assertDoesNotThrow(() -> new CompactConfiguration(env).settings());
            assertFalse(config.enabled()); assertEquals("invalid_summary_configuration",config.unavailable());
        }
        assertEquals("summary_disabled",new CompactConfiguration(new MockEnvironment()
                .withProperty("app.compact.llm-enabled","false").withProperty("app.compact.timeout-ms","invalid")).settings().unavailable());
    }
    @Test void exactProviderRoutingAndStrictCapacityResolution() {
        var a=mock(LlmProvider.class);var b=mock(LlmProvider.class);
        when(a.getProviderName()).thenReturn("other-billing-endpoint");when(b.getProviderName()).thenReturn("selected");
        when(a.getSupportedModels()).thenReturn(List.of("qwen3.7-plus"));when(b.getSupportedModels()).thenReturn(List.of("qwen3.7-plus"));
        when(b.getModelCapabilities(any())).thenReturn(ModelCapabilities.DEFAULT);
        var registry=new LlmProviderRegistry(List.of(a,b),new MockEnvironment());
        assertSame(b,registry.findProviderByName("selected").orElseThrow());
        assertTrue(registry.findProviderByName("select").isEmpty());
        var properties=new ModelCapabilitiesProperties();var models=new ModelRegistry(registry,properties);
        assertTrue(models.findExplicitCapabilities("qwen3.7-plus",b).isEmpty());
        verify(a,never()).getModelCapabilities(any());
        var override=new ModelCapabilitiesProperties.Override();override.setTokenCharRatio(2.0);
        properties.getCapabilities().put("qwen3.7-plus",override);
        assertTrue(models.findExplicitCapabilities("qwen3.7-plus",b).isEmpty());
        override.setContextWindow(128000);override.setOutputMaxTokens(16384);
        assertEquals(128000,models.findExplicitCapabilities("qwen3.7-plus",b).orElseThrow().contextWindow());
    }
}
