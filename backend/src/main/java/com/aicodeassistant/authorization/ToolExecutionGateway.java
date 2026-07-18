package com.aicodeassistant.authorization;

import com.aicodeassistant.run.RunControlService;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import org.springframework.stereotype.Service;

import java.util.Map;

/** 生产代码中唯一允许调用 Tool.call() 的入口。 */
@Service
public final class ToolExecutionGateway {
    public static final class AdmissionException extends RuntimeException {
        private final String code;
        public AdmissionException(String code, Throwable cause) { super(code, cause); this.code = code; }
        public String code() { return code; }
    }
    private final AuthorizationService authorization;
    private final RunControlService runs;
    public ToolExecutionGateway(AuthorizationService authorization, RunControlService runs) {
        this.authorization = authorization; this.runs = runs;
    }

    public ToolResult execute(Tool tool, AuthorizedOperation allowed, ToolUseContext context) {
        return execute(tool, allowed, context, null);
    }

    /**
     * 完成动态环境与授权记录复检、提交执行准入事件，然后调用已经冻结的工具输入。
     * 数据库短事务会在实际工具执行前结束，避免长任务占用 SQLite 写锁。
     *
     * @param tool 待执行工具
     * @param allowed 前置授权阶段生成的授权操作
     * @param context 持久化 Run 对应的工具上下文
     * @param admissionAction 与准入事件同事务执行的可选短声明动作；仅允许有界元数据检查，不得产生文件副作用或网络 I/O
     * @return 工具返回的结构化结果
     */
    public ToolResult execute(Tool tool, AuthorizedOperation allowed, ToolUseContext context,
                              Runnable admissionAction) {
        if (context.currentRunId() == null) throw new AuthorizationException("AUTHORIZATION_ANCESTRY_INVALID",
                "Authorized tool execution requires a persisted Run");
        try {
            authorization.finalDynamicRecheck(tool, allowed, context);
            Map<String, Object> event = AuthorizationDiagnostic.payload(
                    allowed.subject(), allowed.descriptor(), context, allowed.executionAttemptId(),
                    AuthorizationDiagnostic.Outcome.ALLOW,
                    AuthorizationDiagnostic.EvaluationStage.FINAL_RECHECK,
                    allowed.source(), allowed.reasonCode(), allowed.grantId(), allowed.grantScope(),
                    allowed.interactionId());
            runs.executeBoundedWrite(() -> {
                authorization.finalGrantRecheckInCurrentTransaction(allowed, context);
                if (admissionAction != null) admissionAction.run();
                runs.appendEventInCurrentWrite(context.currentRunId(), "tool_started", context.toolUseId(), event);
                return null;
            });
        } catch (AuthorizationException denied) {
            authorization.recordFinalDenial(allowed, context, denied.code());
            throw denied;
        }
        return tool.call(allowed.executionInput(), context);
    }
}
