package com.aicodeassistant.config;

import com.aicodeassistant.config.oss.OssPublishProperties;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityConfigCorsTest {

    @Test
    void allowsPatchForMcpServiceToggles() {
        CorsConfigurationSource source = new SecurityConfig().corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "OPTIONS", "/api/mcp/services/context7/toggle");

        CorsConfiguration configuration = source.getCorsConfiguration(request);

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedMethods()).contains("PATCH");
    }

    @Test
    void defaultCspResponseKeepsExistingMediaSources() throws Exception {
        String csp = responseCsp("");
        assertThat(csp).contains("img-src 'self' data: blob: https://*.aliyuncs.com;")
                .contains("media-src 'self' blob: https://*.aliyuncs.com http://*.aliyuncs.com;");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void customDomainIsAllowedOnlyForImagesAndMediaInActualResponseHeader(boolean enabled) throws Exception {
        String csp = responseCsp("https://files.example.com/", enabled, false);
        assertThat(csp).contains("img-src 'self' data: blob: https://*.aliyuncs.com https://files.example.com;")
                .contains("media-src 'self' blob: https://*.aliyuncs.com http://*.aliyuncs.com https://files.example.com;");
        assertThat(Arrays.stream(csp.split(";")).filter(value -> value.contains("files.example.com")))
                .allSatisfy(value -> assertThat(value.trim().startsWith("img-src ")
                        || value.trim().startsWith("media-src ")).isTrue())
                .hasSize(2);
        CorsConfiguration cors = new SecurityConfig().corsConfigurationSource()
                .getCorsConfiguration(new MockHttpServletRequest("GET", "/test"));
        assertThat(cors.getAllowedOrigins()).doesNotContain("https://files.example.com");
    }

    @ParameterizedTest
    @MethodSource("invalidOssConfigurations")
    void invalidDisabledOssConfigurationDoesNotPreventStartupOrChangeCsp(
            String publicOrigin, boolean previewEnabled, String errorCode) throws Exception {
        assertThat(responseCsp(publicOrigin, false, previewEnabled)).isEqualTo(responseCsp(""));
    }

    @ParameterizedTest
    @MethodSource("invalidOssConfigurations")
    void invalidEnabledOssConfigurationStillPreventsStartup(
            String publicOrigin, boolean previewEnabled, String errorCode) {
        assertThatThrownBy(() -> responseCsp(publicOrigin, true, previewEnabled))
                .hasRootCauseInstanceOf(OssPublishProperties.OssConfigurationException.class)
                .hasRootCauseMessage(errorCode);
    }

    private static Stream<Arguments> invalidOssConfigurations() {
        return Stream.of(
                Arguments.of("http://files.example.com", false, "OSS_PUBLIC_BASE_URL_INVALID"),
                Arguments.of("https://files.example.com/; script-src *", false, "OSS_PUBLIC_BASE_URL_INVALID"),
                Arguments.of("", true, "OSS_PREVIEW_CUSTOM_DOMAIN_REQUIRED"),
                Arguments.of("https://test-artifacts.oss-cn-beijing.aliyuncs.com", true,
                        "OSS_PREVIEW_CUSTOM_DOMAIN_REQUIRED"));
    }

    private String responseCsp(String publicOrigin) throws Exception {
        return responseCsp(publicOrigin, false, false);
    }

    private String responseCsp(String publicOrigin, boolean enabled, boolean previewEnabled) throws Exception {
        try (AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("oss-test", Map.of(
                            "test.oss.public-origin", publicOrigin,
                            "test.oss.enabled", enabled,
                            "test.oss.preview-enabled", previewEnabled)));
            context.register(SecurityConfig.class, CspTestBeans.class);
            context.refresh();
            return MockMvcBuilders.standaloneSetup(new TestEndpoint())
                    .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                    .build().perform(get("/test").secure(true))
                    .andExpect(status().isOk()).andReturn().getResponse()
                    .getHeader("Content-Security-Policy");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CspTestBeans {
        @Bean
        OssPublishProperties ossPublishProperties(Environment environment) {
            OssPublishProperties properties = new OssPublishProperties();
            properties.setPublicBaseUrl(environment.getProperty("test.oss.public-origin", ""));
            properties.setEnabled(environment.getProperty("test.oss.enabled", Boolean.class, false));
            properties.setPreviewEnabled(environment.getProperty("test.oss.preview-enabled", Boolean.class, false));
            return properties;
        }
    }

    @RestController
    static class TestEndpoint {
        @GetMapping("/test")
        String test() { return "ok"; }
    }
}
