package com.aicodeassistant.config.oss;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class OssPublishPropertiesTest {

    @Test
    void disabledByDefault() {
        assertThatThrownBy(new OssPublishProperties()::requireReady)
                .isInstanceOf(OssPublishProperties.OssConfigurationException.class)
                .hasMessage("OSS_PUBLISHING_DISABLED");
    }

    @Test
    void validEcsRoleConfigurationIsAccepted() {
        assertThatCode(() -> valid().requireReady(Map.of())).doesNotThrowAnyException();
    }

    @Test
    void endpointMustMatchConfiguredRegionAndUseHttps() {
        OssPublishProperties properties = valid();
        properties.setEndpoint("http://oss-cn-beijing.aliyuncs.com");
        assertThatThrownBy(() -> properties.requireReady(Map.of())).hasMessage("OSS_ENDPOINT_INVALID");

        properties.setEndpoint("https://oss-cn-hangzhou.aliyuncs.com");
        assertThatThrownBy(() -> properties.requireReady(Map.of())).hasMessage("OSS_ENDPOINT_INVALID");
    }

    @Test
    void staticOssCredentialsAreAlwaysRejected() {
        assertThatThrownBy(() -> OssPublishProperties.rejectStaticCredentials(
                Map.of("OSS_ACCESS_KEY_ID", "forbidden")))
                .hasMessage("OSS_CREDENTIAL_SOURCE_FORBIDDEN");
    }

    @Test
    void localDefaultCredentialChainDoesNotRequireEcsRoleAndAllowsStandardVariables() {
        OssPublishProperties properties = valid();
        properties.setEcsRoleName("");

        assertThatCode(() -> properties.requireReady(Map.of(
                "ALIBABA_CLOUD_ACCESS_KEY_ID", "test-id",
                "ALIBABA_CLOUD_ACCESS_KEY_SECRET", "test-secret")))
                .doesNotThrowAnyException();
        assertThat(properties.resolvedCredentialMode())
                .isEqualTo(OssPublishProperties.CredentialMode.DEFAULT_CHAIN);
    }

    @Test
    void autoModePrefersCompleteLocalEnvironmentCredentialsOverNamedEcsRole() {
        OssPublishProperties properties = valid();
        Map<String, String> localCredentials = Map.of(
                "ALIBABA_CLOUD_ACCESS_KEY_ID", "test-id",
                "ALIBABA_CLOUD_ACCESS_KEY_SECRET", "test-secret");

        assertThat(properties.resolvedCredentialMode(localCredentials))
                .isEqualTo(OssPublishProperties.CredentialMode.DEFAULT_CHAIN);
        assertThat(properties.resolvedCredentialMode(Map.of()))
                .isEqualTo(OssPublishProperties.CredentialMode.ECS_RAM_ROLE);
    }

    @Test
    void autoModeDoesNotSelectIncompleteLocalEnvironmentCredentials() {
        OssPublishProperties properties = valid();

        assertThat(properties.resolvedCredentialMode(Map.of(
                "ALIBABA_CLOUD_ACCESS_KEY_ID", "test-id")))
                .isEqualTo(OssPublishProperties.CredentialMode.ECS_RAM_ROLE);
    }

    @Test
    void explicitEcsModeStillRequiresRole() {
        OssPublishProperties properties = valid();
        properties.setEcsRoleName("");
        properties.setCredentialMode("ecs");

        assertThatThrownBy(() -> properties.requireReady(Map.of()))
                .hasMessage("OSS_ECS_ROLE_REQUIRED");
    }

    @Test
    void onlyConfiguredClipboardPrefixIsTrusted() {
        OssPublishProperties properties = valid();
        String trusted = "https://test-artifacts.oss-cn-beijing.aliyuncs.com/"
                + "zhikuncode-artifacts/clipboard/session/artifact/image.png";

        assertThat(properties.isTrustedClipboardImageUrl(trusted)).isTrue();
        assertThat(properties.isTrustedClipboardImageUrl(
                "https://test-artifacts.oss-cn-beijing.aliyuncs.com/zhikuncode-artifacts/other/image.png"))
                .isFalse();
        assertThat(properties.isTrustedClipboardImageUrl(
                "https://attacker.example/zhikuncode-artifacts/clipboard/image.png"))
                .isFalse();
        assertThat(properties.isTrustedClipboardImageUrl(trusted + "?signature=unexpected"))
                .isFalse();
        assertThat(properties.isTrustedClipboardImageUrl(
                "https://test-artifacts.oss-cn-beijing.aliyuncs.com/"
                        + "zhikuncode-artifacts/clipboard/../private/image.png"))
                .isFalse();
    }

    static OssPublishProperties valid() {
        OssPublishProperties properties = new OssPublishProperties();
        properties.setEnabled(true);
        properties.setEndpoint("https://oss-cn-beijing.aliyuncs.com");
        properties.setRegion("cn-beijing");
        properties.setBucket("test-artifacts");
        properties.setPrefix("zhikuncode-artifacts");
        properties.setEcsRoleName("TestEcsRole");
        return properties;
    }
}
