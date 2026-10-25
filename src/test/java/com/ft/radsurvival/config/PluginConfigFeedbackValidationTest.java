package com.ft.radsurvival.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ft.radsurvival.config.PluginConfig.FeedbackConfig;
import com.ft.radsurvival.config.PluginConfig.LoadResult;
import com.ft.radsurvival.config.PluginConfig.ProtocolLibMode;

/**
 * M3 feedback 配置新键的加载/校验测试（plan.md §3「配置校验」延伸、§1.5；
 * 禁止无校验键——非法值必须聚合报错并拒加载）。
 */
class PluginConfigFeedbackValidationTest {

    /** 仓库默认 config.yml（M1 校验口径的权威源；新键已随 M3 同步入默认文件）。 */
    private static String repoConfigYaml() {
        try {
            return Files.readString(Path.of("src", "main", "resources", "config.yml"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String with(String... pairs) {
        String yaml = repoConfigYaml();
        for (int i = 0; i < pairs.length; i += 2) {
            String next = yaml.replace(pairs[i], pairs[i + 1]);
            if (next.equals(yaml) && !yaml.contains(pairs[i])) {
                throw new IllegalStateException("变体锚点未命中: " + pairs[i]);
            }
            yaml = next;
        }
        return yaml;
    }

    @Nested
    @DisplayName("合法值加载")
    class Valid {

        @Test
        @DisplayName("仓库默认：actionbar/particles/sound/death-message=true，bossbar=false，protocol-lib=AUTO")
        void repoDefaults() {
            LoadResult r = PluginConfig.loadFromString(repoConfigYaml());
            assertThat(r.isSuccess()).as("errors=%s", r.errors()).isTrue();
            FeedbackConfig fb = r.config().feedback();
            assertThat(fb.actionbar()).isTrue();
            assertThat(fb.particles()).isTrue();
            assertThat(fb.sound()).isTrue();
            assertThat(fb.deathMessage()).isTrue();
            assertThat(fb.bossbarCorrosion()).isFalse();
            assertThat(fb.protocolLib()).isEqualTo(ProtocolLibMode.AUTO);
        }

        @Test
        @DisplayName("缺失 feedback 节点 → 新键走默认（bossbar=false / death=true / AUTO）")
        void defaultsWhenSectionMissing() {
            // 精简 YAML：不含 feedback 段
            LoadResult r2 = PluginConfig.loadFromString("enabled-worlds: [world]\ndimensions: NORMAL\n");
            assertThat(r2.isSuccess()).as("errors=%s", r2.errors()).isTrue();
            FeedbackConfig fb = r2.config().feedback();
            assertThat(fb.actionbar()).isTrue();
            assertThat(fb.particles()).isTrue();
            assertThat(fb.sound()).isTrue();
            assertThat(fb.bossbarCorrosion()).isFalse();
            assertThat(fb.deathMessage()).isTrue();
            assertThat(fb.protocolLib()).isEqualTo(ProtocolLibMode.AUTO);
        }

        @Test
        @DisplayName("protocol-lib：auto/true/false（字符串与布尔原生标量都可）→ AUTO/ON/OFF")
        void protocolLibModes() {
            assertThat(loadFeedback(with("protocol-lib: auto", "protocol-lib: \"true\"")).protocolLib())
                    .isEqualTo(ProtocolLibMode.ON);
            assertThat(loadFeedback(with("protocol-lib: auto", "protocol-lib: True")).protocolLib())
                    .isEqualTo(ProtocolLibMode.ON);           // 大小写不敏感
            assertThat(loadFeedback(with("protocol-lib: auto", "protocol-lib: \"false\"")).protocolLib())
                    .isEqualTo(ProtocolLibMode.OFF);
            assertThat(loadFeedback(with("protocol-lib: auto", "protocol-lib: Auto")).protocolLib())
                    .isEqualTo(ProtocolLibMode.AUTO);
            assertThat(loadFeedback(with("protocol-lib: auto", "protocol-lib: false")).protocolLib())
                    .isEqualTo(ProtocolLibMode.OFF);          // YAML 原生布尔
        }

        @Test
        @DisplayName("bossbar-corrosion=true / death-message=false 合法组合")
        void booleanVariants() {
            FeedbackConfig fb = loadFeedback(with(
                    "bossbar-corrosion: false", "bossbar-corrosion: true",
                    "death-message: true", "death-message: false"));
            assertThat(fb.bossbarCorrosion()).isTrue();
            assertThat(fb.deathMessage()).isFalse();
        }

        private FeedbackConfig loadFeedback(String yaml) {
            LoadResult r = PluginConfig.loadFromString(yaml);
            assertThat(r.isSuccess()).as("errors=%s", r.errors()).isTrue();
            return r.config().feedback();
        }
    }

    @Nested
    @DisplayName("非法值拒绝（聚合报错，禁止无校验键）")
    class Invalid {

        @Test
        @DisplayName("protocol-lib=maybe → 报 feedback.protocol-lib 且列出 auto/true/false")
        void badProtocolLibMode() {
            LoadResult r = PluginConfig.loadFromString(with("protocol-lib: auto", "protocol-lib: maybe"));
            assertThat(r.isSuccess()).isFalse();
            assertThat(r.errors()).anyMatch(e -> e.contains("feedback.protocol-lib")
                    && e.contains("auto") && e.contains("true") && e.contains("false"));
        }

        @Test
        @DisplayName("bossbar-corrosion=maybe → 报 feedback.bossbar-corrosion 期望布尔")
        void badBossbarFlag() {
            // 注意不可用裸 yes/on：SnakeYAML（YAML 1.1）把 yes/no/on/off 解析成 Boolean，
            // 会被布尔校验正当接受；必须用非布尔标量才能验证字符串拒绝路径。
            LoadResult r = PluginConfig.loadFromString(with("bossbar-corrosion: false", "bossbar-corrosion: maybe"));
            assertThat(r.isSuccess()).isFalse();
            assertThat(r.errors()).anyMatch(e -> e.contains("feedback.bossbar-corrosion"));
        }

        @Test
        @DisplayName("death-message=3 → 报 feedback.death-message 期望布尔")
        void badDeathMessageFlag() {
            LoadResult r = PluginConfig.loadFromString(with("death-message: true", "death-message: 3"));
            assertThat(r.isSuccess()).isFalse();
            assertThat(r.errors()).anyMatch(e -> e.contains("feedback.death-message"));
        }

        @Test
        @DisplayName("多非法值同轮聚合报错（一次校验聚合全部错误的 M1 口径不变）")
        void aggregatesMultipleErrors() {
            LoadResult r = PluginConfig.loadFromString(with(
                    "protocol-lib: auto", "protocol-lib: sometimes",
                    "bossbar-corrosion: false", "bossbar-corrosion: \"on\"",
                    "actionbar: true", "actionbar: 0"));
            assertThat(r.isSuccess()).isFalse();
            assertThat(r.errors()).hasSizeGreaterThanOrEqualTo(3);
        }
    }
}
