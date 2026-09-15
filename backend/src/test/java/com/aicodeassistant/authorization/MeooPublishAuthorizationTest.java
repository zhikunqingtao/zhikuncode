package com.aicodeassistant.authorization;

import com.aicodeassistant.artifact.meoo.MeooPublicationPolicy;
import com.aicodeassistant.security.*;
import com.aicodeassistant.tool.*;
import com.aicodeassistant.tool.bash.BashSecurityAnalyzer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MeooPublishAuthorizationTest {
    @TempDir Path root;
    @Test void authorizationBindsSnapshotAndRejectsPostApprovalChanges() throws Exception {
        var policy=mock(MeooPublicationPolicy.class);
        var first=new MeooPublicationPolicy.Snapshot(root,"site","static","Demo","account",List.of(),12,"a".repeat(64),"ev");
        var changed=new MeooPublicationPolicy.Snapshot(root,"site","static","Demo","account",List.of(),13,"b".repeat(64),"ev");
        var registry=new OperationAnalyzerRegistry(new ObjectMapper(),mock(BashSecurityAnalyzer.class),new SensitiveDataFilter(),mock(PathSecurityService.class));
        registry.setMeooPublicationPolicy(policy);
        Tool tool=mock(Tool.class);when(tool.getName()).thenReturn("PublishMeoo");
        var input=ToolInput.from(Map.of("path","site","runtime","static","verification_id","ev","_approved_sha256","forged"));
        var context=ToolUseContext.of(root.toString(),"session").withCurrentRunId("run");
        var subject=new AuthorizationSubject("session","run","run","workspace",root);
        when(policy.inspect(any(),eq(context),eq(true))).thenReturn(first);
        try(var frozen=new FrozenToolInputFactory(new ObjectMapper(),1024*1024,4*1024*1024).freeze(tool.getName(),input)) {
            var analyzer=registry.analyzerFor(tool);var descriptor=analyzer.analyze(tool,frozen,input,context,subject);
            assertThat(descriptor.risk()).isEqualTo(RiskClass.HIGH);
            assertThat(descriptor.redactedSummary()).contains("创建新的秒悟","account");
            var bound=registry.bindExecutionInput(tool,descriptor,input,subject);
            assertThat(bound.getString("_approved_sha256")).isEqualTo(first.sha256());
            analyzer.recheck(tool,descriptor,bound,context,subject);
            when(policy.inspect(any(),eq(context),eq(true))).thenReturn(changed);
            assertThatThrownBy(()->analyzer.recheck(tool,descriptor,bound,context,subject)).isInstanceOf(AuthorizationException.class);
        }
    }
}
