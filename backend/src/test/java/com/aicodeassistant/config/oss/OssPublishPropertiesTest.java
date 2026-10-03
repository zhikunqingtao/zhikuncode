package com.aicodeassistant.config.oss;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void defaultPublicationUrlAndPreviewBehaviorAreUnchanged() {
        OssPublishProperties properties = valid();
        assertThat(properties.publicBaseUrl()).isEmpty();
        assertThat(properties.isPreviewEnabled()).isFalse();
        assertThat(properties.publicUrl("reports/report.html"))
                .isEqualTo("https://test-artifacts.oss-cn-beijing.aliyuncs.com/reports/report.html");
    }

    @Test
    void customOriginIsNormalizedWithoutChangingOrDoubleEncodingObjectKey() {
        OssPublishProperties properties = valid();
        properties.setPublicBaseUrl(" HTTPS://Files.Example.com/ ");
        properties.setPreviewEnabled(true);
        assertThatCode(() -> properties.requireReady(Map.of())).doesNotThrowAnyException();
        assertThat(properties.publicBaseUrl()).isEqualTo("https://files.example.com");
        String key = "reports/中文 file%20.html";
        java.net.URI url = java.net.URI.create(properties.publicUrl(key));
        assertThat(url.getHost()).isEqualTo("files.example.com");
        assertThat(url.getPath()).isEqualTo("/" + key);
        assertThat(url.getRawPath()).contains("%25").doesNotContain(" ");
    }

    @Test
    void customOriginRejectsAnythingExceptHttpsDomainRoot() {
        for (String value : new String[] { "http://files.example.com", "https://user@files.example.com",
                "https://files.example.com:443", "https://files.example.com:8443",
                "https://files.example.com/prefix", "https://files.example.com//",
                "https://files.example.com?x=1", "https://files.example.com#fragment",
                "https://files.example.com/%2f", "https://*.example.com", "https://localhost",
                "https://127.0.0.1", "https://[::1]", "https://files.example.com/; script-src *" }) {
            OssPublishProperties properties = valid();
            properties.setPublicBaseUrl(value);
            assertThatThrownBy(properties::publicBaseUrl).as(value)
                    .hasMessage("OSS_PUBLIC_BASE_URL_INVALID");
        }
    }

    @Test
    void previewRequiresCustomDomainNotDefaultOssDomain() {
        for (String value : new String[] { "", "https://test-artifacts.oss-cn-beijing.aliyuncs.com" }) {
            OssPublishProperties properties = valid();
            properties.setPublicBaseUrl(value);
            properties.setPreviewEnabled(true);
            assertThatThrownBy(() -> properties.requireReady(Map.of()))
                    .hasMessage("OSS_PREVIEW_CUSTOM_DOMAIN_REQUIRED");
        }
    }

    @Test
    void customAndHistoricalHostsAreTrustedOnlyInsideEachExistingNamespace() {
        OssPublishProperties properties = valid();
        properties.setPublicBaseUrl("https://files.example.com");
        for (String host : new String[] { "files.example.com", "test-artifacts.oss-cn-beijing.aliyuncs.com" }) {
            String clipboard = "https://" + host + "/zhikuncode-artifacts/clipboard/session/image.png";
            String localFile = "https://" + host + "/zhikuncode-artifacts/local-files/session/report.pdf";
            assertThat(properties.isTrustedClipboardImageUrl(clipboard)).isTrue();
            assertThat(properties.isTrustedLocalFileUrl(localFile)).isTrue();
            assertThat(properties.isTrustedLocalFileUrl(clipboard)).isFalse();
            assertThat(properties.isTrustedClipboardImageUrl(localFile)).isFalse();
            assertThat(properties.isTrustedClipboardImageUrl(clipboard + "?x=1")).isFalse();
            assertThat(properties.isTrustedLocalFileUrl(localFile + "#fragment")).isFalse();
        }
        for (String value : new String[] {
                "https://files.example.com.attacker.test/zhikuncode-artifacts/clipboard/image.png",
                "https://user@files.example.com/zhikuncode-artifacts/clipboard/image.png",
                "https://files.example.com:443/zhikuncode-artifacts/clipboard/image.png",
                "https://files.example.com/zhikuncode-artifacts/clipboard/../private/image.png",
                "https://files.example.com/zhikuncode-artifacts/clipboard/%2e%2e/private/image.png" }) {
            assertThat(properties.isTrustedClipboardImageUrl(value)).as(value).isFalse();
        }
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
    void prefixRejectsSegmentsThatWouldNormalizeAfterPublication() {
        for (String prefix : new String[] { "safe/./files", "safe//files" }) {
            OssPublishProperties properties = valid();
            properties.setPrefix(prefix);

            assertThatThrownBy(() -> properties.requireReady(Map.of()))
                    .hasMessage("OSS_PREFIX_INVALID");
        }
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
    void fileSizeLimitAllowsOneHundredMiBButNothingLarger() {
        OssPublishProperties properties = valid();
        properties.setMaxFileBytes(100L * 1024 * 1024);
        assertThatCode(() -> properties.requireReady(Map.of()))
                .doesNotThrowAnyException();

        properties.setMaxFileBytes(100L * 1024 * 1024 + 1);
        assertThatThrownBy(() -> properties.requireReady(Map.of()))
                .hasMessage("OSS_FILE_LIMIT_INVALID");
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

    @Test
    void onlyConfiguredLocalFilePrefixIsTrusted() {
        OssPublishProperties properties = valid();
        String trusted = "https://test-artifacts.oss-cn-beijing.aliyuncs.com/"
                + "zhikuncode-artifacts/local-files/session/file.txt";

        assertThat(properties.isTrustedLocalFileUrl(trusted)).isTrue();
        assertThat(properties.isTrustedLocalFileUrl(trusted + "?signature=unexpected"))
                .isFalse();
        assertThat(properties.isTrustedLocalFileUrl(
                "https://attacker.example/zhikuncode-artifacts/local-files/file.txt"))
                .isFalse();
        assertThat(properties.isTrustedClipboardImageUrl(trusted)).isFalse();
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
