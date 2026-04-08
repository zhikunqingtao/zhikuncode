package com.aicodeassistant.keybinding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Stream;

/**
 * 键盘绑定管理器 — 管理默认绑定和用户自定义绑定。
 * <p>
 * 对应源码: src/keybindings/
 * <p>
 * 功能:
 * 1. 加载默认绑定（平台相关）
 * 2. 加载用户自定义绑定（覆盖默认）
 * 3. 按键解析 -> 匹配动作
 * 4. 和弦序列状态机
 * 5. 验证管线（5 种警告类型）
 *
 * @see <a href="SPEC §4.8">键盘绑定系统</a>
 */
@Service
public class KeybindingManager {

    private static final Logger log = LoggerFactory.getLogger(KeybindingManager.class);

    /** 和弦超时 (毫秒) */
    static final long CHORD_TIMEOUT_MS = 500;

    /** 保留快捷键 — 不可重绑定 */
    static final Set<String> RESERVED_KEYS = Set.of("ctrl+c", "ctrl+d", "ctrl+m");

    /** 终端保留 — 警告级别 */
    static final Set<String> TERMINAL_RESERVED_KEYS = Set.of("ctrl+z", "ctrl+\\");

    private List<KeybindingConfig> defaultBindings;
    private List<KeybindingConfig> userBindings;
    private List<ParsedKeystroke> pendingChord;

    public KeybindingManager() {
        this.defaultBindings = loadDefaultBindings();
        this.userBindings = new ArrayList<>();
        this.pendingChord = null;
    }

    /**
     * 加载默认 + 用户自定义绑定。
     */
    public void loadBindings() {
        defaultBindings = loadDefaultBindings();
        userBindings = loadUserBindings();
        log.info("Loaded {} default + {} user keybindings",
                defaultBindings.size(), userBindings.size());
    }

    /**
     * 解析按键事件 -> 匹配动作。
     * <p>
     * 用户绑定优先于默认绑定。
     */
    public Optional<String> resolve(String key, KeybindingContext context) {
        return Stream.concat(userBindings.stream(), defaultBindings.stream())
                .filter(b -> b.when() && b.key().equals(key) && b.context() == context)
                .map(KeybindingConfig::action)
                .findFirst();
    }

    /**
     * 带和弦状态的按键解析。
     */
    public ChordResolveResult resolveWithChord(String key, KeybindingContext context) {
        ParsedKeystroke keystroke = ParsedKeystroke.parse(key);

        if (pendingChord == null) {
            // 检查是否为和弦前缀
            if (isChordPrefix(key, context)) {
                pendingChord = new ArrayList<>();
                pendingChord.add(keystroke);
                return new ChordResolveResult.ChordStarted(List.copyOf(pendingChord));
            }
            // 精确匹配
            return resolve(key, context)
                    .map(action -> (ChordResolveResult) new ChordResolveResult.Match(action))
                    .orElse(new ChordResolveResult.None());
        } else {
            // 和弦状态中
            if ("escape".equals(keystroke.key())) {
                pendingChord = null;
                return new ChordResolveResult.ChordCancelled();
            }

            pendingChord.add(keystroke);
            String chordKey = buildChordKey(pendingChord);

            // 检查精确匹配
            Optional<String> action = resolve(chordKey, context);
            if (action.isPresent()) {
                pendingChord = null;
                return new ChordResolveResult.Match(action.get());
            }

            // 检查是否为更长和弦的前缀
            if (isChordPrefix(chordKey, context)) {
                return new ChordResolveResult.ChordStarted(List.copyOf(pendingChord));
            }

            // 无匹配，取消
            pendingChord = null;
            return new ChordResolveResult.ChordCancelled();
        }
    }

    /**
     * 获取动作的显示文本。
     */
    public Optional<String> getDisplayText(String action, KeybindingContext context) {
        return Stream.concat(userBindings.stream(), defaultBindings.stream())
                .filter(b -> b.action().equals(action) && b.context() == context)
                .map(KeybindingConfig::key)
                .findFirst();
    }

