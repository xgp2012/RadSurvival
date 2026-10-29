package com.ft.radsurvival.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-7【P1】配置层数值入口接受 NaN / Infinity，导致「静默通过校验」或「报出误导性的 YAML 语法错误」。
 *
 * <p>根因是 {@code PluginConfig.toDouble(Object, ...)} 直接接受 {@code Number}：
 * SnakeYAML 把 {@code .nan} / {@code .inf} / {@code -.inf} 解析成 {@code Double.NaN} /
 * {@code POSITIVE_INFINITY} / {@code NEGATIVE_INFINITY}，这些值随后：
 * <ul>
 *   <li>{@code .nan} → 所有 {@code <} / {@code >} 比较均 false → <b>静默通过校验</b>，
 *       污染 timeFactor / 伤害 / 腐蚀，最终写进 player.dat；</li>
 *   <li>{@code .inf} → 触发某条越界分支 → 该分支用 {@code fmt(value)} 拼错误信息 →
 *       {@code BigDecimal.valueOf(Infinity)} 抛 {@link NumberFormatException} →
 *       被 loadFromFile 的兜底 catch 吞成「YAML 解析失败（语法错误）」，
 *       把数值问题误报为语法问题。</li>
 * </ul>
 */
class ConfigNonFiniteNumberTest {

    private static PluginConfig.LoadResult load(Path dir, String yaml) throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, yaml);
        return PluginConfig.loadFromFile(file);
    }

    /** 取仓库真实 config.yml 并替换某一行为给定内容。 */
    private static String repoYamlWith(String anchor, String replacement) throws IOException {
        String base = Files.readString(Path.of("src", "main", "resources", "config.yml"));
        assertThat(base).as("锚点必须存在于仓库 config.yml: " + anchor).contains(anchor);
        return base.replace(anchor, replacement);
    }

    @Test
    @DisplayName("修复后：night-multiplier: .nan 被拒，错误信息点名「有限数值」")
    void nanNightMultiplierRejected(@TempDir Path dir) throws IOException {
        var result = load(dir, repoYamlWith("night-multiplier: 0.5", "night-multiplier: .nan"));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.config()).isNull();
        assertThat(String.join(" ", result.errors()))
                .as("必须明确指出「有限数值」，而不是伪装成语法错误")
                .contains("有限数值")
                .contains("night-multiplier");
    }

    @Test
    @DisplayName("修复后：base-damage: .nan 同样被拒（NaN 不再静默污染伤害公式）")
    void nanBaseDamageRejected(@TempDir Path dir) throws IOException {
        var result = load(dir, repoYamlWith("base-damage: 1.0", "base-damage: .nan"));

        assertThat(result.hasErrors()).isTrue();
        assertThat(String.join(" ", result.errors())).contains("有限数值");
    }

    @Test
    @DisplayName("修复后：.inf 报「有限数值」而不是「YAML 解析失败」（不再误导排查方向）")
    void infiniteValueReportsFiniteErrorNotYaml(@TempDir Path dir) throws IOException {
        var result = load(dir, repoYamlWith("night-multiplier: 0.5", "night-multiplier: .inf"));

        assertThat(result.hasErrors()).isTrue();
        String all = String.join(" ", result.errors());
        assertThat(all).contains("有限数值");
        assertThat(all)
                .as("必须是数值错误，不能退化成 YAML 语法错误")
                .doesNotContain("YAML 解析失败");
    }

    @Test
    @DisplayName("修复后：-.inf 也被拒（负无穷同样不是合法倍率）")
    void negativeInfinityRejected(@TempDir Path dir) throws IOException {
        var result = load(dir, repoYamlWith("night-multiplier: 0.5", "night-multiplier: -.inf"));

        assertThat(result.hasErrors()).isTrue();
        assertThat(String.join(" ", result.errors())).contains("有限数值");
    }

    @Test
    @DisplayName("回归：正常数值仍然通过（修复不能误伤合法配置）")
    void finiteValuesStillAccepted(@TempDir Path dir) throws IOException {
        var result = load(dir, Files.readString(Path.of("src", "main", "resources", "config.yml")));

        assertThat(result.hasErrors()).as("仓库默认配置必须仍然合法").isFalse();
        assertThat(result.config().nightMultiplier()).isEqualTo(0.5);
        assertThat(result.config().baseDamage()).isEqualTo(1.0);
    }
}
