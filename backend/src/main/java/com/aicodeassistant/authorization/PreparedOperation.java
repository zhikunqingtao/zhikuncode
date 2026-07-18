package com.aicodeassistant.authorization;

/** 从策略钩子传递到授权裁决的不可变分析结果。 */
public record PreparedOperation(AuthorizationSubject subject, OperationDescriptor descriptor,
                                String executionAttemptId) { }
