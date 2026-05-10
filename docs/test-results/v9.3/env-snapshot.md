# v9.3 环境快照

**采集时间**: 2026-05-09 22:42:12 CST

## 三端服务

| 服务 | 端口 | PID | 状态 |
|------|------|-----|------|
  File "<string>", line 1
    import sys,json;d=json.load(sys.stdin);print(d[\"status\"])
                                                    ^
SyntaxError: unexpected character after line continuation character
| Backend  | 8080 | 40974 | UP (heap 68MB/4096MB, uptime 3s at launch) |
| Python   | 8000 | 40975 | 200 |
| Frontend | 5173 | 40999 | 200 |

## 运行时版本

```
openjdk version "21.0.10" 2026-01-20 LTS
OpenJDK Runtime Environment Corretto-21.0.10.7.1 (build 21.0.10+7-LTS)
OpenJDK 64-Bit Server VM Corretto-21.0.10.7.1 (build 21.0.10+7-LTS, mixed mode, sharing)
---
v22.14.0
---
Python 3.11.15
---
3.9.9
git version 2.50.1 (Apple Git-155)
```

## LLM Provider / 模型注册

{
    "models": [
        {
            "id": "qwen3.6-max-preview",
            "displayName": "Qwen 3.6 Max Preview",
            "maxOutputTokens": 16384,
            "contextWindow": 262144,
            "supportsStreaming": true,
            "supportsThinking": true,
            "supportsImages": false,
            "supportsToolUse": true,
            "costPer1kInput": 0.009,
            "costPer1kOutput": 0.054
        },
        {
            "id": "qwen3.6-plus",
            "displayName": "Qwen 3.6 Plus",
            "maxOutputTokens": 8192,
            "contextWindow": 1000000,
            "supportsStreaming": true,
            "supportsThinking": false,
            "supportsImages": true,
            "supportsToolUse": true,
            "costPer1kInput": 0.0008,
            "costPer1kOutput": 0.002
        },
        {
            "id": "deepseek-v4-pro",
            "displayName": "DeepSeek V4 Pro",
            "maxOutputTokens": 384000,
            "contextWindow": 1000000,
            "supportsStreaming": true,
            "supportsThinking": true,
            "supportsImages": false,
            "supportsToolUse": true,
            "costPer1kInput": 0.001,
            "costPer1kOutput": 0.004
        },
        {
            "id": "deepseek-v4-flash",
            "displayName": "DeepSeek V4 Flash",
            "maxOutputTokens": 384000,
            "contextWindow": 1000000,
            "supportsStreaming": true,
            "supportsThinking": true,
            "supportsImages": false,
            "supportsToolUse": true,
            "costPer1kInput": 0.0005,
            "costPer1kOutput": 0.002
        },
        {
            "id": "kimi-k2.6",
            "displayName": "Kimi K2.6",
            "maxOutputTokens": 16384,
            "contextWindow": 256000,
            "supportsStreaming": true,
            "supportsThinking": true,
            "supportsImages": true,
            "supportsToolUse": true,
            "costPer1kInput": 0.002,
            "costPer1kOutput": 0.012
        },
        {
            "id": "moonshot-v1-auto",
            "displayName": "Moonshot V1 Auto",
            "maxOutputTokens": 8192,
            "contextWindow": 128000,
            "supportsStreaming": true,
            "supportsThinking": false,
            "supportsImages": false,
            "supportsToolUse": true,
            "costPer1kInput": 0.001,
            "costPer1kOutput": 0.002
        }
    ],
    "defaultModel": "qwen3.6-max-preview"
}
