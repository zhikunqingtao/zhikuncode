package com.aicodeassistant.keybinding;

import com.aicodeassistant.keybinding.vim.VimTypes;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 前端增强黄金测试 — 覆盖 §4.8 键盘绑定 + §4.15 Vim 骨架。
 */
class FrontendEnhancementGoldenTest {

    // ==================== KeybindingContext ====================

    @Nested
    @DisplayName("§4.8 KeybindingContext")
    class ContextTests {

        @Test
        @DisplayName("20 种上下文")
        void has20Contexts() {
            assertEquals(20, KeybindingContext.values().length);
        }

        @Test
        @DisplayName("包含 GLOBAL")
        void hasGlobal() {
            assertNotNull(KeybindingContext.GLOBAL);
        }

        @Test
        @DisplayName("包含 SCROLL (v1.65.0 新增)")
        void hasScroll() {
            assertNotNull(KeybindingContext.SCROLL);
        }

        @Test
        @DisplayName("包含 MESSAGE_ACTIONS (v1.65.0 新增)")
        void hasMessageActions() {
            assertNotNull(KeybindingContext.MESSAGE_ACTIONS);
        }

        @Test
        @DisplayName("包含所有 20 种上下文")
        void allContextsExist() {
            Set<String> expected = Set.of(
                    "GLOBAL", "CHAT", "AUTOCOMPLETE", "CONFIRMATION", "HELP",
                    "TRANSCRIPT", "HISTORY_SEARCH", "TASK", "THEME_PICKER",
                    "SETTINGS", "TABS", "SCROLL", "ATTACHMENTS", "FOOTER",
                    "MESSAGE_SELECTOR", "MESSAGE_ACTIONS", "DIFF_DIALOG",
                    "MODEL_PICKER", "SELECT", "PLUGIN"
            );
            Set<String> actual = Set.of(
                    Stream.of(KeybindingContext.values())
                            .map(Enum::name)
                            .toArray(String[]::new)
            );
            assertEquals(expected, actual);
        }
    }

    // ==================== KeybindingAction ====================

    @Nested
    @DisplayName("§4.8 KeybindingAction — 97 种动作")
    class ActionTests {

        @Test
        @DisplayName("TOTAL_ACTION_COUNT = 97")
        void totalIs97() {
            assertEquals(97, KeybindingAction.TOTAL_ACTION_COUNT);
        }

        @Test
        @DisplayName("实际常量数量与声明一致")
        void constantCountMatches() {
            // 统计所有 public static final String 字段
            long count = Stream.of(KeybindingAction.class.getDeclaredFields())
                    .filter(f -> f.getType() == String.class
                            && java.lang.reflect.Modifier.isStatic(f.getModifiers())
                            && java.lang.reflect.Modifier.isFinal(f.getModifiers()))
                    .count();
            assertEquals(97, count);
        }

        @Test
        @DisplayName("应用级动作 10 个")
        void appActions() {
            assertNotNull(KeybindingAction.APP_INTERRUPT);
            assertNotNull(KeybindingAction.APP_EXIT);
            assertNotNull(KeybindingAction.APP_QUICK_OPEN);
            assertTrue(KeybindingAction.APP_INTERRUPT.startsWith("app:"));
        }

        @Test
        @DisplayName("聊天动作 13 个")
        void chatActions() {
            assertNotNull(KeybindingAction.CHAT_SUBMIT);
            assertNotNull(KeybindingAction.CHAT_CANCEL);
            assertNotNull(KeybindingAction.CHAT_MESSAGE_ACTIONS);
            assertTrue(KeybindingAction.CHAT_SUBMIT.startsWith("chat:"));
        }

