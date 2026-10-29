package com.ft.radsurvival.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * bug hunt P1-7：{@code corrosion.stages.warn/danger} 的校验缺口。
 *
 * <p>修复前这两个键是「读了但一次都没校验」的漏网之鱼。三种错配都不报错、日志里也看不出：
 * <ul>
 *   <li>{@code warn ≥ danger} → warn 档永远不可达（danger 先命中），阶段播报直接跳级；</li>
 *   <li>{@code danger > 100} → danger 永不触发，最高只播报 warn；</li>
 *   <li>{@code warn < 0} → 玩家一进服就播「危险」。</li>
 * </ul>
 */
class StageThresholdValidationTest {

    @TempDir
    Path dir;

    private PluginConfig.LoadResult loadWith(String warn, String danger) throws IOException {
        String yaml = Files.readString(Path.of("src", "main", "resources", "config.yml"));
        // 仓库默认 stages 段为 warn: 50 / danger: 75
        yaml = yaml.replace("warn: 50", "warn: " + warn);
        yaml = yaml.replace("danger: 75", "danger: " + danger);
        Path file = dir.resolve("config.yml");
        Files.writeString(file, yaml);
        return PluginConfig.loadFromFile(file);
    }

    @Nested
    @DisplayName("非法组合必须被拒并给出可操作的错误信息")
    class RejectsInvalid {

        @Test
        @DisplayName("warn = danger → 拒（warn 档不可达）")
        void equalRejected() throws IOException {
            var r = loadWith("75", "75");
            assertThat(r.hasErrors()).isTrue();
            assertThat(String.join(" ", r.errors())).contains("必须 <");
        }

        @Test
        @DisplayName("warn > danger → 拒")
        void invertedRejected() throws IOException {
            var r = loadWith("90", "60");
            assertThat(r.hasErrors()).isTrue();
            assertThat(String.join(" ", r.errors())).contains("必须 <");
        }

        @Test
        @DisplayName("danger > 100 → 拒（永远触发不了）")
        void dangerAboveHundredRejected() throws IOException {
            var r = loadWith("50", "300");
            assertThat(r.hasErrors()).isTrue();
            assertThat(String.join(" ", r.errors())).contains("[0,100]");
        }

        @Test
        @DisplayName("warn < 0 → 拒（一进服就播危险）")
        void negativeWarnRejected() throws IOException {
            var r = loadWith("-5", "75");
            assertThat(r.hasErrors()).isTrue();
            assertThat(String.join(" ", r.errors())).contains("[0,100]");
        }
    }

    @Nested
    @DisplayName("合法组合不能被误伤")
    class AcceptsValid {

        @Test
        @DisplayName("仓库默认 50/75 通过")
        void defaultsAccepted() throws IOException {
            var r = loadWith("50", "75");
            assertThat(r.hasErrors()).isFalse();
            assertThat(r.config().corrosion().stageWarn()).isEqualTo(50.0);
            assertThat(r.config().corrosion().stageDanger()).isEqualTo(75.0);
        }

        @Test
        @DisplayName("边界 0 / 100 通过（闭区间）")
        void boundariesAccepted() throws IOException {
            var r = loadWith("0", "100");
            assertThat(r.hasErrors()).isFalse();
            assertThat(r.config().corrosion().stageWarn()).isZero();
            assertThat(r.config().corrosion().stageDanger()).isEqualTo(100.0);
        }
    }
}
