package com.aicodeassistant.tool.artifact;

import com.aicodeassistant.artifact.meoo.*;
import com.aicodeassistant.config.meoo.MeooPublishProperties;
import com.aicodeassistant.tool.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class InspectMeooDeploymentTool implements Tool {
    private final MeooPublicationPolicy policy;
    private final MeooPublishProperties properties;
    public InspectMeooDeploymentTool(MeooPublicationPolicy policy,MeooPublishProperties properties) { this.policy=policy;this.properties=properties; }
    @Override public String getName() { return "InspectMeooDeployment"; }
    @Override public String getDescription() { return "Inspect an exact workspace HTML file, static site directory, or full-stack source directory for Meoo publication. No cloud resources are created. Call only for an explicit Meoo publication request."; }
    @Override public Map<String,Object> getInputSchema() { return schema(); }
    @Override public Map<String,Object> getSchema() { return schema(); }
    public static Map<String,Object> schema() {
        return Map.of("type","object","properties",Map.of(
            "path",Map.of("type","string","description","Exact path inside the authorized workspace; no glob"),
            "runtime",Map.of("type","string","enum",List.of("static","image")),
            "name",Map.of("type","string","maxLength",100),
            "verification_id",Map.of("type","string","description","Passed VerifyJourney evidence for this workspace, required to publish")),
            "required",List.of("path","runtime"),"additionalProperties",false);
    }
    @Override public boolean isEnabled() { return properties.isEnabled(); }
    @Override public boolean isReadOnly(ToolInput input) { return true; }
    @Override public String getGroup() { return "artifact"; }
    @Override public String getPath(ToolInput input) { return input.getString("path",null); }
    @Override public ToolResult call(ToolInput input,ToolUseContext context) {
        try {
            var snapshot=policy.inspect(input,context,false);
            return ToolResult.success(snapshot.summary()+"\n文件清单:\n"+String.join("\n",snapshot.files().stream().map(MeooPublicationPolicy.FileFact::relativePath).toList())
                +(snapshot.verificationId().isBlank()?"\n发布前需要 VerifyJourney 的通过证据 verification_id。":"\n验证证据已检查。"));
        } catch(MeooException e) { return ToolResult.validationError(e.code(),"秒悟发布检查未通过: "+e.code()+"。"+MeooException.guidance(e.code())); }
    }
}