        @Test
        @DisplayName("滚动动作 7 个 (v1.65.0)")
        void scrollActions() {
            assertNotNull(KeybindingAction.SCROLL_PAGE_UP);
            assertNotNull(KeybindingAction.SCROLL_PAGE_DOWN);
            assertNotNull(KeybindingAction.SCROLL_LINE_UP);
            assertNotNull(KeybindingAction.SCROLL_LINE_DOWN);
            assertNotNull(KeybindingAction.SCROLL_TOP);
            assertNotNull(KeybindingAction.SCROLL_BOTTOM);
            assertNotNull(KeybindingAction.SELECTION_COPY);
        }

        @Test
        @DisplayName("消息操作动作 4 个 (v1.65.0)")
        void messageActionsActions() {
            assertNotNull(KeybindingAction.MESSAGE_ACTIONS_PREV);
            assertNotNull(KeybindingAction.MESSAGE_ACTIONS_NEXT);
            assertNotNull(KeybindingAction.MESSAGE_ACTIONS_TOP);
            assertNotNull(KeybindingAction.MESSAGE_ACTIONS_BOTTOM);
        }
    }

    // ==================== KeybindingConfig ====================

    @Nested
    @DisplayName("§4.8 KeybindingConfig")
    class ConfigTests {

        @Test
        @DisplayName("record 字段正确")
        void recordFields() {
            var config = new KeybindingConfig("ctrl+c", "app:interrupt",
                    KeybindingContext.GLOBAL, true);
            assertEquals("ctrl+c", config.key());
            assertEquals("app:interrupt", config.action());
            assertEquals(KeybindingContext.GLOBAL, config.context());
            assertTrue(config.when());
        }

        @Test
        @DisplayName("便捷构造默认 when=true")
        void defaultWhen() {
            var config = new KeybindingConfig("enter", "chat:submit",
                    KeybindingContext.CHAT);
            assertTrue(config.when());
        }
    }

    // ==================== ParsedKeystroke ====================

    @Nested
    @DisplayName("§4.8.1 ParsedKeystroke")
    class ParsedKeystrokeTests {

        @Test
        @DisplayName("解析 ctrl+c")
        void parseCtrlC() {
            var p = ParsedKeystroke.parse("ctrl+c");
            assertEquals("c", p.key());
            assertTrue(p.ctrl());
            assertFalse(p.alt());
            assertFalse(p.shift());
        }

        @Test
        @DisplayName("解析 ctrl+shift+k")
        void parseCtrlShiftK() {
            var p = ParsedKeystroke.parse("ctrl+shift+k");
            assertEquals("k", p.key());
            assertTrue(p.ctrl());
            assertTrue(p.shift());
        }

        @Test
        @DisplayName("解析 alt 别名 opt")
        void parseOpt() {
            var p = ParsedKeystroke.parse("opt+v");
            assertTrue(p.alt());
            assertEquals("v", p.key());
        }

        @Test
        @DisplayName("解析 escape 标准化")
        void parseEscape() {
            var p = ParsedKeystroke.parse("esc");
            assertEquals("escape", p.key());
        }

        @Test
        @DisplayName("解析 return -> enter")
        void parseReturn() {
            var p = ParsedKeystroke.parse("return");
            assertEquals("enter", p.key());
        }

        @Test
        @DisplayName("空字符串抛异常")
        void emptyThrows() {
            assertThrows(IllegalArgumentException.class, () -> ParsedKeystroke.parse(""));
        }

        @Test
        @DisplayName("null 抛异常")
        void nullThrows() {
            assertThrows(IllegalArgumentException.class, () -> ParsedKeystroke.parse(null));
        }

        @Test
        @DisplayName("toDisplayText 正确")
        void displayText() {
            var p = ParsedKeystroke.parse("ctrl+shift+k");
            assertEquals("Ctrl+Shift+K", p.toDisplayText());
        }

        @Test
        @DisplayName("toDisplayText 无修饰符")
        void displayTextNoModifiers() {
            var p = ParsedKeystroke.parse("escape");
            assertEquals("Escape", p.toDisplayText());
        }

        @Test
        @DisplayName("matches 正确匹配")
        void matches() {
            var p = ParsedKeystroke.parse("ctrl+c");
            assertTrue(p.matches("c", true, false, false, false));
            assertFalse(p.matches("c", false, false, false, false));
        }
    }

