package com.aicodeassistant.tool.impl;

import com.aicodeassistant.session.merge.HandoffReadService;

import com.aicodeassistant.authorization.AuthorizationSubjectResolver;
import com.aicodeassistant.tool.*;
import java.nio.file.*;
import java.util.*;

public class HandoffReadTool implements Tool {
    private final HandoffReadService reads;
    private final AuthorizationSubjectResolver subjects;
    private final com.aicodeassistant.tool.impl.ImageResultExternalizer images;
    public HandoffReadTool(HandoffReadService reads,AuthorizationSubjectResolver subjects,com.aicodeassistant.tool.impl.ImageResultExternalizer images) { this.reads=reads; this.subjects=subjects; this.images=images; }
    public String getName() { return "HandoffRead"; }
    public String getDescription() { return "只读当前根会话绑定的独立交接资料。list 列目录，search 字面搜索，read 按 ref 分页读取原文或详情，asset 读取图片。complete=false 时继续传 nextCursor；ref=gaps 查看资料缺口。不能传文件路径或其他会话 ID。"; }
    public boolean alwaysLoad() { return true; }
    public boolean isReadOnly(ToolInput input) { return true; }
    public Map<String,Object> getInputSchema() {
        Map<String,Object> properties=new LinkedHashMap<>();
        properties.put("action",Map.of("type","string","enum",List.of("list","search","read","asset")));
        for(String field:List.of("ref","query","sourceId","section","cursor")) properties.put(field,Map.of("type","string"));
        properties.put("limit",Map.of("type","integer","minimum",1,"maximum",20));
        return Map.of("type","object","properties",properties,"required",List.of("action"),"additionalProperties",false);
    }
    public Map<String,Object> getSchema() { return getInputSchema(); }
    public String authorize(String trustedRoot,ToolInput input) { return reads.authorize(trustedRoot,input); }
    public ToolResult call(ToolInput input,ToolUseContext context) {
        String root=subjects.resolve(context.currentRunId()).rootSessionId();
        try {
            if("asset".equals(input.getString("action"))) {
                Path path=reads.asset(root,input.getString("ref"));
                String mime=Files.probeContentType(path);
                // Detect copied images by content; snapshot names deliberately have no caller extension.
                try(var stream=javax.imageio.ImageIO.createImageInputStream(path.toFile())) {
                    var readers=javax.imageio.ImageIO.getImageReaders(stream);
                    if(readers.hasNext()) { var reader=readers.next(); try { mime="image/"+reader.getFormatName().toLowerCase(Locale.ROOT); } finally { reader.dispose(); } }
                }
                if(mime==null || !Set.of("image/png","image/jpeg","image/jpg","image/gif","image/webp").contains(mime))
                    return ToolResult.validationError("HANDOFF_ASSET_UNSUPPORTED","原件已保存，当前工具无法解析此附件；请读取相关文字资料。");
                if(Files.size(path)>20*1024*1024) return ToolResult.validationError("HANDOFF_ASSET_TOO_LARGE","图片超过现有读取容量，原件已保留");
                return ToolResult.text(images.externalize(path,mime,Files.size(path))).withMetadata("type","image_ref");
            }
            return ToolResult.text(reads.read(root,input));
        } catch(java.io.IOException e) { return ToolResult.validationError("HANDOFF_READ_FAILED",e.getMessage()); }
    }
}
