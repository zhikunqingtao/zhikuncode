package com.aicodeassistant.keybinding;

/**
 * 键盘绑定动作 — 97 种动作标识符常量。
 * <p>
 * v1.65.0 H-06: 从 86 更正为 97 种 (新增 scroll 7 + messageActions 4)
 *
 * @see <a href="SPEC §4.8">键盘绑定系统</a>
 */
public final class KeybindingAction {

    private KeybindingAction() {}

    // ===== 应用级 (10) =====
    public static final String APP_INTERRUPT = "app:interrupt";
    public static final String APP_EXIT = "app:exit";
    public static final String APP_TOGGLE_TODOS = "app:toggleTodos";
    public static final String APP_TOGGLE_TRANSCRIPT = "app:toggleTranscript";
    public static final String APP_TOGGLE_BRIEF = "app:toggleBrief";
    public static final String APP_TOGGLE_TEAMMATE_PREVIEW = "app:toggleTeammatePreview";
    public static final String APP_TOGGLE_TERMINAL = "app:toggleTerminal";
    public static final String APP_REDRAW = "app:redraw";
    public static final String APP_GLOBAL_SEARCH = "app:globalSearch";
    public static final String APP_QUICK_OPEN = "app:quickOpen";

    // ===== 聊天 (13) =====
    public static final String CHAT_CANCEL = "chat:cancel";
    public static final String CHAT_KILL_AGENTS = "chat:killAgents";
    public static final String CHAT_CYCLE_MODE = "chat:cycleMode";
    public static final String CHAT_MODEL_PICKER = "chat:modelPicker";
    public static final String CHAT_FAST_MODE = "chat:fastMode";
    public static final String CHAT_THINKING_TOGGLE = "chat:thinkingToggle";
    public static final String CHAT_SUBMIT = "chat:submit";
    public static final String CHAT_NEWLINE = "chat:newline";
    public static final String CHAT_UNDO = "chat:undo";
    public static final String CHAT_EXTERNAL_EDITOR = "chat:externalEditor";
    public static final String CHAT_STASH = "chat:stash";
    public static final String CHAT_IMAGE_PASTE = "chat:imagePaste";
    public static final String CHAT_MESSAGE_ACTIONS = "chat:messageActions";

    // ===== 确认 (9) =====
    public static final String CONFIRM_YES = "confirm:yes";
    public static final String CONFIRM_NO = "confirm:no";
    public static final String CONFIRM_PREVIOUS = "confirm:previous";
    public static final String CONFIRM_NEXT = "confirm:next";
    public static final String CONFIRM_NEXT_FIELD = "confirm:nextField";
    public static final String CONFIRM_PREVIOUS_FIELD = "confirm:previousField";
    public static final String CONFIRM_CYCLE_MODE = "confirm:cycleMode";
    public static final String CONFIRM_TOGGLE = "confirm:toggle";
    public static final String CONFIRM_TOGGLE_EXPLANATION = "confirm:toggleExplanation";

    // ===== 历史 (3) =====
    public static final String HISTORY_SEARCH = "history:search";
    public static final String HISTORY_PREVIOUS = "history:previous";
    public static final String HISTORY_NEXT = "history:next";

    // ===== 自动补全 (4) =====
    public static final String AUTOCOMPLETE_ACCEPT = "autocomplete:accept";
    public static final String AUTOCOMPLETE_DISMISS = "autocomplete:dismiss";
    public static final String AUTOCOMPLETE_PREVIOUS = "autocomplete:previous";
    public static final String AUTOCOMPLETE_NEXT = "autocomplete:next";

    // ===== 标签页 (2) =====
    public static final String TABS_NEXT = "tabs:next";
    public static final String TABS_PREVIOUS = "tabs:previous";

    // ===== 记录 (2) =====
    public static final String TRANSCRIPT_TOGGLE_SHOW_ALL = "transcript:toggleShowAll";
    public static final String TRANSCRIPT_EXIT = "transcript:exit";

    // ===== 历史搜索 (4) =====
    public static final String HISTORY_SEARCH_NEXT = "historySearch:next";
    public static final String HISTORY_SEARCH_ACCEPT = "historySearch:accept";
    public static final String HISTORY_SEARCH_CANCEL = "historySearch:cancel";
    public static final String HISTORY_SEARCH_EXECUTE = "historySearch:execute";

    // ===== 任务 (1) =====
    public static final String TASK_BACKGROUND = "task:background";