    // ==================== ChordResolveResult ====================

    @Nested
    @DisplayName("§4.8.1 ChordResolveResult")
    class ChordTests {

        @Test
        @DisplayName("Match 包含动作")
        void matchContainsAction() {
            var r = new ChordResolveResult.Match("app:interrupt");
            assertEquals("app:interrupt", r.action());
        }

        @Test
        @DisplayName("5 种结果类型")
        void fiveResultTypes() {
            assertInstanceOf(ChordResolveResult.class, new ChordResolveResult.Match("a"));
            assertInstanceOf(ChordResolveResult.class, new ChordResolveResult.None());
            assertInstanceOf(ChordResolveResult.class, new ChordResolveResult.Unbound());
            assertInstanceOf(ChordResolveResult.class,
                    new ChordResolveResult.ChordStarted(List.of()));
            assertInstanceOf(ChordResolveResult.class, new ChordResolveResult.ChordCancelled());
        }

        @Test
        @DisplayName("sealed interface 密封性")
        void sealedInterface() {
            assertTrue(ChordResolveResult.class.isSealed());
        }
    }

    // ==================== KeybindingManager ====================

    @Nested
    @DisplayName("§4.8 KeybindingManager")
    class ManagerTests {

        private KeybindingManager manager;

        @BeforeEach
        void setUp() {
            manager = new KeybindingManager();
        }

        @Test
        @DisplayName("默认绑定非空")
        void defaultBindingsNotEmpty() {
            assertTrue(manager.getDefaultBindingCount() > 0);
        }

        @Test
        @DisplayName("CHORD_TIMEOUT_MS = 500")
        void chordTimeout() {
            assertEquals(500, KeybindingManager.CHORD_TIMEOUT_MS);
        }

        @Test
        @DisplayName("保留键包含 ctrl+c/d/m")
        void reservedKeys() {
            assertTrue(KeybindingManager.RESERVED_KEYS.contains("ctrl+c"));
            assertTrue(KeybindingManager.RESERVED_KEYS.contains("ctrl+d"));
            assertTrue(KeybindingManager.RESERVED_KEYS.contains("ctrl+m"));
        }

        @Test
        @DisplayName("resolve 找到 enter -> chat:submit")
        void resolveEnter() {
            Optional<String> action = manager.resolve("enter", KeybindingContext.CHAT);
            assertTrue(action.isPresent());
            assertEquals("chat:submit", action.get());
        }

        @Test
        @DisplayName("resolve 找到 escape -> chat:cancel")
        void resolveEscape() {
            Optional<String> action = manager.resolve("escape", KeybindingContext.CHAT);
            assertTrue(action.isPresent());
            assertEquals("chat:cancel", action.get());
        }

        @Test
        @DisplayName("resolve 未知键返回 empty")
        void resolveUnknown() {
            assertTrue(manager.resolve("f12", KeybindingContext.GLOBAL).isEmpty());
        }

        @Test
        @DisplayName("getDisplayText 返回按键")
        void getDisplayText() {
            Optional<String> text = manager.getDisplayText("chat:submit", KeybindingContext.CHAT);
            assertTrue(text.isPresent());
            assertEquals("enter", text.get());
        }

        @Test
        @DisplayName("getAllBindings 包含默认")
        void getAllBindings() {
            List<KeybindingConfig> all = manager.getAllBindings();
            assertFalse(all.isEmpty());
            assertTrue(all.stream().anyMatch(b -> b.action().equals("chat:submit")));
        }

        @Test
        @DisplayName("初始和弦状态为空")
        void initialChordEmpty() {
            assertTrue(manager.getPendingChord().isEmpty());
        }

        @Test
        @DisplayName("resetChord 重置和弦")
        void resetChord() {
            manager.resetChord();
            assertTrue(manager.getPendingChord().isEmpty());
        }