    /**
     * 获取所有绑定（合并后）。
     */
    public List<KeybindingConfig> getAllBindings() {
        List<KeybindingConfig> merged = new ArrayList<>(defaultBindings);
        // 用户绑定覆盖
        for (KeybindingConfig ub : userBindings) {
            merged.removeIf(b -> b.context() == ub.context()
                    && b.action().equals(ub.action()));
            merged.add(ub);
        }
        return Collections.unmodifiableList(merged);
    }

    /**
     * 获取默认绑定数量。
     */
    public int getDefaultBindingCount() {
        return defaultBindings.size();
    }

    /**
     * 获取当前和弦状态。
     */
    public List<ParsedKeystroke> getPendingChord() {
        return pendingChord != null ? List.copyOf(pendingChord) : List.of();
    }

    /**
     * 重置和弦状态。
     */
    public void resetChord() {
        pendingChord = null;
    }

    /**
     * 验证用户绑定。
     */
    public List<ValidationWarning> validate(List<KeybindingConfig> bindings) {
        List<ValidationWarning> warnings = new ArrayList<>();

        for (KeybindingConfig b : bindings) {
            // 保留键检查
            if (RESERVED_KEYS.contains(b.key())) {
                warnings.add(new ValidationWarning(
                        ValidationWarning.Type.RESERVED, b.key(),
                        "Reserved key cannot be rebound: " + b.key()));
            }
            // 终端保留键警告
            if (TERMINAL_RESERVED_KEYS.contains(b.key())) {
                warnings.add(new ValidationWarning(
                        ValidationWarning.Type.RESERVED, b.key(),
                        "Terminal reserved key (may not work): " + b.key()));
            }
            // 解析检查
            try {
                ParsedKeystroke.parse(b.key());
            } catch (IllegalArgumentException e) {
                warnings.add(new ValidationWarning(
                        ValidationWarning.Type.PARSE_ERROR, b.key(), e.getMessage()));
            }
        }

        // 重复检查
        Map<String, List<KeybindingConfig>> byContextKey = new HashMap<>();
        for (KeybindingConfig b : bindings) {
            String ck = b.context() + ":" + b.key();
            byContextKey.computeIfAbsent(ck, k -> new ArrayList<>()).add(b);
        }
        byContextKey.values().stream()
                .filter(list -> list.size() > 1)
                .forEach(list -> warnings.add(new ValidationWarning(
                        ValidationWarning.Type.DUPLICATE, list.get(0).key(),
                        "Duplicate binding in " + list.get(0).context())));

        return warnings;
    }

    // ==================== 内部方法 ====================

    private boolean isChordPrefix(String key, KeybindingContext context) {
        String prefix = key + " ";
        return Stream.concat(userBindings.stream(), defaultBindings.stream())
                .filter(b -> b.context() == context)
                .anyMatch(b -> b.key().startsWith(prefix));
    }

    private String buildChordKey(List<ParsedKeystroke> keystrokes) {
        return keystrokes.stream()
                .map(ParsedKeystroke::toDisplayText)
                .map(String::toLowerCase)
                .reduce((a, b) -> a + " " + b)
                .orElse("");
    }

