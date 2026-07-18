package com.aicodeassistant.authorization;

import com.aicodeassistant.model.PermissionScope;
import com.aicodeassistant.tool.ToolInput;

/** 传递给唯一执行网关的授权结果。 */
public record AuthorizedOperation(AuthorizationSubject subject, OperationDescriptor descriptor,
                                  ToolInput executionInput, AuthorizationDiagnostic.Source source,
                                  String reasonCode, String grantId, PermissionScope grantScope,
                                  String interactionId, String executionAttemptId) { }
