package com.ft.radsurvival.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bukkit.GameMode;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ft.radsurvival.config.PluginConfig.CorrosionCurve;
import com.ft.radsurvival.config.PluginConfig.LoadResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * PluginConfig 校验规则逐条断言（plan.md §3「配置校验」，M1 验收项）。
 * 纯 JUnit5 + AssertJ，无 Bukkit 服务器依赖（World.Environment / GameMode 为常量枚举，可直接调用）。
 */
class PluginConfigValidationTest {

    /** 仓库内真实 config.yml：作为合法基线，同时是回归护栏（默认文件必须永远通过校验）。 */
    private static final Path REPO_CONFIG = Path.of("src", "main", "resources", "config.yml");

    private static final String BASE = readRepoConfig();

    private static String readRepoConfig() {
        try {
            return Files.readString(REPO_CONFIG);
        } catch (IOException e) {
            throw new UncheckedIOException("读取仓库 config.yml 失败（surefire 工作目录应为项目根）", e);
        }
    }

    private static LoadResult load(String yaml) {
        return PluginConfig.loadFromString(yaml);
    }

    private static void assertRejected(String yaml, String... expectedFragments) {
        LoadResult result = load(yaml);
        assertThat(result.isSuccess())
                .withFailMessage(() -> "预期校验失败但通过了：" + String.join(" | ", result.errors()))
                .isFalse();
        assertThat(result.config()).isNull();
        assertThat(result.errors()).isNotEmpty();
        for (String fragment : expectedFragments) {
            assertThat(result.errors())
                    .withFailMessage(() -> "错误明细应包含 \"" + fragment + "\"，实际：" + String.join(" | ", result.errors()))
                    .anySatisfy(error -> assertThat(error).contains(fragment));
        }
    }

    // ------------------------------------------------------------ 合法基线

    @Test
    @DisplayName("仓库真实 config.yml：文件加载与文本加载均校验通过（回归护栏）")
    void repoConfigFileIsValid() {
        LoadResult fromDisk = PluginConfig.loadFromFile(REPO_CONFIG);
        assertThat(fromDisk.errors())
                .withFailMessage(() -> String.join("\n", fromDisk.errors()))
                .isEmpty();
        assertThat(fromDisk.config()).isNotNull();

        LoadResult fromText = load(BASE);
        assertThat(fromText.errors()).isEmpty();
        assertThat(fromText.config()).isNotNull();
    }

    @Test
    @DisplayName("默认值与 plan.md §3 草案逐项一致")
    void defaultsMatchPlan() {
        PluginConfig cfg = load(BASE).config();
        assertThat(cfg).isNotNull();
        assertThat(cfg.enabledWorlds()).containsExactly("world");
        assertThat(cfg.dimensions()).containsExactly(World.Environment.NORMAL);
        assertThat(cfg.tickInterval()).isEqualTo(20);
        assertThat(cfg.baseDamage()).isEqualTo(1.0);
        assertThat(cfg.damageGameModes()).containsExactlyInAnyOrder(GameMode.SURVIVAL, GameMode.ADVENTURE);
        assertThat(cfg.requireOnGround()).isFalse();
        assertThat(cfg.nightMultiplier()).isEqualTo(0.5);
        assertThat(cfg.smoothTransition()).isTrue();
        assertThat(cfg.transparentBlocksCountAsCover()).isTrue();
        assertThat(cfg.asyncExposureScan()).isTrue(); // §2 异步化：默认开（冷启动仍同步，机制不变）
        assertThat(cfg.armor().slotWeights())
                .containsOnly(entry("helmet", 0.20), entry("chestplate", 0.40),
                        entry("leggings", 0.30), entry("boots", 0.10));
        assertThat(cfg.armor().materialFactors())
                .containsOnly(entry("LEATHER", 0.25), entry("GOLD", 0.45), entry("CHAINMAIL", 0.45),
                        entry("IRON", 0.70), entry("DIAMOND", 0.85), entry("NETHERITE", 1.00));
        assertThat(cfg.corrosion().enabled()).isTrue();
        assertThat(cfg.corrosion().dayAccumPerSec()).isEqualTo(1.0);
        assertThat(cfg.corrosion().regenPerSec()).isEqualTo(1.0);
        assertThat(cfg.corrosion().armorAffectsAccum()).isTrue();
        assertThat(cfg.corrosion().maxMultiplier()).isEqualTo(2.0);
        assertThat(cfg.corrosion().curve()).isEqualTo(CorrosionCurve.LINEAR);
        assertThat(cfg.corrosion().resetOnDeath()).isTrue();
        assertThat(cfg.corrosion().decayOnDeath()).isNull(); // 注释掉的 decay-on-death 未生效
        assertThat(cfg.corrosion().stageWarn()).isEqualTo(50.0);
        assertThat(cfg.corrosion().stageDanger()).isEqualTo(75.0);
        assertThat(cfg.feedback().actionbar()).isTrue();
        assertThat(cfg.feedback().particles()).isTrue();
        assertThat(cfg.feedback().sound()).isTrue();
        assertThat(cfg.commands().reloadPermission()).isEqualTo("radsurvival.reload");
    }

