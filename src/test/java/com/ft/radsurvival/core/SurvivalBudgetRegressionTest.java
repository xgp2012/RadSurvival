package com.ft.radsurvival.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ft.radsurvival.config.PluginConfig;
import com.ft.radsurvival.config.PluginConfig.CorrosionCurve;
import com.ft.radsurvival.core.ArmorCalculator.ArmorSlot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withinPercentage;

/**
 * 生存预算回归测试（原 plan.md §1.6 预算表；v1.2 随护甲削弱重算）。
 *
 * <p><b>v1.2 为什么必须重算</b>：护甲减免从「下界合金 100% / 钻石 85% / 铁 70% / 皮革 25%」
 * 全面下调到「72% / 60% / 48% / 15%」。旧断言（铁 ≈53s、钻石 ≈91s、钻石≈123s、
 * 下界合金免疫）全部失效。本版按新系数<b>重新实测离散积分</b>，并把结果作为
 * <b>削弱效果的量化证据</b>钉死——如果以后有人把系数改回去，这些断言会立刻红。</p>
 *
 * <p>离散积分口径（与主循环一致）：每 1s 结算一次，同一周期内<b>先累积腐蚀、再按新腐蚀值
 * 算伤害</b>（先累后打）。原版生命上限 20 点（base-damage 1.0 = 半心/s）。</p>
 */
class SurvivalBudgetRegressionTest {

    /** 原版生命上限 20 点（20HP/半心基准）。 */
    private static final double MAX_HP = 20.0;

    private static final PluginConfig CFG = loadRepoConfig();
    private static final double BASE = CFG.baseDamage();                      // 1.0
    private static final double DAY_ACCUM = CFG.corrosion().dayAccumPerSec(); // 1.0 %/s
    private static final double REGEN = CFG.corrosion().regenPerSec();        // 1.0 %/s
    private static final double NIGHT_TF = CFG.nightMultiplier();             // 0.5
    private static final double MAX_MULT = CFG.corrosion().maxMultiplier();   // 2.0
    private static final CorrosionCurve CURVE = CFG.corrosion().curve();      // linear
    private static final double MIN_FRACTION = CFG.armor().minDamageFraction(); // 0.10

