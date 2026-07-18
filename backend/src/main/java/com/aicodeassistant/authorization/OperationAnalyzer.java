package com.aicodeassistant.authorization;

import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolUseContext;

public interface OperationAnalyzer {
    String id();
    OperationDescriptor analyze(Tool tool, FrozenToolInput frozen, ToolInput input,
                                ToolUseContext context, AuthorizationSubject subject);
    void recheck(Tool tool, OperationDescriptor descriptor, ToolInput input, ToolUseContext context,
                 AuthorizationSubject subject);
}
