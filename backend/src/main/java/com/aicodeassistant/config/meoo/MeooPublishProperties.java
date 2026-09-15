package com.aicodeassistant.config.meoo;

import com.aicodeassistant.artifact.meoo.MeooException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

@Component
@ConfigurationProperties(prefix = "zhikuncode.meoo")
public class MeooPublishProperties {
    public static final String CLI_VERSION = "0.5.3";
    private boolean enabled;
    private String credentialsFile = System.getProperty("user.home") + "/.meoo/credentials.json";
    private String executable = "meoo";
    private long maxBytes = 100L * 1024 * 1024;
    private int maxFiles = 10000;
    private int timeoutSeconds = 1200;

    public Credential credential() {
        if (!enabled) throw new MeooException("MEOO_DISABLED");
        if (maxBytes < 1 || maxBytes > 100L * 1024 * 1024 || maxFiles < 1 || maxFiles > 50000
                || timeoutSeconds < 30 || timeoutSeconds > 3600 || executable.isBlank())
            throw new MeooException("MEOO_CONFIG_INVALID");
        try {
            Path path = Path.of(credentialsFile);
            if (!path.isAbsolute() || !Files.isRegularFile(path) || Files.size(path) > 65536)
                throw new MeooException("MEOO_CREDENTIALS_UNAVAILABLE");
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
                if (permissions.stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")))
                    throw new MeooException("MEOO_CREDENTIAL_PERMISSIONS");
            }
            var json = new ObjectMapper().readTree(Files.readString(path));
            if (!"https://meoo.com".equals(json.path("apiBaseUrl").asText())
                    || !"api_key".equals(json.path("credentialType").asText())
                    || !json.path("projectUrlId").asText().isBlank())
                throw new MeooException("MEOO_ACCOUNT_CREDENTIAL_REQUIRED");
            String key = json.path("apiKey").asText();
            if (!key.startsWith("meoo_ak_") || key.chars().anyMatch(Character::isWhitespace))
                throw new MeooException("MEOO_CREDENTIALS_INVALID");
            String account = json.path("userId").asText();
            if (account.isBlank()) throw new MeooException("MEOO_ACCOUNT_ID_REQUIRED");
            return new Credential(key, account);
        } catch (MeooException e) { throw e; }
        catch (Exception e) { throw new MeooException("MEOO_CREDENTIALS_UNAVAILABLE"); }
    }
    public static final class Credential {
        private final String key;
        private final String account;
        Credential(String key, String account) { this.key = key; this.account = account; }
        public String key() { return key; }
        public String account() { return account; }
        @Override public String toString() { return "MeooCredential[REDACTED]"; }
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public String getCredentialsFile() { return credentialsFile; }
    public void setCredentialsFile(String v) { credentialsFile = v; }
    public String getExecutable() { return executable; }
    public void setExecutable(String v) { executable = v; }
    public long getMaxBytes() { return maxBytes; }
    public void setMaxBytes(long v) { maxBytes = v; }
    public int getMaxFiles() { return maxFiles; }
    public void setMaxFiles(int v) { maxFiles = v; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int v) { timeoutSeconds = v; }
}