    private static PluginConfig loadRepoConfig() {
        try {
            PluginConfig.LoadResult result =
                    PluginConfig.loadFromString(Files.readString(Path.of("src", "main", "resources", "config.yml")));
            assertThat(result.errors()).isEmpty();
            return result.config();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 模拟结果：死亡秒数 + 死亡时腐蚀值。 */
    private record Sim(double deathSeconds, double corrosionAtDeath) {
    }

    /** 每秒结算、腐蚀逐秒累积，`主循环同款：先累后打 + v1.2 最低杀伤保底`。 */
    private static Sim simulate(double timeFactor, double armorReduction, boolean armorAffectsAccum) {
        double corrosion = 0.0;
        double hp = MAX_HP;
        double accumRate = RadiationMath.effectiveAccumRatePerSec(
                DAY_ACCUM, timeFactor, armorReduction, armorAffectsAccum);
        int seconds = 0;
        while (hp > 0.0) {
            seconds++;
            corrosion = RadiationMath.step(corrosion, 1.0, accumRate, REGEN, true); // 先累积
            double mult = RadiationMath.corrosionMultiplier(corrosion, MAX_MULT, CURVE);
            double baseline = BASE * timeFactor * mult;
            double dmg = ArmorCalculator.applyFloor(
                    RadiationMath.finalDamage(BASE, timeFactor, corrosion, armorReduction, MAX_MULT, CURVE),
                    baseline, MIN_FRACTION);
            hp -= dmg;
            if (seconds > 100000) {
                return new Sim(Double.POSITIVE_INFINITY, corrosion);
            }
        }
        return new Sim(seconds, corrosion);
    }

    /** 全套材质减免（生产适配层同口径：EnumMap 保证确定性浮点累加顺序）。 */
    private static double fullSetReduction(String material) {
        ArmorCalculator calc = new ArmorCalculator(CFG);
        Map<ArmorSlot, String> m = new java.util.EnumMap<>(ArmorSlot.class);
        m.put(ArmorSlot.HELMET, material);
        m.put(ArmorSlot.CHESTPLATE, material);
        m.put(ArmorSlot.LEGGINGS, material);
        m.put(ArmorSlot.BOOTS, material);
        return calc.reduction(m);
    }

    // -------------------------------------------------------------- 白天（累计口径）

    @Test
    @DisplayName("白天·裸装 ≈22s（无甲时最低杀伤保底正好等于原伤害，故与旧口径一致）")
    void dayNaked() {
        Sim sim = simulate(1.0, 0.0, false);
        assertThat(sim.deathSeconds()).isCloseTo(22.0, withinPercentage(20.0));
        // 裸装减免为 0 → 保底不改变任何结果（基线 × 0.10 远低于实际伤害）
        assertThat(sim.deathSeconds()).isEqualTo(simulate(1.0, 0.0, true).deathSeconds());
    }

    @Test
    @DisplayName("白天·皮革全（减免 15%）≈22s：最弱一档，与裸装几乎无差（削弱后的直接体感）")
    void dayFullLeather() {
        assertThat(fullSetReduction("LEATHER")).isCloseTo(0.15, org.assertj.core.data.Offset.offset(1e-9));
        Sim sim = simulate(1.0, fullSetReduction("LEATHER"), false);
        assertThat(sim.deathSeconds()).isCloseTo(22.0, withinPercentage(20.0));
    }

    @Test
    @DisplayName("白天·铁全（减免 48%）≈33s：v1.1 的 53s → 现在约 33s，削弱幅度约 38%")
    void dayFullIron() {
        assertThat(fullSetReduction("IRON")).isCloseTo(0.48, org.assertj.core.data.Offset.offset(1e-9));
        Sim sim = simulate(1.0, fullSetReduction("IRON"), false);
        assertThat(sim.deathSeconds()).isCloseTo(33.0, withinPercentage(20.0));
    }

    @Test
    @DisplayName("白天·钻石全（减免 60%）≈42s：v1.1 的 91s → 现在约 42s，腰斩有余")
    void dayFullDiamond() {
        assertThat(fullSetReduction("DIAMOND")).isCloseTo(0.60, org.assertj.core.data.Offset.offset(1e-9));
        Sim sim = simulate(1.0, fullSetReduction("DIAMOND"), false);
        assertThat(sim.deathSeconds()).isCloseTo(42.0, withinPercentage(20.0));
    }

    // -------------------------------------------------------------- 夜晚（timeFactor 0.5）

    @Test
    @DisplayName("夜晚·裸装 ≈37s（累积与伤害同时减半，但倍率增长被拉长，不能按 22×2=44 推）")
    void nightNaked() {
        Sim sim = simulate(NIGHT_TF, 0.0, false);
        // 正确值 37s；旧断言写 44s（由「白天 22s × 2」误推而来），
        // ±20% 容差恰好把错值兜住，属「容差掩盖错误」的典型，这里收紧到 ±10%。
        assertThat(sim.deathSeconds()).isCloseTo(37.0, withinPercentage(10.0));
    }

    @Test
    @DisplayName("夜晚·铁全 ≈66s")
    void nightFullIron() {
        Sim sim = simulate(NIGHT_TF, fullSetReduction("IRON"), false);
        assertThat(sim.deathSeconds()).isCloseTo(66.0, withinPercentage(20.0));
    }

    @Test
    @DisplayName("夜晚·钻石全 ≈83s")
    void nightFullDiamond() {
        Sim sim = simulate(NIGHT_TF, fullSetReduction("DIAMOND"), false);
        assertThat(sim.deathSeconds()).isCloseTo(83.0, withinPercentage(20.0));
    }

    // -------------------------------------------------------- v1.2 核心：免疫已不存在

    @Test
    @DisplayName("v1.2 全套下界合金不再免疫：伤害恒 > 0，白天约 56s 必死（旧断言已反转）")
    void fullNetheriteIsNoLongerImmune() {
        double red = fullSetReduction("NETHERITE");
        assertThat(red).isCloseTo(0.72, org.assertj.core.data.Offset.offset(1e-9));
        // 任意时段 × 任意腐蚀值：伤害都必须严格大于 0
        for (double tf : new double[]{1.0, 0.5, 0.75}) {
            for (double corrosion = 0; corrosion <= 100; corrosion += 25) {
                double dmg = RadiationMath.finalDamage(BASE, tf, corrosion, red, MAX_MULT, CURVE);
                assertThat(dmg)
                        .withFailMessage("下界合金 tf=%.2f corr=%.0f 仍为 0：免疫未被移除", tf, corrosion)
                        .isGreaterThan(0.0);
            }
        }
        // 满配下界合金白天仍会在有限时间内死亡（不再有 Infinity）
        Sim netherite = simulate(1.0, red, false);
        assertThat(netherite.deathSeconds()).isFinite();
        assertThat(netherite.deathSeconds()).isCloseTo(56.0, withinPercentage(20.0));
    }

    @Test
    @DisplayName("v1.2 最低杀伤保底兜底：即便减免被配成 100%，每轮仍承受 ≥ 基线 10% 的伤害")
    void floorGuaranteesDamageEvenAtFullReduction() {
        double baseline = BASE * 1.0;
        for (double corrosion = 0; corrosion <= 100; corrosion += 20) {
            double mult = RadiationMath.corrosionMultiplier(corrosion, MAX_MULT, CURVE);
            double raw = RadiationMath.finalDamage(BASE, 1.0, corrosion, 1.0, MAX_MULT, CURVE);
            double floored = ArmorCalculator.applyFloor(raw, BASE * mult, MIN_FRACTION);
            assertThat(raw).isZero(); // 纯公式在减免 100% 时确实是 0
            assertThat(floored).isCloseTo(baseline * mult * MIN_FRACTION,
                    org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    // ------------------------------------------------------------------- 室内恢复

    @Test
    @DisplayName("室内（遮蔽）恢复：腐蚀 100%→0 恰需 100s（regen 1%/s）")
    void coveredRegenTakes100Seconds() {
        double c = 100.0;
        for (int i = 1; i <= 99; i++) {
            c = RadiationMath.stepCovered(c, 1.0, REGEN);
            assertThat(c).isCloseTo(100.0 - i, org.assertj.core.data.Offset.offset(1e-9));
        }
        c = RadiationMath.stepCovered(c, 1.0, REGEN);
        assertThat(c).isZero();
    }

    // ------------------------------------------------- 默认配置（盔甲参与累积）偏差钉死

    /**
     * config.yml 默认 {@code armor-affects-accum: true}，即重甲同时减伤与减累积。
     * v1.2 下调系数后这条偏差依然存在（方向不变：穿得越厚死得越晚），
     * 但绝对量级变了，因此重算钉死。
     */
    @Test
    @DisplayName("偏差钉死：armor-affects-accum=true 时白天铁/钻石比不参与累积更耐晒")
    void armorAffectsAccumDefaultDeviatesFromTable() {
        Sim ironOn = simulate(1.0, fullSetReduction("IRON"), true);
        Sim ironOff = simulate(1.0, fullSetReduction("IRON"), false);
        Sim diamondOn = simulate(1.0, fullSetReduction("DIAMOND"), true);
        Sim diamondOff = simulate(1.0, fullSetReduction("DIAMOND"), false);

        // 方向：参与累积 → 腐蚀涨得慢 → 死亡更晚（严格大于）
        assertThat(ironOn.deathSeconds()).isGreaterThan(ironOff.deathSeconds());
        assertThat(diamondOn.deathSeconds()).isGreaterThan(diamondOff.deathSeconds());
        // 量级：新系数下铁 ≈36s、钻石 ≈42s（v1.1 旧系数为 61s / 123s —— 削弱非常显著）
        assertThat(ironOn.deathSeconds()).isCloseTo(36.0, withinPercentage(20.0));
        assertThat(diamondOn.deathSeconds()).isCloseTo(42.0, withinPercentage(20.0));
        // 裸装场景两口径等价（无甲 → 累积速率与减免无关）
        assertThat(simulate(1.0, 0.0, true).deathSeconds())
                .isEqualTo(simulate(1.0, 0.0, false).deathSeconds());
    }
}
