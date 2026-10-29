package com.ft.radsurvival.command;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CommandOutput} 的显示宽度与对齐口径（bug hunt P2-6）。
 *
 * <p>此前 {@code command} 包**完全没有单元测试**——对齐逻辑是服主唯一能直接看到的东西，
 * 却一直没被钉住。本类补上最小但有效的护栏。</p>
 *
 * <p>sender 用动态 {@link Proxy} 实现：只拦 {@code sendMessage}，其余方法返回默认值。
 * 这样 Paper 给 {@code CommandSender} 加多少方法都不会让本测试编译失败。</p>
 */
class CommandOutputWidthTest {

    /** 收集 sendMessage 输出的假 sender。 */
    private static List<String> capture(CommandOutputBuilder b) {
        List<String> out = new ArrayList<>();
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(
                CommandOutputWidthTest.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> {
                    if ("sendMessage".equals(method.getName()) && args != null && args.length > 0) {
                        if (args[0] instanceof String s) {
                            out.add(s);
                        } else if (args[0] instanceof String[] arr) {
                            out.addAll(List.of(arr));
                        }
                        return null;
                    }
                    // 其余方法：void→null，boolean→false，其余→null
                    return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                });
        b.build(sender);
        return out;
    }

    @FunctionalInterface
    private interface CommandOutputBuilder {
        void build(CommandSender sender);
    }

    @Nested
    @DisplayName("字符宽度：只把真正的全角码位算 2 列")
    class CharWidth {

        @Test
        @DisplayName("中文（CJK 统一表意文字）算 2 列")
        void chineseIsWide() {
            assertThat(CommandOutput.displayWidth("目标玩家")).isEqualTo(8);
            assertThat(CommandOutput.displayWidth("腐蚀")).isEqualTo(4);
        }

        @Test
        @DisplayName("ASCII 算 1 列")
        void asciiIsNarrow() {
            assertThat(CommandOutput.displayWidth("hello")).isEqualTo(5);
            assertThat(CommandOutput.displayWidth("a1_")).isEqualTo(3);
        }

        @Test
        @DisplayName("颜色码不占宽度（§ 后整字符跳过）")
        void colorCodesAreFree() {
            assertThat(CommandOutput.displayWidth("§a目标")).isEqualTo(4);
            assertThat(CommandOutput.displayWidth("§c§labc")).isEqualTo(3);
        }

        @Test
        @DisplayName("HUD 符号按 2 列（▲◆● 等在 Minecraft 里确实更宽）")
        void wideSymbols() {
            assertThat(CommandOutput.displayWidth("▲")).isEqualTo(2);
            assertThat(CommandOutput.displayWidth("◆")).isEqualTo(2);
            assertThat(CommandOutput.displayWidth("░▓")).isEqualTo(4);
        }

        @Test
        @DisplayName("P2-6 修复点：彝文区间（U+A000）不再被误算 2 列")
        void yiSyllablesNoLongerWide() {
            // U+A000 起是彝文音节，Minecraft 默认字体里是 1 列；
            // 旧实现的 0x2E80–0xA4CF 区间把它算成 2 列，导致整列错位。
            assertThat(CommandOutput.displayWidth("\uA000"))
                    .as("彝文应当是 1 列（旧实现算 2 列）")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("P2-6 修复点：CJK 标点仍算 2 列")
        void cjkPunctuationStillWide() {
            assertThat(CommandOutput.displayWidth("。")).isEqualTo(2);
            assertThat(CommandOutput.displayWidth("、")).isEqualTo(2);
        }

        @Test
        @DisplayName("全角形式（U+FF01+）算 2 列")
        void fullwidthFormsWide() {
            assertThat(CommandOutput.displayWidth("Ａ")).isEqualTo(2);
            assertThat(CommandOutput.displayWidth("！")).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("对齐：键宽不同也必须让值列对齐")
    class Alignment {

        @Test
        @DisplayName("中文键与 ASCII 键混排：三行总显示宽度一致（按显示宽度补齐，非 char 数）")
        void padsByDisplayWidthNotCharCount() {
            // 键宽分别 8 / 4 / 2 列，值都是 1 列。
            // 若按 char 数补齐，三行宽度必然不同；按显示宽度补齐才相等。
            List<String> lines = capture(sender -> {
                CommandOutput out = CommandOutput.of(sender, "目标玩家", "世界", "id");
                out.kv("目标玩家", "A");
                out.kv("世界", "B");
                out.kv("id", "C");
            });

            assertThat(lines).hasSize(3);
            List<Integer> widths = lines.stream().map(CommandOutput::displayWidth).toList();
            assertThat(widths)
                    .as("三行应等宽（值列对齐），实际: %s", widths)
                    .allMatch(w -> w.equals(widths.get(0)));
        }

        @Test
        @DisplayName("超宽键不被截断（宁可错位也不能丢信息）")
        void doesNotTruncate() {
            List<String> lines = capture(sender -> {
                // keyWidth 自适应取最大键宽，不会小于「一个非常长的键名」
                CommandOutput out = CommandOutput.of(sender, "一个非常长的键名");
                out.kv("一个非常长的键名", "值");
            });

            assertThat(lines).hasSize(1);
            assertThat(lines.get(0)).contains("一个非常长的键名").contains("值");
        }

        @Test
        @DisplayName("section/header/rule 不参与键对齐（它们不是键值行）")
        void nonKvLinesAreDistinct() {
            List<String> lines = capture(sender -> {
                CommandOutput out = CommandOutput.of(sender, "键");
                out.header("t");
                out.section("s");
                out.rule();
            });
            assertThat(lines).hasSize(3);
            assertThat(lines.get(0)).contains("t");
            assertThat(lines.get(1)).contains("s");
            assertThat(lines.get(2)).contains("-");
        }
    }

    @Nested
    @DisplayName("数值格式化：无科学计数法、无多余尾零")
    class Numbers {

        @Test
        @DisplayName("fixed 按小数位补齐")
        void fixedFormats() {
            assertThat(CommandOutput.fixed(0.5, 3)).isEqualTo("0.500");
            assertThat(CommandOutput.fixed(1.0, 2)).isEqualTo("1.00");
        }

        @Test
        @DisplayName("fmt 去掉尾零")
        void fmtStripsZeros() {
            assertThat(CommandOutput.fmt(1.500)).isEqualTo("1.5");
            assertThat(CommandOutput.fmt(2.0)).isEqualTo("2");
        }

        @Test
        @DisplayName("percent 收比例而非百分数（0.72 → \"72%\"）")
        void percentTakesRatio() {
            assertThat(CommandOutput.percent(0.72)).isEqualTo("72%");
            assertThat(CommandOutput.percent(1.0)).isEqualTo("100%");
            assertThat(CommandOutput.percent(0.0)).isEqualTo("0%");
        }
    }
}
