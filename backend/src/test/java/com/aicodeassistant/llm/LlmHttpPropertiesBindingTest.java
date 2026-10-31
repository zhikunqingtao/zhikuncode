package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmHttpPropertiesBindingTest {

    private static final String WRITE_TIMEOUT_ENV = "LLM_HTTP_WRITETIMEOUTSECONDS";
    private static final Map<String, Object> APPLICATION_DEFAULTS = Map.of(
            "llm.http.pool.max-idle-connections", 5,
            "llm.http.pool.keep-alive-seconds", 30,
            "llm.http.connect-timeout-seconds", 10,
            "llm.http.write-timeout-seconds", 10,
            "llm.http.retry-on-failure", true);

    @Test
    void absentConfigurationKeepsPublicDefaults() {
        var properties = bind(Map.of(), Map.of());

        assertThat(properties.writeTimeoutSeconds()).isEqualTo(10);
        assertUnchangedHttpSettings(properties);
    }

    @Test
    void systemEnvironmentOverridesApplicationWriteTimeoutAndConfiguresClient() {
        var properties = bind(Map.of(WRITE_TIMEOUT_ENV, "60"), APPLICATION_DEFAULTS);

        assertThat(properties.writeTimeoutSeconds()).isEqualTo(60);
        assertUnchangedHttpSettings(properties);
        assertClientTimeouts(properties, 60_000);
    }

    @Test
    void systemEnvironmentCanRestoreTenSecondWriteTimeout() {
        var properties = bind(Map.of(WRITE_TIMEOUT_ENV, "10"),
                Map.of("llm.http.write-timeout-seconds", 60));

        assertThat(properties.writeTimeoutSeconds()).isEqualTo(10);
        assertUnchangedHttpSettings(properties);
        assertClientTimeouts(properties, 10_000);
    }

    private static LlmHttpProperties bind(Map<String, Object> environmentVariables,
                                          Map<String, Object> applicationProperties) {
        var environment = new StandardEnvironment();
        var sources = environment.getPropertySources();
        // Isolate the test from the developer machine while retaining Spring Boot's
        // real system-environment name mapping and precedence over application values.
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        environmentVariables));
        sources.addLast(new MapPropertySource("application", applicationProperties));
        return Binder.get(environment).bindOrCreate("llm.http", LlmHttpProperties.class);
    }

    private static void assertUnchangedHttpSettings(LlmHttpProperties properties) {
        assertThat(properties.connectTimeoutSeconds()).isEqualTo(10);
        assertThat(properties.retryOnFailure()).isTrue();
        assertThat(properties.pool().maxIdleConnections()).isEqualTo(5);
        assertThat(properties.pool().keepAliveSeconds()).isEqualTo(30);
    }

    private static void assertClientTimeouts(LlmHttpProperties properties, int writeTimeoutMillis) {
        // Constructing the provider makes no requests and needs no real credentials.
        var provider = new OpenAiCompatibleProvider(
                "binding-test", new ObjectMapper(), properties,
                new ApiKeyRotationManager(List.of()), "", "https://example.test/v1",
                "test-model", List.of("test-model"));
        var client = (OkHttpClient) ReflectionTestUtils.getField(provider, "httpClient");

        assertThat(client).isNotNull();
        assertThat(client.writeTimeoutMillis()).isEqualTo(writeTimeoutMillis);
        assertThat(client.connectTimeoutMillis()).isEqualTo(10_000);
        assertThat(client.readTimeoutMillis()).isEqualTo(600_000);
        assertThat(client.retryOnConnectionFailure()).isTrue();
    }
}
