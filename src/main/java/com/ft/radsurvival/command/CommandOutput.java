package com.ft.radsurvival.command;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import org.bukkit.command.CommandSender;

/**
 * 命令输出排版工具（命令层优化）：统一「标题行 / 键值对齐 / 状态徽标 / 结论块」四类输出，
 * 避免各 handle* 方法各自拼接颜色码与缩进导致口径漂移。
 *
 * <p>纯字符串渲染，不持有状态（玩家级命令按实例计）。</p>
 */
final class CommandOutput {

    static final String INDENT = "  ";

    private final CommandSender sender;
    /** 键名（去颜色码后）的最大显示宽度，用于对齐「键 …… 值」。 */
    private int keyWidth;

    CommandOutput(CommandSender sender, int keyWidth) {
        this.sender = sender;
        this.keyWidth = Math.max(6, keyWidth);
    }

    /** 宽度按一组键名自适应。 */
    static CommandOutput of(CommandSender sender, String... keys) {
        int w = 6;
        for (String key : keys) {
            w = Math.max(w, displayWidth(key));
        }
        return new CommandOutput(sender, w);
    }

    /** 标题行：`—— RadSurvival / status ——`（§6 金色）。 */
    void header(String text) {
        raw("§6—— " + text + " ——");
    }

    /** 分组小标题：`▸ 昼夜`。 */
    void section(String text) {
        raw("§8▸ §7" + text);
    }

    /** 对齐键值行：`  露天判定      露天（头顶可见天空）`。 */
    void kv(String key, String value) {
        raw(INDENT + "§7" + pad(key) + " §f" + value);
    }

    /** 无键的续行（缩进对齐到值列）。 */
    void line(String text) {
        raw(INDENT + "§8" + pad("") + "§7" + text);
    }

    /** 分隔/收尾横线。 */
    void rule() {
        raw("§8" + "-".repeat(Math.min(48, keyWidth + 30)));
    }

    void raw(String text) {
        sender.sendMessage(text);
    }

    /** 结论行：`  结论  ✔ 已结算 1.013 点`。 */
    void verdict(String mark, String coloredText) {
        raw(INDENT + mark + " " + coloredText);
    }

    private String pad(String key) {
        int w = displayWidth(key);
        return key + " ".repeat(Math.max(0, keyWidth - w));
    }

    /**
     * 字体宽度修正表（Minecraft 默认字体实测）：这些符号不在 CJK 区间但占 2 列，
     * 若按 1 列计会导致后续所有行的对齐漂移。
     */
    private static final String WIDE_SYMBOLS = "▲△▸●◆✔✖▓░";

    /** 单字符显示宽度。 */
    private static int charWidth(char c) {
        if ((c >= 0x2E80 && c <= 0xA4CF) || (c >= 0xFF00 && c <= 0xFF60)) {
            return 2;
        }
        return WIDE_SYMBOLS.indexOf(c) >= 0 ? 2 : 1;
    }

    /**
     * 显示宽度：CJK/全角与上表符号按 2 列计，其余按 1 列。颜色码（§x）不计入。
     */
    static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§') {
                i++; // 跳过颜色码
                continue;
            }
            w += charWidth(c);
        }
        return w;
    }

    /**
     * 腐蚀条（命令层管理读数口径）：10 格，按 warn/danger 阶段变色，数值保留两位小数
     * （与 PDC 落盘精度一致，便于 set/add 后精确核对）。
     * 例：`▓▓▓▓▓▓▓░░░ 67.00%`
     */
    static String corrosionBar(double value, double warn, double danger) {
        double clamped = Math.max(0.0, Math.min(100.0, value));
        int filled = (int) Math.round(clamped / 10.0);
        String color = clamped >= danger ? "§c" : clamped >= warn ? "§e" : "§f";
        return "§8" + "▓".repeat(filled) + "░".repeat(10 - filled)
                + " " + color + String.format(Locale.ROOT, "%.2f%%", clamped) + " ";
    }

    /** 辐射等级条（无遮挡时按 timeFactor 比例显示，与 HUD ▲ 口径一致）。 */
    static String exposureBar(double timeFactor) {
        int n = (int) Math.round(Math.max(0.0, Math.min(1.0, timeFactor)) * 5);
        return "§6" + "▲".repeat(n) + "§8" + "△".repeat(5 - n);
    }

    /** 数值瘦身：整数值不显示小数点（1.0 → 1，0.5 → 0.5）。 */
    static String fmt(double value) {
        if (!Double.isFinite(value)) {
            return String.valueOf(value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** 固定小数位（Locale 无关）。 */
    static String fixed(double value, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f", value);
    }

    static String percent(double ratio) {
        return fixed(ratio * 100.0, 0) + "%";
    }

    /** 表格化：把若干 (键, 值) 按列对齐（用于多世界摘要）。 */
    static List<String> table(List<String[]> rows) {
        int width = 0;
        for (String[] row : rows) {
            width = Math.max(width, displayWidth(row[0]));
        }
        final int w = width;
        return rows.stream()
                .map(row -> INDENT + "§7" + row[0] + " ".repeat(Math.max(0, w - displayWidth(row[0])))
                        + " §f" + row[1])
                .toList();
    }
}
