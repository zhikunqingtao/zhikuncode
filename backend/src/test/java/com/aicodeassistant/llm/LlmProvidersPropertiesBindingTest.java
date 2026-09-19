package com.aicodeassistant.llm;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmProvidersPropertiesBindingTest {
    @Test
    void strategyDefaultsToRoundRobinAndBindsExplicitPriorityFailover() {
        var defaultValues = Map.<String, Object>of(
                "provider.api-key", "a,b",
                "provider.base-url", "https://example.test",
                "provider.default-model", "m",
                "provider.models[0]", "m");
        var priorityValues = Map.<String, Object>of(
                "provider.api-key", "a,b",
                "provider.base-url", "https://zenmux.test",
                "provider.default-model", "m",
                "provider.models[0]", "m",
                "provider.key-selection-strategy", "PRIORITY_FAILOVER");

        StandardEnvironment defaultEnvironment = new StandardEnvironment();
        defaultEnvironment.getPropertySources().addFirst(
                new MapPropertySource("test", defaultValues));
        StandardEnvironment priorityEnvironment = new StandardEnvironment();
        priorityEnvironment.getPropertySources().addFirst(
                new MapPropertySource("test", priorityValues));

        var defaultConfig = Binder.get(defaultEnvironment)
                .bind("provider", LlmProvidersProperties.ProviderConfig.class)
                .orElseThrow(() -> new AssertionError("default provider did not bind"));
        var priorityConfig = Binder.get(priorityEnvironment)
                .bind("provider", LlmProvidersProperties.ProviderConfig.class)
                .orElseThrow(() -> new AssertionError("priority provider did not bind"));
        assertThat(defaultConfig.keySelectionStrategy())
                .isEqualTo(ApiKeyRotationManager.KeySelectionStrategy.ROUND_ROBIN);
        assertThat(priorityConfig.keySelectionStrategy())
                .isEqualTo(ApiKeyRotationManager.KeySelectionStrategy.PRIORITY_FAILOVER);
    }
}
