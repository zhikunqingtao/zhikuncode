package com.aicodeassistant.artifact.meoo;

import com.aicodeassistant.config.meoo.MeooPublishProperties;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

@Service
public class MeooCliClient {
    private final MeooPublishProperties properties;
    private final ManagedProcessRunner runner;
    private final ObjectMapper json;
    public MeooCliClient(MeooPublishProperties properties,ManagedProcessRunner runner,ObjectMapper json) { this.properties=properties;this.runner=runner;this.json=json; }
    public JsonNode execute(List<String> arguments,Path cwd,Path home,ToolUseContext context) {
        try {
            var credentials=properties.credential();
            Map<String,String> env=new HashMap<>();
            env.put("PATH",System.getenv().getOrDefault("PATH","/usr/local/bin:/usr/bin:/bin"));
            env.put("HOME",home.toString()); env.put("TMPDIR",home.toString()); env.put("LANG","C.UTF-8");
            env.put("MEOO_API_KEY",credentials.key()); env.put("MEOO_API_URL","https://meoo.com");
            List<String> cmd=new ArrayList<>(List.of(properties.getExecutable(),"--json")); cmd.addAll(arguments);
            var result=runner.runIsolated(new ManagedProcessRunner.Request(cmd,cwd,Duration.ofSeconds(properties.getTimeoutSeconds()),context.currentRunId(),context.toolUseId()),env);
            if(result.timedOut() || result.cancelled()) throw new MeooException("MEOO_REMOTE_RESULT_UNKNOWN");
            if(result.stdoutTruncated() || result.stderrTruncated()) throw new MeooException("MEOO_OUTPUT_TRUNCATED");
            JsonNode response=parse(result.stdout());
            if(result.exitCode()!=0 || !response.path("success").asBoolean()) {
                String code=response.path("error").path("code").asText(response.path("code").asText());
                throw new MeooException(safeCode(code));
            }
            return response.path("data");
        } catch(MeooException e) { throw e; }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new MeooException("MEOO_REMOTE_RESULT_UNKNOWN"); }
        catch(Exception e) { throw new MeooException("MEOO_CLI_FAILED"); }
    }
    public void checkVersion(Path cwd,Path home,ToolUseContext context) {
        try {
            Map<String,String> env=Map.of("PATH",System.getenv().getOrDefault("PATH","/usr/local/bin:/usr/bin:/bin"),"HOME",home.toString(),"TMPDIR",home.toString());
            var result=runner.runIsolated(new ManagedProcessRunner.Request(List.of(properties.getExecutable(),"--version"),cwd,Duration.ofSeconds(15),context.currentRunId(),context.toolUseId()),env);
            if(result.exitCode()!=0 || !result.stdout().strip().equals(MeooPublishProperties.CLI_VERSION)) throw new MeooException("MEOO_CLI_VERSION_MISMATCH");
        } catch(MeooException e) { throw e; }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new MeooException("MEOO_REMOTE_RESULT_UNKNOWN"); }
        catch(Exception e) { throw new MeooException("MEOO_CLI_UNAVAILABLE"); }
    }
    JsonNode parse(String text) {
        // The pinned CLI emits one final JSON document, with optional surrounding whitespace.
        try { return json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text.strip()); }
        catch(Exception e) { throw new MeooException("MEOO_INVALID_RESPONSE"); }
    }
    private static String safeCode(String code) {
        return switch(code) {
            case "DEPLOY_QUOTA_EXCEEDED", "QUOTA_EXCEEDED", "STORAGE_EXCEEDED" -> "MEOO_QUOTA_EXCEEDED";
            case "INVALID_CREDENTIAL", "NOT_LOGGED_IN", "UNAUTHORIZED" -> "MEOO_AUTH_FAILED";
            case "DEPLOY_FAILED" -> "MEOO_BUILD_FAILED";
            case "REQUEST_TIMEOUT" -> "MEOO_REMOTE_RESULT_UNKNOWN";
            default -> "MEOO_PROVIDER_FAILED";
        };
    }
}