        @Test
        @DisplayName("validate 检测保留键")
        void validateReservedKey() {
            var bindings = List.of(
                    new KeybindingConfig("ctrl+c", "custom:action", KeybindingContext.GLOBAL));
            var warnings = manager.validate(bindings);
            assertFalse(warnings.isEmpty());
            assertTrue(warnings.stream().anyMatch(
                    w -> w.type() == KeybindingManager.ValidationWarning.Type.RESERVED));
        }

        @Test
        @DisplayName("validate 检测重复绑定")
        void validateDuplicate() {
            var bindings = List.of(
                    new KeybindingConfig("enter", "a", KeybindingContext.CHAT),
                    new KeybindingConfig("enter", "b", KeybindingContext.CHAT));
            var warnings = manager.validate(bindings);
            assertTrue(warnings.stream().anyMatch(
                    w -> w.type() == KeybindingManager.ValidationWarning.Type.DUPLICATE));
        }

        @Test
        @DisplayName("validate 检测解析错误")
        void validateParseError() {
            var bindings = List.of(
                    new KeybindingConfig("", "some:action", KeybindingContext.GLOBAL));
            var warnings = manager.validate(bindings);
            assertTrue(warnings.stream().anyMatch(
                    w -> w.type() == KeybindingManager.ValidationWarning.Type.PARSE_ERROR));
        }

        @Test
        @DisplayName("ValidationWarning 5 种类型")
        void warningTypes() {
            assertEquals(5, KeybindingManager.ValidationWarning.Type.values().length);
        }
    }

    // ==================== VimTypes (P2 骨架) ====================

    @Nested
    @DisplayName("§4.15 VimTypes (P2 骨架)")
    class VimTests {

        @Test
        @DisplayName("Operator 3 种")
        void operators() {
            assertEquals(3, VimTypes.Operator.values().length);
            assertNotNull(VimTypes.Operator.DELETE);
            assertNotNull(VimTypes.Operator.CHANGE);
            assertNotNull(VimTypes.Operator.YANK);
        }

        @Test
        @DisplayName("FindType 4 种")
        void findTypes() {
            assertEquals(4, VimTypes.FindType.values().length);
        }

        @Test
        @DisplayName("TextObjScope 2 种")
        void textObjScopes() {
            assertEquals(2, VimTypes.TextObjScope.values().length);
        }

        @Test
        @DisplayName("VimState sealed interface")
        void vimStateSealed() {
            assertTrue(VimTypes.VimState.class.isSealed());
        }

        @Test
        @DisplayName("InsertMode 包含 insertedText")
        void insertMode() {
            var state = new VimTypes.VimState.InsertMode("hello");
            assertEquals("hello", state.insertedText());
        }

        @Test
        @DisplayName("NormalMode 包含 CommandState")
        void normalMode() {
            var state = new VimTypes.VimState.NormalMode(new VimTypes.CommandState.Idle());
            assertInstanceOf(VimTypes.CommandState.Idle.class, state.command());
        }

        @Test
        @DisplayName("CommandState sealed interface 有 11 种")
        void commandStateSealed() {
            assertTrue(VimTypes.CommandState.class.isSealed());
            assertEquals(11, VimTypes.CommandState.class.getPermittedSubclasses().length);
        }

        @Test
        @DisplayName("PersistentState.initial() 创建初始状态")
        void persistentStateInitial() {
            var ps = VimTypes.PersistentState.initial();
            assertNull(ps.lastChange());
            assertNull(ps.lastFind());
            assertEquals("", ps.register());
            assertFalse(ps.registerIsLinewise());
        }

        @Test
        @DisplayName("RecordedChange sealed interface")
        void recordedChangeSealed() {
            assertTrue(VimTypes.RecordedChange.class.isSealed());
        }

        @Test
        @DisplayName("MAX_VIM_COUNT = 10000")
        void maxVimCount() {
            assertEquals(10000, VimTypes.MAX_VIM_COUNT);
        }
    }
}
