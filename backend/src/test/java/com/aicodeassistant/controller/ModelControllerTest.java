package com.aicodeassistant.controller;

import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.llm.ModelCapabilities;
import com.aicodeassistant.llm.ModelRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelControllerTest {

    @Test
    void preservesProviderModelIdWhenCapabilitiesAreUnknown() {
        LlmProviderRegistry providers = mock(LlmProviderRegistry.class);
        ModelRegistry models = mock(ModelRegistry.class);
        when(providers.listAvailableModels()).thenReturn(List.of("provider/new-model"));
        when(providers.getDefaultModel()).thenReturn("provider/new-model");
        when(models.getCapabilities("provider/new-model"))
                .thenReturn(ModelCapabilities.DEFAULT);

        ModelController.ModelListResponse body =
                new ModelController(providers, models).listModels(null).getBody();

        assertThat(body).isNotNull();
        assertThat(body.models()).singleElement().satisfies(model -> {
            assertThat(model.id()).isEqualTo("provider/new-model");
            assertThat(model.displayName()).isEqualTo("provider/new-model");
            assertThat(model.supportsImages()).isFalse();
        });
        assertThat(body.models()).noneMatch(model -> "unknown".equals(model.id()));
    }

    @Test
    void validatesQueriesAgainstConfiguredProviders() {
        LlmProviderRegistry providers = mock(LlmProviderRegistry.class);
        ModelRegistry models = mock(ModelRegistry.class);
        when(providers.supportsModel("retired-model")).thenReturn(false);

        ModelController controller = new ModelController(providers, models);

        assertThatThrownBy(() -> controller.listModels("retired-model"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid model: retired-model");
    }
}
