package com.aicodeassistant.hook;

/**
 * 钩子事件类型枚举 — 定义系统支持的所有钩子事件。
 * <p>
 * 对照源码 loadPluginHooks.ts 的钩子类型定义。
 *
 * @see <a href="SPEC section 3.10">Hook 系统</a>
 */
public enum HookEvent {

    /** 工具调用前拦截 — 可修改输入或拒绝调用 */
    PRE_TOOL_USE,

    /** 工具调用后处理 — 可修改输出 */
    POST_TOOL_USE,

    /** 用户提交消息前 — 可修改消息或阻止提交 */
    USER_PROMPT_SUBMIT,

    /** 通知事件处理 */
    NOTIFICATION,

    /** 查询循环结束后处理 */
    STOP,

    /** 会话开始 */
    SESSION_START,

    /** 会话结束 */
    SESSION_END
}