    // ===== 主题 (1) =====
    public static final String THEME_TOGGLE_SYNTAX = "theme:toggleSyntaxHighlighting";

    // ===== 帮助 (1) =====
    public static final String HELP_DISMISS = "help:dismiss";

    // ===== 滚动 (7, v1.65.0 新增) =====
    public static final String SCROLL_PAGE_UP = "scroll:pageUp";
    public static final String SCROLL_PAGE_DOWN = "scroll:pageDown";
    public static final String SCROLL_LINE_UP = "scroll:lineUp";
    public static final String SCROLL_LINE_DOWN = "scroll:lineDown";
    public static final String SCROLL_TOP = "scroll:top";
    public static final String SCROLL_BOTTOM = "scroll:bottom";
    public static final String SELECTION_COPY = "selection:copy";

    // ===== 附件 (4) =====
    public static final String ATTACHMENTS_NEXT = "attachments:next";
    public static final String ATTACHMENTS_PREVIOUS = "attachments:previous";
    public static final String ATTACHMENTS_REMOVE = "attachments:remove";
    public static final String ATTACHMENTS_EXIT = "attachments:exit";

    // ===== 底部面板 (7) =====
    public static final String FOOTER_UP = "footer:up";
    public static final String FOOTER_DOWN = "footer:down";
    public static final String FOOTER_NEXT = "footer:next";
    public static final String FOOTER_PREVIOUS = "footer:previous";
    public static final String FOOTER_OPEN_SELECTED = "footer:openSelected";
    public static final String FOOTER_CLEAR_SELECTION = "footer:clearSelection";
    public static final String FOOTER_CLOSE = "footer:close";

    // ===== 消息选择 (5) =====
    public static final String MESSAGE_SELECTOR_UP = "messageSelector:up";
    public static final String MESSAGE_SELECTOR_DOWN = "messageSelector:down";
    public static final String MESSAGE_SELECTOR_TOP = "messageSelector:top";
    public static final String MESSAGE_SELECTOR_BOTTOM = "messageSelector:bottom";
    public static final String MESSAGE_SELECTOR_SELECT = "messageSelector:select";

    // ===== 消息操作 (4, v1.65.0 新增) =====
    public static final String MESSAGE_ACTIONS_PREV = "messageActions:prev";
    public static final String MESSAGE_ACTIONS_NEXT = "messageActions:next";
    public static final String MESSAGE_ACTIONS_TOP = "messageActions:top";
    public static final String MESSAGE_ACTIONS_BOTTOM = "messageActions:bottom";

    // ===== Diff (7) =====
    public static final String DIFF_DISMISS = "diff:dismiss";
    public static final String DIFF_PREVIOUS_SOURCE = "diff:previousSource";
    public static final String DIFF_NEXT_SOURCE = "diff:nextSource";
    public static final String DIFF_BACK = "diff:back";
    public static final String DIFF_VIEW_DETAILS = "diff:viewDetails";
    public static final String DIFF_PREVIOUS_FILE = "diff:previousFile";
    public static final String DIFF_NEXT_FILE = "diff:nextFile";

    // ===== 模型选择 (2) =====
    public static final String MODEL_PICKER_DECREASE_EFFORT = "modelPicker:decreaseEffort";
    public static final String MODEL_PICKER_INCREASE_EFFORT = "modelPicker:increaseEffort";

    // ===== 通用选择 (4) =====
    public static final String SELECT_NEXT = "select:next";
    public static final String SELECT_PREVIOUS = "select:previous";
    public static final String SELECT_ACCEPT = "select:accept";
    public static final String SELECT_CANCEL = "select:cancel";

    // ===== 插件 (2) =====
    public static final String PLUGIN_TOGGLE = "plugin:toggle";
    public static final String PLUGIN_INSTALL = "plugin:install";

    // ===== 权限 (1) =====
    public static final String PERMISSION_TOGGLE_DEBUG = "permission:toggleDebug";

    // ===== 设置 (3) =====
    public static final String SETTINGS_SEARCH = "settings:search";
    public static final String SETTINGS_RETRY = "settings:retry";
    public static final String SETTINGS_CLOSE = "settings:close";

    // ===== 语音 (1) =====
    public static final String VOICE_PUSH_TO_TALK = "voice:pushToTalk";

    /** 动作总数 — 97 */
    public static final int TOTAL_ACTION_COUNT = 97;
}
