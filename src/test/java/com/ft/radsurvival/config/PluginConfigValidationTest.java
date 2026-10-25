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
                .containsOnly(entry("helmet", 0.12), entry("chestplate", 0.46),
                        entry("leggings", 0.30), entry("boots", 0.12));
        assertThat(cfg.armor().materialFactors())
                .containsOnly(entry("LEATHER", 0.15), entry("GOLD", 0.30), entry("CHAINMAIL", 0.30),
                        entry("IRON", 0.48), entry("DIAMOND", 0.60), entry("NETHERITE", 0.72));
        assertThat(cfg.armor().minDamageFraction()).isEqualTo(0.10); // v1.2 最低杀伤保底
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
        assertRejected(BASE.replace("IRON: 0.48", "IRON: 1.5"),
                "armor.material-factors", "[0,1]", "IRON");
    }

    @Test
    @DisplayName("部位权重 1.5 拒绝：∈ [0,1]")
    void slotWeightAboveOneRejected() {
        assertRejected(BASE.replace("chestplate: 0.46", "chestplate: 1.5"),
                "armor.slot-weights", "[0,1]", "chestplate");
    }

    @Test
    @DisplayName("未知材质键 WOOD 拒绝：仅允许 LEATHER/GOLD/CHAINMAIL/IRON/DIAMOND/NETHERITE")
    void unknownMaterialKeyRejected() {
        assertRejected(BASE.replace("NETHERITE: 0.72", "NETHERITE: 0.72, WOOD: 0.10"),
                "armor.material-factors", "WOOD");
    }

    // ------------------------------------------------- v1.2 禁止免疫 + 最低杀伤保底

    @Test
    @DisplayName("v1.2 硬约束：材质系数 1.00 复活下界合金免疫 → 拒绝（权重和 × 系数 ≥ 1.0）")
    void fullImmunityMaterialFactorRejected() {
        assertRejected(BASE.replace("NETHERITE: 0.72", "NETHERITE: 1.00"),
                "armor.material-factors.NETHERITE", "完全免疫", "NETHERITE");
    }

    @Test
    @DisplayName("v1.2 硬约束：即便权重和不足 1，只要乘积恰达 1.0 也拒绝（0.5+0.5 权重 + 系数 1.0）")
    void productReachingOneRejectedEvenWithPartialWeights() {
        String yaml = BASE
                .replace("helmet: 0.12", "helmet: 0.50")
                .replace("chestplate: 0.46", "chestplate: 0.50")
                .replace("leggings: 0.30", "leggings: 0.00")
                .replace("boots: 0.12", "boots: 0.00")
                .replace("NETHERITE: 0.72", "NETHERITE: 1.00");
        assertRejected(yaml, "armor.material-factors.NETHERITE", "完全免疫");
    }

    @Test
    @DisplayName("v1.2 硬约束：接近但不达免疫（0.99）合法——约束是 <1.0 而非 ≤0.95")
    void justBelowImmunityStillAccepted() {
        LoadResult result = load(BASE.replace("NETHERITE: 0.72", "NETHERITE: 0.99"));
        assertThat(result.errors())
                .withFailMessage(() -> String.join("\n", result.errors()))
                .isEmpty();
        assertThat(result.config().armor().maxFullSetReduction()).isCloseTo(0.99,
                org.assertj.core.data.Offset.offset(1e-9));
        // 但运行期仍由最低杀伤保底兜住：减免 99% 时伤害不会被压到 0
        assertThat(com.ft.radsurvival.core.ArmorCalculator.applyFloor(0.01, 1.0, 0.10))
                .isCloseTo(0.10, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("v1.2：权重和 > 1.0 拒绝（否则满配百分比会超过 100%，免疫判断失去意义）")
    void slotWeightSumAboveOneRejected() {
        String yaml = BASE
                .replace("helmet: 0.12", "helmet: 0.50")
                .replace("chestplate: 0.46", "chestplate: 0.50")
                .replace("leggings: 0.30", "leggings: 0.30")
                .replace("boots: 0.12", "boots: 0.12");
        assertRejected(yaml, "armor.slot-weights", "权重和", "≤ 1.0");
    }

    @Test
    @DisplayName("v1.2：权重表写成列表/裸标量时拒绝——不能静默套用默认值")
    void malformedFactorMapRejected() {
        // 把 inline map 换成列表：旧实现 childrenMap() 为空 → 静默用默认值，配置看起来「生效了」
        String asList = BASE.replace(
                "slot-weights: {helmet: 0.12, chestplate: 0.46, leggings: 0.30, boots: 0.12}",
                "slot-weights: [helmet, chestplate]");
        assertRejected(asList, "armor.slot-weights", "结构非法");

        // 裸标量同理
        String asScalar = BASE.replace(
                "slot-weights: {helmet: 0.12, chestplate: 0.46, leggings: 0.30, boots: 0.12}",
                "slot-weights: 0.5");
        assertRejected(asScalar, "armor.slot-weights", "结构非法");
    }

    @Test
    @DisplayName("v1.2：权重表缺键时明确提示「将使用默认值」，不静默")
    void missingSlotWeightIsReported() {
        String yaml = BASE.replace(
                "slot-weights: {helmet: 0.12, chestplate: 0.46, leggings: 0.30, boots: 0.12}",
                "slot-weights: {helmet: 0.12, chestplate: 0.46, leggings: 0.30}");
        assertRejected(yaml, "armor.slot-weights", "缺少键", "boots");
    }

    @Test
    @DisplayName("min-damage-fraction 越界拒绝：必须 ∈ (0,1]")
    void minDamageFractionRange() {
        assertRejected(BASE.replace("min-damage-fraction: 0.10", "min-damage-fraction: 0"),                "armor.min-damage-fraction", "(0,1]");
        assertRejected(BASE.replace("min-damage-fraction: 0.10", "min-damage-fraction: 1.5"),
                "armor.min-damage-fraction", "(0,1]");
        // 边界 1.0 合法（= 完全无视护甲，服主自选的极端设定）
        assertThat(load(BASE.replace("min-damage-fraction: 0.10", "min-damage-fraction: 1.0"))
                .errors()).isEmpty();
    }

    @Test
    @DisplayName("部位权重全 0 拒绝：盔甲会完全失效")
    void allZeroSlotWeightsRejected() {
        String yaml = BASE
                .replace("helmet: 0.12", "helmet: 0.0")
                .replace("chestplate: 0.46", "chestplate: 0.0")
                .replace("leggings: 0.30", "leggings: 0.0")
                .replace("boots: 0.12", "boots: 0.0");
        // 全 0 会让所有材质满配都是 0：不触发「禁止免疫」，但触发「权重和必须 > 0」
        assertRejected(yaml, "armor.slot-weights", "至少保留一个部位权重");
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

    // ------------------------------------------------------ anti_dust 段（v1.2 新增）

    @Test
    @DisplayName("anti_dust 默认值：启用、冷却 20s、四档由强到弱、HUD 标记 ◆抗尘")
    void antiDustDefaults() {
        PluginConfig cfg = load(BASE).config();
        assertThat(cfg).isNotNull();
        PluginConfig.AntiDustConfig dust = cfg.antiDust();
        assertThat(dust.enabled()).isTrue();
        assertThat(dust.cooldownSeconds()).isEqualTo(20);
        assertThat(dust.hudImmuneMark()).isEqualTo("◆抗尘");
        assertThat(dust.cures()).containsExactly("POISON", "HUNGER", "NAUSEA", "WEAKNESS", "SLOWNESS");
        assertThat(dust.levels()).hasSize(4);
        assertThat(dust.levels().stream().map(PluginConfig.DustLevel::id))
                .containsExactly("refined", "strong", "improved", "crude");
        // 档位强弱单调递减（配置顺序即优先级，取首个匹配）
        var reductions = dust.levels().stream().mapToDouble(PluginConfig.DustLevel::corrosionReduction).toArray();
        var immuneSeconds = dust.levels().stream().mapToInt(PluginConfig.DustLevel::immuneSeconds).toArray();
        for (int i = 1; i < reductions.length; i++) {
            assertThat(reductions[i]).withFailMessage("档位 %d 的 reduction 未递减", i)
                    .isLessThan(reductions[i - 1]);
            assertThat(immuneSeconds[i]).withFailMessage("档位 %d 的 immune-seconds 未递减", i)
                    .isLessThan(immuneSeconds[i - 1]);
        }
    }

    @Test
    @DisplayName("anti_dust 作物归属：按档位解析，未知作物返回 null，acceptedItems 含全部 22 种（4+4+6+8）")
    void antiDustCropResolution() {
        PluginConfig.AntiDustConfig dust = load(BASE).config().antiDust();
        assertThat(dust.levelFor("GOLDEN_CARROT").id()).isEqualTo("refined");
        assertThat(dust.levelFor("NETHER_WART").id()).isEqualTo("strong");
        assertThat(dust.levelFor("PUMPKIN").id()).isEqualTo("improved");
        assertThat(dust.levelFor("WHEAT").id()).isEqualTo("crude");
        assertThat(dust.levelFor("DIAMOND")).isNull(); // 非作物
        assertThat(dust.levelFor(null)).isNull();
        assertThat(dust.levelById("strong").displayName()).isEqualTo("强效抗尘埃药");
        assertThat(dust.levelById("nope")).isNull();
        assertThat(dust.acceptedItems()).hasSize(22);
        // 反查一致性：任何被认领的物品都能解析出档位
        for (String item : dust.acceptedItems()) {
            assertThat(dust.levelFor(item)).withFailMessage(item).isNotNull();
        }
    }

    @Test
    @DisplayName("anti_dust 缺失时走内置默认表（老配置升级不炸）")
    void antiDustSectionOptional() {
        String withoutSection = BASE.substring(0, BASE.indexOf("\nanti_dust:"));
        LoadResult result = load(withoutSection);
        assertThat(result.errors())
                .withFailMessage(() -> String.join("\n", result.errors()))
                .isEmpty();
        assertThat(result.config().antiDust().enabled()).isTrue();
        assertThat(result.config().antiDust().levels()).hasSize(4);
    }

    @Test
    @DisplayName("anti_dust 非法：颜色非 #RRGGBB 拒绝")
    void antiDustBadColorRejected() {
        assertRejected(BASE.replace("color: \"#8FE3C4\"", "color: \"8FE3C4\""),
                "anti_dust.levels.refined.color", "#RRGGBB");
    }

    @Test
    @DisplayName("anti_dust 非法：reduction 越界（0 / 150）拒绝")
    void antiDustBadReductionRejected() {
        assertRejected(BASE.replace("reduction: 50.0", "reduction: 0"),
                "anti_dust.levels.refined.reduction", "(0,100]");
        assertRejected(BASE.replace("reduction: 50.0", "reduction: 150"),
                "anti_dust.levels.refined.reduction", "(0,100]");
    }

    @Test
    @DisplayName("anti_dust 非法：免疫秒数为负拒绝；0 合法（只降腐蚀不留免疫）")
    void antiDustImmuneSeconds() {
        assertRejected(BASE.replace("immune-seconds: 30", "immune-seconds: -5"),
                "anti_dust.levels.refined.immune-seconds", "≥ 0");
        assertThat(load(BASE.replace("immune-seconds: 30", "immune-seconds: 0")).errors()).isEmpty();
    }

    @Test
    @DisplayName("anti_dust 非法：未知物品名拒绝")
    void antiDustUnknownItemRejected() {
        assertRejected(BASE.replace("items: [GOLDEN_CARROT,", "items: [NOT_A_CROP,"),
                "anti_dust.levels.refined.items", "NOT_A_CROP");
    }

    @Test
    @DisplayName("anti_dust 非法：同一作物被两个档位认领 → 拒绝（首个匹配才有效，重复是配置事故）")
    void antiDustDuplicateCropRejected() {
        // crude 档已认领 WHEAT，这里再塞进 refined 档 → 后出现的那个报冲突
        assertRejected(BASE.replace("[GOLDEN_CARROT, CHORUS_FRUIT, GLOW_BERRIES, ENCHANTED_GOLDEN_APPLE]",
                        "[GOLDEN_CARROT, WHEAT, GLOW_BERRIES, ENCHANTED_GOLDEN_APPLE]"),
                "anti_dust.levels.crude.items", "WHEAT", "已被更靠前的档位认领");
    }

    @Test
    @DisplayName("anti_dust 非法：冷却为负拒绝")
    void antiDustNegativeCooldownRejected() {
        assertRejected(BASE.replace("cooldown-seconds: 20", "cooldown-seconds: -1"),
                "anti_dust.cooldown-seconds", "≥ 0");
    }

    @Test
    @DisplayName("anti_dust 非法：HUD 标记为空拒绝")
    void antiDustEmptyHudMarkRejected() {
        assertRejected(BASE.replace("hud-immume-mark: \"◆抗尘\"", "hud-immume-mark: \"\""),
                "anti_dust.hud-immume-mark");
    }

    @Test
    @DisplayName("anti_dust 非法：cures 含非法标识符拒绝（如中文/空）")
    void antiDustBadCureRejected() {
        assertRejected(BASE.replace("cures: [POISON, HUNGER", "cures: [中毒, HUNGER"),
                "anti_dust.cures", "中毒");
    }

    @Test
    @DisplayName("anti_dust: enabled=false 合法且不注册任何监听器相关校验")
    void antiDustCanBeDisabled() {
        // 注意：anti_dust.enabled 与 corrosion.enabled 同名，替换时必须锚定 anti_dust 段内的上下文
        LoadResult result = load(BASE.replace(
                "  # 总开关：false = 不注册监听器，发射器行为退回原版\n  enabled: true",
                "  # 总开关：false = 不注册监听器，发射器行为退回原版\n  enabled: false"));
        assertThat(result.errors())
                .withFailMessage(() -> String.join("\n", result.errors()))
                .isEmpty();
        assertThat(result.config().antiDust().enabled()).isFalse();
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
