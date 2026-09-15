package com.aicodeassistant.artifact.meoo;

import com.aicodeassistant.config.meoo.MeooPublishProperties;
import com.aicodeassistant.tool.*;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MeooCliClientTest {
    @TempDir Path root;
    @Test void credentialsAreOnlyPassedThroughControlledEnvironmentAndNeverArguments() throws Exception {
        String key="meoo_ak_test_fixture_only";
        Path credential=root.resolve("credentials.json");
        Files.writeString(credential,"{\"apiBaseUrl\":\"https://meoo.com\",\"credentialType\":\"api_key\",\"apiKey\":\""+key+"\",\"userId\":\"test\"}");
        Files.setPosixFilePermissions(credential,PosixFilePermissions.fromString("rw-------"));
        var props=new MeooPublishProperties();props.setEnabled(true);props.setCredentialsFile(credential.toString());
        var runner=mock(ManagedProcessRunner.class);
        when(runner.runIsolated(any(),any())).thenAnswer(call->{
            ManagedProcessRunner.Request request=call.getArgument(0); Map<String,String> environment=call.getArgument(1);
            assertThat(String.join(" ",request.command())).doesNotContain(key).contains("--json");
            assertThat(environment).containsEntry("MEOO_API_KEY",key).containsEntry("HOME",root.toString());
            assertThat(environment).doesNotContainKeys("NODE_OPTIONS","ALIBABA_CLOUD_ACCESS_KEY_SECRET");
            return new ManagedProcessRunner.Result(0,"{\"success\":true,\"data\":{\"urlId\":\"site\"}}","",false,false,false,false,true,1,false);
        });
        var cli=new MeooCliClient(props,runner,new ObjectMapper());
        var context=ToolUseContext.of(root.toString(),"session").withCurrentRunId("run").withToolUseId("tool");
        assertThat(cli.execute(List.of("projects","create","Site"),root,root,context).path("urlId").asText()).isEqualTo("site");
        assertThatThrownBy(()->cli.parse("untrusted error + secret")).hasMessage("MEOO_INVALID_RESPONSE");
    }
    @Test void providerFailuresAreStableAndRawOutputNeverEscapes() throws Exception {
        var props=mock(MeooPublishProperties.class);
        var credential=mock(MeooPublishProperties.Credential.class);
        when(credential.key()).thenReturn("private-fixture-token");when(credential.account()).thenReturn("account");
        when(props.credential()).thenReturn(credential);when(props.getExecutable()).thenReturn("meoo");when(props.getTimeoutSeconds()).thenReturn(30);
        var runner=mock(ManagedProcessRunner.class);var cli=new MeooCliClient(props,runner,new ObjectMapper());
        var ctx=ToolUseContext.of(root.toString(),"s").withCurrentRunId("r").withToolUseId("t");
        for(var code:Map.of("DEPLOY_QUOTA_EXCEEDED","MEOO_QUOTA_EXCEEDED","UNAUTHORIZED","MEOO_AUTH_FAILED","DEPLOY_FAILED","MEOO_BUILD_FAILED").entrySet()) {
            String output="{\"success\":false,\"error\":{\"code\":\""+code.getKey()+"\",\"message\":\"private-fixture-token\"}}";
            when(runner.runIsolated(any(),any())).thenReturn(new ManagedProcessRunner.Result(1,output,"private-fixture-token",false,false,false,false,true,1,false));
            assertThatThrownBy(()->cli.execute(List.of("deploy"),root,root,ctx)).hasMessage(code.getValue());
        }
        for(var result:List.of(
            new ManagedProcessRunner.Result(137,"","",false,false,true,false,true,1,false),
            new ManagedProcessRunner.Result(130,"","",false,false,false,true,true,1,false),
            new ManagedProcessRunner.Result(0,"{}","",true,false,false,false,true,1,false))) {
            when(runner.runIsolated(any(),any())).thenReturn(result);
            assertThatThrownBy(()->cli.execute(List.of("deploy"),root,root,ctx)).hasMessage(result.stdoutTruncated()?"MEOO_OUTPUT_TRUNCATED":"MEOO_REMOTE_RESULT_UNKNOWN");
        }
        assertThatThrownBy(()->cli.parse("{} {}" )).hasMessage("MEOO_INVALID_RESPONSE");
    }
    @Test void entryVerificationAllowsOnlyPlatformDecorationAndRejectsLoginOrDifferentApp() {
        byte[] original="<!doctype html><html><head><meta charset='utf-8'/><title>Demo</title></head><body><!-- note --><h1>Real app</h1><script src='./app.js'></script></body></html>".getBytes();
        byte[] decorated="<html><head><meta charset='utf-8'><title>Demo</title><link rel='icon' href='/favicon.ico?v=0'></head><body>\n<h1>Real app</h1><script src='./app.js'></script><div id='meoo-brand-watermark'>By Meoo</div><script id='meoo-baxia-web-security' src='https://g.alicdn.com/security.js'></script></body></html>".getBytes();
        assertThat(MeooAccessVerifier.entryHash(decorated)).isEqualTo(MeooAccessVerifier.entryHash(original));
        assertThat(MeooAccessVerifier.entryHash("<html><h1>Login to Meoo</h1></html>".getBytes())).isNotEqualTo(MeooAccessVerifier.entryHash(original));
        assertThat(MeooAccessVerifier.entryHash("<html><h1>Different app</h1></html>".getBytes())).isNotEqualTo(MeooAccessVerifier.entryHash(original));
    }
    @Test void rejectsUntrustedAccessUrls() {
        for(String url:List.of("http://x.meoo.fun","https://localhost","https://x.meoo.fun.evil.test","https://u@x.meoo.fun","https://x.meoo.pub.evil.test","https://x.meoo.fun/?secret=a"))
            assertThatThrownBy(()->MeooAccessVerifier.trustedSite(url)).hasMessage("MEOO_ACCESS_URL_INVALID");
        assertThat(MeooAccessVerifier.trustedSite("https://demo.meoo.pub").getHost()).isEqualTo("demo.meoo.pub");
        assertThat(MeooAccessVerifier.trustedSite("https://demo.meoo.fun").getHost()).isEqualTo("demo.meoo.fun");
        assertThat(MeooAccessVerifier.trustedProject("https://meoo.com/chat/demo","demo")).isEqualTo("https://meoo.com/chat/demo");
        assertThatThrownBy(()->MeooAccessVerifier.trustedProject("https://evil.test/chat/demo","demo")).hasMessage("MEOO_PROJECT_URL_INVALID");
    }
}
