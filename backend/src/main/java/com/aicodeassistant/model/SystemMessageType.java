package com.aicodeassistant.model;

/**
 * 系统消息类型。
 *
 * @see <a href="SPEC §5.1">消息模型</a>
 */
public enum SystemMessageType {
    INFO,
    WARNING,
    ERROR,
    WELCOME,
    COMPACT_SUMMARY,
    FILE_REINJECT
}
