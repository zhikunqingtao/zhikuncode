package com.aicodeassistant.tool.artifact;

import com.aicodeassistant.artifact.meoo.*;
import com.aicodeassistant.config.meoo.MeooPublishProperties;
import com.aicodeassistant.tool.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class PublishMeooTool implements Tool {
    private final MeooPublicationService service;
    private final MeooPublishProperties properties;
    public PublishMeooTool(MeooPublicationService service,MeooPublishProperties properties) { this.service=service;this.properties=properties; }
    @Override public String getName() { return "PublishMeoo"; }
    @Override public String getDescription() { return "Create a NEW public Meoo site from a verified exact workspace path after one-time authorization. Never overwrite an existing site, provision databases, or automatically retry."; }
    @Override public Map<String,Object> getInputSchema() { return InspectMeooDeploymentTool.schema(); }
    @Override public Map<String,Object> getSchema() { return getInputSchema(); }
    @Override public String getGroup() { return "artifact"; }
    @Override public boolean isEnabled() { return properties.isEnabled(); }
    @Override public PermissionRequirement getPermissionRequirement() { return PermissionRequirement.ALWAYS_ASK; }
    @Override public boolean isHighRisk() { return true; }
    @Override public boolean isOpenWorld() { return true; }
    @Override public boolean isConcurrencySafe(ToolInput input) { return false; }
    @Override public InterruptBehavior interruptBehavior() { return InterruptBehavior.BLOCK; }
    @Override public long getMaxExecutionTimeMs() { return (properties.getTimeoutSeconds()*2L+90)*1000; }
    @Override public String getPath(ToolInput input) { return input.getString("path",null); }
    @Override public String userFacingName(ToolInput input) { return "发布新的秒悟公网应用"; }
    @Override public ToolResult call(ToolInput input,ToolUseContext context) {
        try {
            var p=service.publish(input,context);
            Map<String,Object> card=new LinkedHashMap<>();
            card.put("schema","site-publication/v1"); card.put("provider","meoo"); card.put("publicationId",p.id());
            card.put("label",p.label()); card.put("runtime",p.runtime());
            String state=Set.of("pending","deploying").contains(p.state())?"unknown":p.state();
            card.put("state",state);
            if(p.projectId()!=null) card.put("projectId",p.projectId());
            if(p.projectUrl()!=null) card.put("projectUrl",p.projectUrl());
            if(p.version()!=null) card.put("version",p.version());
            if(p.accessUrl()!=null) card.put("url",p.accessUrl());
            if(p.errorCode()!=null) card.put("errorCode",p.errorCode());
            if(!state.equals("public_verified")) card.put("guidance",MeooException.guidance(p.errorCode()));
            boolean deployed=state.equals("public_verified") || state.equals("deployed_unverified");
            String message=switch(state) {
                case "public_verified" -> "秒悟发布成功，匿名公网访问验证通过，请使用网站卡片打开或复制链接。";
                case "deployed_unverified" -> "秒悟部署完成，匿名访问尚未验证通过，请查看网站卡片并检查项目访问权限。";
                case "failed" -> "秒悟发布失败: "+p.errorCode()+"。不会自动重试，请检查配置或额度。";
                default -> "秒悟远端结果待确认，请查看项目设置；不会自动创建另一个站点或重发部署。";
            };
            return deployed ? ToolResult.successWithEffect(message,ToolResult.EffectState.APPLIED,Map.of("structuredResult",card))
                : ToolResult.failed(ToolResult.ToolFailureType.PROVIDER,p.errorCode()==null?"MEOO_REMOTE_RESULT_UNKNOWN":p.errorCode(),message,ToolResult.Retryability.NEVER,ToolResult.EffectState.UNKNOWN,null,Map.of("structuredResult",card));
        } catch(MeooException e) { return ToolResult.validationError(e.code(),"秒悟发布被拒绝: "+e.code()+"。"+MeooException.guidance(e.code())); }
    }
}
