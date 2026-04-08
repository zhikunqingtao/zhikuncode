package com.aicodeassistant.keybinding.vim;

/**
 * Vim 编辑模式类型定义 — P2 骨架。
 * <p>
 * 优先级: P2（本版本不实现完整功能，保留类型定义作为源码参考）
 * <p>
 * 基于 src/vim/ 目录（5 个文件 ~1500 行）的完整源码分析。
 * 纯状态机 + 纯函数算法设计，无副作用，便于测试和 undo。
 *
 * @see <a href="SPEC §4.15">Vim 编辑模式</a>
 */
public final class VimTypes {

    private VimTypes() {}

    // ==================== Vim 运算符 ====================

    /** Vim 运算符 */
    public enum Operator {
        DELETE, CHANGE, YANK
    }

    /** 查找方向 */
    public enum FindType {
        f, F, t, T  // f=正向到字符 F=反向 t=正向到字符前 T=反向到字符后
    }

    /** 文本对象范围 */
    public enum TextObjScope {
        INNER, AROUND
    }

    // ==================== Vim 状态 ====================

    /**
     * Vim 状态 — 顶层联合类型。
     * INSERT 模式追踪已输入文本（用于 dot-repeat）。
     * NORMAL 模式追踪命令解析状态机。
     */
    public sealed interface VimState {
        record InsertMode(String insertedText) implements VimState {}
        record NormalMode(CommandState command) implements VimState {}
    }

    /**
     * 命令状态机 — NORMAL 模式下的 12 种状态。
     */
    public sealed interface CommandState {
        record Idle() implements CommandState {}
        record Count(String digits) implements CommandState {}
        record OperatorPending(Operator op, int count) implements CommandState {}
        record OperatorCount(Operator op, int count, String digits) implements CommandState {}
        record OperatorFind(Operator op, int count, FindType find) implements CommandState {}
        record OperatorTextObj(Operator op, int count, TextObjScope scope) implements CommandState {}
        record FindPending(FindType find, int count) implements CommandState {}
        record GPending(int count) implements CommandState {}
        record OperatorGPending(Operator op, int count) implements CommandState {}
        record ReplacePending(int count) implements CommandState {}
        record IndentPending(char dir, int count) implements CommandState {}
    }

    // ==================== 持久状态 ====================

    /** 查找记录 */
    public record FindRecord(FindType type, char ch) {}

    /** 持久状态 — 跨命令保留 */
    public record PersistentState(
            RecordedChange lastChange,
            FindRecord lastFind,
            String register,
            boolean registerIsLinewise
    ) {
        public static PersistentState initial() {
            return new PersistentState(null, null, "", false);
        }
    }

    // ==================== 录制的变更 ====================

    /** 录制的变更 — dot-repeat 回放所需 */
    public sealed interface RecordedChange {
        record Insert(String text) implements RecordedChange {}
        record OperatorMotion(Operator op, String motion, int count) implements RecordedChange {}
        record OperatorTextObjChange(Operator op, String objType, TextObjScope scope, int count) implements RecordedChange {}
        record OperatorFindChange(Operator op, FindType find, char ch, int count) implements RecordedChange {}
        record Replace(char ch, int count) implements RecordedChange {}
        record DeleteChar(int count) implements RecordedChange {}
        record ToggleCase(int count) implements RecordedChange {}
        record Indent(char dir, int count) implements RecordedChange {}
        record OpenLine(String direction) implements RecordedChange {}
        record Join(int count) implements RecordedChange {}
    }

    /** 状态转移最大计数 */
    public static final int MAX_VIM_COUNT = 10000;
}
