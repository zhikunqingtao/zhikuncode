package com.aicodeassistant.tool.search;

import com.aicodeassistant.mcp.McpClientManager;
import com.aicodeassistant.mcp.McpConnectionStatus;
import com.aicodeassistant.mcp.McpServerConnection;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Real YAML and @Value injection, isolated fake environment, loopback HTTP only. */
class WebSearchConfigurationRegressionTest {
    static Stream<Arguments> credentials() {
        return Stream.of(
                Arguments.of("missing dedicated falls back", null, "dummy-llm", "dummy-llm"),
                Arguments.of("empty dedicated falls back", "", "dummy-llm", "dummy-llm"),
                Arguments.of("blank dedicated and LLM list", "   ", " dummy-a ,dummy-b,dummy-a", "dummy-a"),
                Arguments.of("dedicated trims and wins", " dummy-search ", "dummy-llm", "dummy-search"),
                Arguments.of("dedicated list skips empty and duplicates", ", dummy-search-a,,dummy-search-b,dummy-search-a", "dummy-llm", "dummy-search-a"),
                Arguments.of("comma-only dedicated does not fall back", ",", "dummy-llm", null),
                Arguments.of("comma-space dedicated does not fall back", " , , ", "dummy-llm", null),
                Arguments.of("both missing disables", null, null, null),
                Arguments.of("both blank disables", "  ", "  ", null),
                Arguments.of("empty fallback list disables", null, " , , ", null),
                Arguments.of("fallback list keeps first", null, "dummy-a,dummy-a,dummy-b", "dummy-a"),
                Arguments.of("dedicated works without LLM", "dummy-search", null, "dummy-search")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("credentials")
    void credentialsFlowThroughYamlAndValueIntoHttp(String label, String dedicated,
                                                   String llm, String expectedKey) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("WEB_SEARCH_BACKEND", "deepseek");
            variables.put("WEB_SEARCH_DEEPSEEK_BASE_URL", server.url("/").toString());
            if (dedicated != null) variables.put("WEB_SEARCH_DEEPSEEK_API_KEY", dedicated);
            if (llm != null) variables.put("LLM_PROVIDER_DEEPSEEK_API_KEY", llm);
            try (var context = context(variables, unavailableMcp())) {
                var backend = context.getBean(WebSearchBackendFactory.class).createBackend();
                if (expectedKey == null) {
                    assertInstanceOf(DisabledSearchBackend.class, backend);
                    assertFalse(backend.isAvailable());
                    assertEquals(0, server.getRequestCount());
                } else {
                    assertInstanceOf(DeepSeekSearchBackend.class, backend);
                    assertTrue(backend.isAvailable());
                    // Two calls prove selection is fixed; no key rotation is introduced.
                    for (int i = 0; i < 2; i++) {
                        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                                .setBody("{\"results\":[]}"));
                        assertTrue(backend.search("configuration probe", null).isEmpty());
                        var request = server.takeRequest(3, TimeUnit.SECONDS);
                        assertNotNull(request);
                        assertEquals("/v1/tools/search", request.getPath());
                        assertEquals("Bearer " + expectedKey, request.getHeader("Authorization"));
                    }
                    assertEquals(2, server.getRequestCount());
                }
            }
        }
    }

    @Test
    void missingBackendKeepsAutoAndDoesNotActivatePaidSearch() throws Exception {
        try (var context = context(Map.of("LLM_PROVIDER_DEEPSEEK_API_KEY", "dummy-llm"), unavailableMcp())) {
            assertEquals("auto", context.getEnvironment().getProperty("web-search.backend"));
            assertInstanceOf(DisabledSearchBackend.class,
                    context.getBean(WebSearchBackendFactory.class).createBackend());
        }
    }

    @Test
    void autoStillPrefersConnectedMcp() throws Exception {
        var mcp = mock(McpClientManager.class);
        var connection = mock(McpServerConnection.class);
        when(mcp.getConnection(anyString())).thenReturn(Optional.of(connection));
        when(connection.getStatus()).thenReturn(McpConnectionStatus.CONNECTED);
        try (var context = context(Map.of("LLM_PROVIDER_DEEPSEEK_API_KEY", "dummy-llm"), mcp)) {
            assertInstanceOf(McpWebSearchBackend.class,
                    context.getBean(WebSearchBackendFactory.class).createBackend());
            verify(connection, never()).callTool(anyString(), anyMap(), anyLong());
        }
    }

    @Test
    void disabledStillIgnoresConfiguredCredentials() throws Exception {
        try (var context = context(Map.of("WEB_SEARCH_BACKEND", "disabled",
                "WEB_SEARCH_DEEPSEEK_API_KEY", "dummy-search"), unavailableMcp())) {
            assertInstanceOf(DisabledSearchBackend.class,
                    context.getBean(WebSearchBackendFactory.class).createBackend());
        }
    }

    @Test
    void missingBaseUrlKeepsOfficialDefaultWithoutMakingRequests() throws Exception {
        try (var context = context(Map.of("WEB_SEARCH_BACKEND", "deepseek",
                "WEB_SEARCH_DEEPSEEK_API_KEY", "dummy-search"), unavailableMcp())) {
            assertEquals("https://api.deepseek.com",
                    context.getEnvironment().getProperty("web-search.deepseek-base-url"));
            assertInstanceOf(DeepSeekSearchBackend.class,
                    context.getBean(WebSearchBackendFactory.class).createBackend());
            // Deliberately do not call search: this case has an official URL.
        }
    }

    private static McpClientManager unavailableMcp() {
        var mcp = mock(McpClientManager.class);
        when(mcp.getConnection(anyString())).thenReturn(Optional.empty());
        return mcp;
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> fakeVariables,
                                                               McpClientManager mcp) throws Exception {
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("fakeEnvironment", fakeVariables));
        new YamlPropertySourceLoader().load("applicationYaml", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        assertFalse(environment.getPropertySources().contains("systemEnvironment"));
        assertFalse(environment.getPropertySources().contains("systemProperties"));

        var context = new AnnotationConfigApplicationContext();
        context.setEnvironment(environment);
        context.registerBean(PropertySourcesPlaceholderConfigurer.class, () -> {
            var configurer = new PropertySourcesPlaceholderConfigurer();
            configurer.setEnvironment(environment);
            return configurer;
        });
        context.registerBean(McpClientManager.class, () -> mcp);
        context.registerBean(WebSearchBackendFactory.class);
        try {
            context.refresh();
            return context;
        } catch (RuntimeException | Error failure) {
            context.close();
            throw failure;
        }
    }
}