    /**
     * 加载默认绑定 — 平台相关。
     */
    List<KeybindingConfig> loadDefaultBindings() {
        List<KeybindingConfig> bindings = new ArrayList<>();

        // ===== GLOBAL =====
        bindings.add(new KeybindingConfig("ctrl+c", KeybindingAction.APP_INTERRUPT, KeybindingContext.GLOBAL));
        bindings.add(new KeybindingConfig("ctrl+shift+t", KeybindingAction.APP_TOGGLE_TODOS, KeybindingContext.GLOBAL));
        bindings.add(new KeybindingConfig("ctrl+shift+o", KeybindingAction.APP_TOGGLE_TRANSCRIPT, KeybindingContext.GLOBAL));
        bindings.add(new KeybindingConfig("ctrl+shift+b", KeybindingAction.APP_TOGGLE_BRIEF, KeybindingContext.GLOBAL));
        bindings.add(new KeybindingConfig("ctrl+shift+p", KeybindingAction.APP_QUICK_OPEN, KeybindingContext.GLOBAL));
        bindings.add(new KeybindingConfig("ctrl+shift+f", KeybindingAction.APP_GLOBAL_SEARCH, KeybindingContext.GLOBAL));

        // ===== CHAT =====
        bindings.add(new KeybindingConfig("escape", KeybindingAction.CHAT_CANCEL, KeybindingContext.CHAT));
        bindings.add(new KeybindingConfig("enter", KeybindingAction.CHAT_SUBMIT, KeybindingContext.CHAT));
        bindings.add(new KeybindingConfig("shift+enter", KeybindingAction.CHAT_NEWLINE, KeybindingContext.CHAT));
        bindings.add(new KeybindingConfig("ctrl+z", KeybindingAction.CHAT_UNDO, KeybindingContext.CHAT));
        bindings.add(new KeybindingConfig("ctrl+k", KeybindingAction.CHAT_MODEL_PICKER, KeybindingContext.CHAT));
        bindings.add(new KeybindingConfig("ctrl+l", KeybindingAction.CHAT_KILL_AGENTS, KeybindingContext.CHAT));
        bindings.add(new KeybindingConfig("shift+tab", KeybindingAction.CHAT_CYCLE_MODE, KeybindingContext.CHAT));
        bindings.add(new KeybindingConfig("ctrl+shift+s", KeybindingAction.CHAT_STASH, KeybindingContext.CHAT));
        bindings.add(new KeybindingConfig("ctrl+shift+r", KeybindingAction.HISTORY_SEARCH, KeybindingContext.CHAT));

        // ===== CONFIRMATION =====
        bindings.add(new KeybindingConfig("y", KeybindingAction.CONFIRM_YES, KeybindingContext.CONFIRMATION));
        bindings.add(new KeybindingConfig("n", KeybindingAction.CONFIRM_NO, KeybindingContext.CONFIRMATION));
        bindings.add(new KeybindingConfig("up", KeybindingAction.CONFIRM_PREVIOUS, KeybindingContext.CONFIRMATION));
        bindings.add(new KeybindingConfig("down", KeybindingAction.CONFIRM_NEXT, KeybindingContext.CONFIRMATION));
        bindings.add(new KeybindingConfig("tab", KeybindingAction.CONFIRM_NEXT_FIELD, KeybindingContext.CONFIRMATION));
        bindings.add(new KeybindingConfig("shift+tab", KeybindingAction.CONFIRM_CYCLE_MODE, KeybindingContext.CONFIRMATION));

        // ===== AUTOCOMPLETE =====
        bindings.add(new KeybindingConfig("tab", KeybindingAction.AUTOCOMPLETE_ACCEPT, KeybindingContext.AUTOCOMPLETE));
        bindings.add(new KeybindingConfig("escape", KeybindingAction.AUTOCOMPLETE_DISMISS, KeybindingContext.AUTOCOMPLETE));
        bindings.add(new KeybindingConfig("up", KeybindingAction.AUTOCOMPLETE_PREVIOUS, KeybindingContext.AUTOCOMPLETE));
        bindings.add(new KeybindingConfig("down", KeybindingAction.AUTOCOMPLETE_NEXT, KeybindingContext.AUTOCOMPLETE));

        // ===== HISTORY_SEARCH =====
        bindings.add(new KeybindingConfig("up", KeybindingAction.HISTORY_SEARCH_NEXT, KeybindingContext.HISTORY_SEARCH));
        bindings.add(new KeybindingConfig("enter", KeybindingAction.HISTORY_SEARCH_ACCEPT, KeybindingContext.HISTORY_SEARCH));
        bindings.add(new KeybindingConfig("escape", KeybindingAction.HISTORY_SEARCH_CANCEL, KeybindingContext.HISTORY_SEARCH));
        bindings.add(new KeybindingConfig("ctrl+enter", KeybindingAction.HISTORY_SEARCH_EXECUTE, KeybindingContext.HISTORY_SEARCH));

        // ===== SCROLL (v1.65.0) =====
        bindings.add(new KeybindingConfig("pageup", KeybindingAction.SCROLL_PAGE_UP, KeybindingContext.SCROLL));
        bindings.add(new KeybindingConfig("pagedown", KeybindingAction.SCROLL_PAGE_DOWN, KeybindingContext.SCROLL));
        bindings.add(new KeybindingConfig("shift+up", KeybindingAction.SCROLL_LINE_UP, KeybindingContext.SCROLL));
        bindings.add(new KeybindingConfig("shift+down", KeybindingAction.SCROLL_LINE_DOWN, KeybindingContext.SCROLL));
        bindings.add(new KeybindingConfig("home", KeybindingAction.SCROLL_TOP, KeybindingContext.SCROLL));
        bindings.add(new KeybindingConfig("end", KeybindingAction.SCROLL_BOTTOM, KeybindingContext.SCROLL));
        bindings.add(new KeybindingConfig("ctrl+c", KeybindingAction.SELECTION_COPY, KeybindingContext.SCROLL));

        // ===== HELP =====
        bindings.add(new KeybindingConfig("escape", KeybindingAction.HELP_DISMISS, KeybindingContext.HELP));

        // ===== TABS =====
        bindings.add(new KeybindingConfig("right", KeybindingAction.TABS_NEXT, KeybindingContext.TABS));
        bindings.add(new KeybindingConfig("left", KeybindingAction.TABS_PREVIOUS, KeybindingContext.TABS));

        // ===== SELECT =====
        bindings.add(new KeybindingConfig("down", KeybindingAction.SELECT_NEXT, KeybindingContext.SELECT));
        bindings.add(new KeybindingConfig("up", KeybindingAction.SELECT_PREVIOUS, KeybindingContext.SELECT));
        bindings.add(new KeybindingConfig("enter", KeybindingAction.SELECT_ACCEPT, KeybindingContext.SELECT));
        bindings.add(new KeybindingConfig("escape", KeybindingAction.SELECT_CANCEL, KeybindingContext.SELECT));

        // ===== DIFF =====
        bindings.add(new KeybindingConfig("escape", KeybindingAction.DIFF_DISMISS, KeybindingContext.DIFF_DIALOG));
        bindings.add(new KeybindingConfig("left", KeybindingAction.DIFF_PREVIOUS_SOURCE, KeybindingContext.DIFF_DIALOG));
        bindings.add(new KeybindingConfig("right", KeybindingAction.DIFF_NEXT_SOURCE, KeybindingContext.DIFF_DIALOG));
        bindings.add(new KeybindingConfig("[", KeybindingAction.DIFF_PREVIOUS_FILE, KeybindingContext.DIFF_DIALOG));
        bindings.add(new KeybindingConfig("]", KeybindingAction.DIFF_NEXT_FILE, KeybindingContext.DIFF_DIALOG));

        // ===== SETTINGS =====
        bindings.add(new KeybindingConfig("ctrl+f", KeybindingAction.SETTINGS_SEARCH, KeybindingContext.SETTINGS));
        bindings.add(new KeybindingConfig("escape", KeybindingAction.SETTINGS_CLOSE, KeybindingContext.SETTINGS));

        return bindings;
    }

    /**
     * 加载用户自定义绑定 — 从配置文件。
     */
    List<KeybindingConfig> loadUserBindings() {
        // P1: 桩实现 — 返回空列表
        // 实际实现从 ~/.config/.../keybindings.json 加载
        return new ArrayList<>();
    }

    // ==================== 验证警告 ====================

    /** 验证警告 */
    public record ValidationWarning(Type type, String key, String message) {
        public enum Type {
            PARSE_ERROR,     // 按键语法错误
            DUPLICATE,       // 同上下文内重复绑定
            RESERVED,        // 保留快捷键
            INVALID_CONTEXT, // 未知上下文
            INVALID_ACTION   // 无效动作
        }
    }
}
