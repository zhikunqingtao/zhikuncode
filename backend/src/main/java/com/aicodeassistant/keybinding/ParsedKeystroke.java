package com.aicodeassistant.keybinding;

/**
 * 解析后的按键 — 将字符串 "ctrl+shift+k" 解析为结构化数据。
 * <p>
 * 对应源码: src/keybindings/parser.ts ParsedKeystroke
 * <p>
 * 修饰符别名映射:
 * - ctrl/control -> ctrl
 * - alt/opt/option/meta -> alt
 * - cmd/command/super/win -> super
 * - shift -> shift
 * <p>
 * 特殊键名标准化:
 * - esc/escape -> "escape", return -> "enter", space -> " "
 *
 * @see <a href="SPEC §4.8.1">按键解析</a>
 */
public record ParsedKeystroke(
        String key,         // 标准化键名 (小写)
        boolean ctrl,       // Ctrl 修饰符
        boolean alt,        // Alt/Option 修饰符
        boolean shift,      // Shift 修饰符
        boolean meta,       // Meta 修饰符
        boolean superKey    // Super/Cmd 修饰符 (Kitty 协议独有)
) {

    /**
     * 从字符串解析按键组合。
     * <p>
     * 例: "ctrl+shift+k" -> ParsedKeystroke("k", true, false, true, false, false)
     */
    public static ParsedKeystroke parse(String combo) {
        if (combo == null || combo.isBlank()) {
            throw new IllegalArgumentException("Key combo cannot be empty");
        }

        String[] parts = combo.toLowerCase().split("\\+");
        boolean ctrl = false, alt = false, shift = false, meta = false, superKey = false;
        String key = "";

        for (String part : parts) {
            String p = part.trim();
            switch (p) {
                case "ctrl", "control" -> ctrl = true;
                case "alt", "opt", "option" -> alt = true;
                case "shift" -> shift = true;
                case "meta" -> { alt = true; meta = true; }
                case "cmd", "command", "super", "win" -> superKey = true;
                default -> key = normalizeKeyName(p);
            }
        }

        if (key.isEmpty()) {
            throw new IllegalArgumentException("No key found in combo: " + combo);
        }

        return new ParsedKeystroke(key, ctrl, alt, shift, meta, superKey);
    }

    /**
     * 标准化键名。
     */
    static String normalizeKeyName(String name) {
        return switch (name) {
            case "esc", "escape" -> "escape";
            case "return" -> "enter";
            case "space", " " -> " ";
            case "\u2191" -> "up";
            case "\u2193" -> "down";
            case "\u2190" -> "left";
            case "\u2192" -> "right";
            default -> name;
        };
    }

    /**
     * 转换为显示文本。
     */
    public String toDisplayText() {
        StringBuilder sb = new StringBuilder();
        if (ctrl) sb.append("Ctrl+");
        if (alt) sb.append("Alt+");
        if (shift) sb.append("Shift+");
        if (superKey) sb.append("Super+");
        sb.append(key.length() == 1 ? key.toUpperCase() : capitalize(key));
        return sb.toString();
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * 检查是否与给定按键事件匹配。
     */
    public boolean matches(String eventKey, boolean eventCtrl, boolean eventAlt,
                           boolean eventShift, boolean eventMeta) {
        // Escape 特殊处理: 终端中 Escape 设置 meta=true，忽略 meta 检查
        if ("escape".equals(key)) {
            return key.equals(eventKey.toLowerCase()) && ctrl == eventCtrl
                    && alt == eventAlt && shift == eventShift;
        }
        return key.equals(eventKey.toLowerCase()) && ctrl == eventCtrl
                && (alt == eventAlt || meta == eventMeta) && shift == eventShift;
    }
}