    // ------------------------------------------------------------ 校验规则逐条

    @Test
    @DisplayName("tick-interval=19 拒绝：无敌帧 20tick 下限")
    void tickIntervalBelowInvulnerabilityTickRejected() {
        assertRejected(BASE.replace("tick-interval: 20", "tick-interval: 19"),
                "tick-interval", "无敌帧", "20");
    }

    @Test
    @DisplayName("tick-interval=24（≥20）合法")
    void tickIntervalAboveMinimumAccepted() {
        LoadResult result = load(BASE.replace("tick-interval: 20", "tick-interval: 24"));
        assertThat(result.errors()).isEmpty();
        assertThat(result.config().tickInterval()).isEqualTo(24);
    }

    @Test
    @DisplayName("reset-on-death 与 decay-on-death 同设拒绝（互斥）")
    void resetOnDeathAndDecayAreMutuallyExclusive() {
        assertRejected(BASE.replace("reset-on-death: true",
                        "reset-on-death: true\n  decay-on-death: 0.5"),
                "corrosion.reset-on-death", "corrosion.decay-on-death", "互斥");
    }

    @Test
    @DisplayName("reset-on-death: false + decay-on-death: 0.5 合法")
    void decayValidWhenResetDisabled() {
        LoadResult result = load(BASE.replace("reset-on-death: true",
                "reset-on-death: false\n  decay-on-death: 0.5"));
        assertThat(result.errors())
                .withFailMessage(() -> String.join("\n", result.errors()))
                .isEmpty();
        assertThat(result.config().corrosion().resetOnDeath()).isFalse();
        assertThat(result.config().corrosion().decayOnDeath()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("decay-on-death 越界（1.5）拒绝：必须 ∈ (0,1)")
    void decayOutOfRangeRejected() {
        assertRejected(BASE.replace("reset-on-death: true",
                        "reset-on-death: false\n  decay-on-death: 1.5"),
                "corrosion.decay-on-death", "(0,1)");
    }

    @Test
    @DisplayName("材质系数 1.5 拒绝：∈ [0,1]")
    void materialFactorAboveOneRejected() {
        assertRejected(BASE.replace("IRON: 0.70", "IRON: 1.5"),
                "armor.material-factors", "[0,1]", "IRON");
    }

    @Test
    @DisplayName("部位权重 1.5 拒绝：∈ [0,1]")
    void slotWeightAboveOneRejected() {
        assertRejected(BASE.replace("chestplate: 0.40", "chestplate: 1.5"),
                "armor.slot-weights", "[0,1]", "chestplate");
    }

    @Test
    @DisplayName("未知材质键 WOOD 拒绝：仅允许 LEATHER/GOLD/CHAINMAIL/IRON/DIAMOND/NETHERITE")
    void unknownMaterialKeyRejected() {
        assertRejected(BASE.replace("NETHERITE: 1.00}", "NETHERITE: 1.00, WOOD: 0.10}"),
                "armor.material-factors", "WOOD");
    }

    @Test
    @DisplayName("max-multiplier=0.5 拒绝：必须 ≥ 1")
    void maxMultiplierBelowOneRejected() {
        assertRejected(BASE.replace("max-multiplier: 2.0", "max-multiplier: 0.5"),
                "corrosion.max-multiplier", "≥ 1");
    }

    @Test
    @DisplayName("curve=quadratic 拒绝：仅 linear|sqrt|exp；sqrt/exp 合法")
    void curveMustBeOneOfLinearSqrtExp() {
        assertRejected(BASE.replace("curve: linear", "curve: quadratic"),
                "corrosion.curve", "linear | sqrt | exp");
        assertThat(load(BASE.replace("curve: linear", "curve: sqrt")).errors()).isEmpty();
        assertThat(load(BASE.replace("curve: linear", "curve: exp")).config().corrosion().curve())
                .isEqualTo(CorrosionCurve.EXP);
    }

    @Test
    @DisplayName("enabled-worlds 为空拒绝：白名单非空")
    void emptyEnabledWorldsRejected() {
        assertRejected(BASE.replace("enabled-worlds: [world]", "enabled-worlds: []"),
                "enabled-worlds", "不能为空");
    }

    @Test
    @DisplayName("dimensions 非法枚举拒绝；列表写法与多值合法")
    void dimensionsMustBeValidEnvironment() {
        assertRejected(BASE.replace("dimensions: NORMAL", "dimensions: CRITARIA"),
                "dimensions", "NORMAL / NETHER / THE_END");
        LoadResult multi = load(BASE.replace("dimensions: NORMAL", "dimensions: [NORMAL, NETHER]"));
        assertThat(multi.errors()).isEmpty();
        assertThat(multi.config().dimensions())
                .containsExactlyInAnyOrder(World.Environment.NORMAL, World.Environment.NETHER);
    }

    @Test
    @DisplayName("damage-game-modes 非法值拒绝；CREATIVE/SPECTATOR 配置进列表也拒绝（§1.3 无条件免疫）")
    void damageGameModesValidated() {
        assertRejected(BASE.replace("damage-game-modes: [SURVIVAL, ADVENTURE]",
                        "damage-game-modes: [SURVIVAL, CREATIVE_EX]"),
                "damage-game-modes");
        assertRejected(BASE.replace("damage-game-modes: [SURVIVAL, ADVENTURE]",
                        "damage-game-modes: [SURVIVAL, CREATIVE]"),
                "damage-game-modes", "无条件免疫");
    }

    @Test
    @DisplayName("night-multiplier 越界拒绝：∈ [0,1]")
    void nightMultiplierRange() {
        assertRejected(BASE.replace("night-multiplier: 0.5", "night-multiplier: 1.5"),
                "night-multiplier", "[0,1]");
    }

    @Test
    @DisplayName("async-exposure-scan: false 可切回全同步旧口径；非布尔拒绝（§2 异步化开关）")
    void asyncExposureScanSwitch() {
        LoadResult sync = load(BASE.replace("async-exposure-scan: true", "async-exposure-scan: false"));
        assertThat(sync.errors()).isEmpty();
        assertThat(sync.config().asyncExposureScan()).isFalse();

        assertRejected(BASE.replace("async-exposure-scan: true", "async-exposure-scan: maybe"),
                "async-exposure-scan");
    }

    @Test
    @DisplayName("非数值类型（tick-interval: fast）拒绝")
    void nonNumericValueRejected() {
        assertRejected(BASE.replace("tick-interval: 20", "tick-interval: fast"),
                "tick-interval");
    }

    @Test
    @DisplayName("YAML 语法错误聚合成加载失败而非异常抛出")
    void malformedYamlFailsGracefully() {
        LoadResult result = load("enabled-worlds: [world\n  broken: : :");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).hasSize(1);
    }

    // ------------------------------------------------- /rad reload 行为护栏（纯 Java 层）

    @Test
    @DisplayName("reload 语义：非法内容返回错误明细且可再次加载合法旧内容（旧配置保留由插件层完成）")
    void invalidReloadReportsErrorsButValidContentStillLoads() {
        String invalid = BASE.replace("max-multiplier: 2.0", "max-multiplier: 0.5");
        LoadResult failed = load(invalid);
        assertThat(failed.config()).isNull();
        LoadResult again = load(BASE);
        assertThat(again.isSuccess()).isTrue();
    }
}
