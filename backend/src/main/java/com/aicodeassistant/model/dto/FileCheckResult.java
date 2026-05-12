package com.aicodeassistant.model.dto;

public record FileCheckResult(
    String filePath,
    CheckDetail typescript,
    CheckDetail eslint,
    TestCheckDetail vitest
) {}
