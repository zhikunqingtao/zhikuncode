package com.aicodeassistant.verify;

public record StackInfo(
    String stackId,              // "vite" | "next" | "cra" | "static" | "unknown"
    int defaultPort,             // 5173, 3000, etc.
    String defaultStartCommand   // "npm run dev", "npm start", etc.
) {}
