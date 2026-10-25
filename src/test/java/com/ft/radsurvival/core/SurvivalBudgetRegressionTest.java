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
 * §1.6 生存预算表回归测试（plan.md §6 明确要求「预算表数值作为回归断言」，M2a）。
 *
 * <p>离散积分口径（与 M2b 主循环一致）：每 1s 结算一次；同一结算周期内<b>先累积腐蚀、
 * 再按新腐蚀值判定伤害</b>（先累后打；反向「先打后累」仅差最后一 tick ≈1s，下表 ±20% 容差内等价）。
 * 原版生命上限 20 点（10 颗心，半心 = 1 点，base-damage 1.0 = 半心/s）。</p>
 *
 * <p>预算表推导口径（表头「base 1.0、累积 1%/s、×2 封顶」，§1.6）：夜晚累积同样按
 * timeFactor 减半（「累积也减半」），但<b>盔甲不减免累积</b>（即 armor-affects-accum=false 口径，
 * 表中「死亡时腐蚀 ≈ 暴露秒数」即为证据；若盔甲参与累积，重甲场景会漂移出 ±20%，
 * 见末尾偏差钉死测试与里程碑报告）。</p>
 */
class SurvivalBudgetRegressionTest {

    /** §1.6：原版生命上限 20 点（20HP/半心基准）。 */
    private static final double MAX_HP = 20.0;

    private static final PluginConfig CFG = loadRepoConfig();
    private static final double BASE = CFG.baseDamage();                     // 1.0（§3）
    private static final double DAY_ACCUM = CFG.corrosion().dayAccumPerSec(); // 1.0 %/s
    private static final double REGEN = CFG.corrosion().regenPerSec();        // 1.0 %/s
    private static final double NIGHT_TF = CFG.nightMultiplier();             // 0.5（§1.2）
    private static final double MAX_MULT = CFG.corrosion().maxMultiplier();   // 2.0（§1.6 ×2 封顶）
    private static final CorrosionCurve CURVE = CFG.corrosion().curve();      // linear

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

    /** 模拟结果：死亡（或等效封顶）秒数 + 死亡时腐蚀值。 */
    private record Sim(double deathSeconds, double corrosionAtDeath) {
    }

    /**
     * 「每秒结算、腐蚀逐秒累积」离散积分：
     * accumRate = DAY_ACCUM × timeFactor（armorAffectsAccum 口径由参数决定），
     * damage(s) = finalDamage(base, timeFactor, corrosion(s), armorReduction, maxMult, curve)。
     * 免疫场景（伤害恒 0）不会死亡：以 100000s 上限兜底返回 Infinity 标记。
     */
    private static Sim simulate(double timeFactor, double armorReduction, boolean armorAffectsAccum) {
        double corrosion = 0.0;
        double hp = MAX_HP;
        double accumRate = RadiationMath.effectiveAccumRatePerSec(
                DAY_ACCUM, timeFactor, armorReduction, armorAffectsAccum);
        int seconds = 0;
        while (hp > 0.0) {
            seconds++;
            corrosion = RadiationMath.step(corrosion, 1.0, accumRate, REGEN, true); // 先累积
            hp -= RadiationMath.finalDamage(BASE, timeFactor, corrosion, armorReduction, MAX_MULT, CURVE); // 后结算
            if (seconds > 100000) {
                return new Sim(Double.POSITIVE_INFINITY, corrosion);
            }
        }
        return new Sim(seconds, corrosion);
    }

    /**
     * 全套材质减免（经 §1.4 模型计算，与预算表行一一对应）。
     *
     * <p>M3 恢复期修复：原实现用 {@code Map.of}（ImmutableCollections），其槽位迭代顺序
     * 随 JVM 身份哈希/SALT 每次启动随机 → 浮点累加顺序漂移，「全套下界合金恰 1.0」断言
     * 在个别顺序下得到 0.9999999999999999 / 1.0000000000000002（M2b 验证恰好抽中好顺序）。
     * 改为与生产适配层 {@code ArmorCalculator.slotMaterialsOf} 完全一致的 {@link java.util.EnumMap}
     * （= ArmorSlot 声明顺序，确定性）；越界值由内核 clampUnit 收口 → 恰 1.0。
     * 数值口径不变（125 条既有断言全部保留）。</p>
     */
    private static double fullSetReduction(String material) {
        ArmorCalculator calc = new ArmorCalculator(CFG);
        Map<ArmorSlot, String> m = new java.util.EnumMap<>(ArmorSlot.class);
        m.put(ArmorSlot.HELMET, material);
        m.put(ArmorSlot.CHESTPLATE, material);
        m.put(ArmorSlot.LEGGINGS, material);
        m.put(ArmorSlot.BOOTS, material);
        return calc.reduction(m);
    }

    // ---------------------------------------------------------- §1.6 预算表逐行断言

    @Test
    @DisplayName("白天·裸装 ~18s（腐蚀 ~18%）：离散积分实测落在 ±20%")
    void dayNaked() {
        Sim sim = simulate(1.0, 0.0, false);
        assertThat(sim.deathSeconds()).isCloseTo(18.0, withinPercentage(20.0));
        assertThat(sim.corrosionAtDeath()).isCloseTo(18.0, withinPercentage(20.0));
    }

    @Test
    @DisplayName("白天·皮革全 ~24s（腐蚀 ~24%，减免恰 0.25）")
    void dayFullLeather() {
        assertThat(fullSetReduction("LEATHER")).isCloseTo(0.25, org.assertj.core.data.Offset.offset(1e-9));
        Sim sim = simulate(1.0, fullSetReduction("LEATHER"), false);
        assertThat(sim.deathSeconds()).isCloseTo(24.0, withinPercentage(20.0));
        assertThat(sim.corrosionAtDeath()).isCloseTo(24.0, withinPercentage(20.0));
    }

    @Test
    @DisplayName("白天·铁全 ~53s（腐蚀 ~53%，减免恰 0.70）")
    void dayFullIron() {
        assertThat(fullSetReduction("IRON")).isCloseTo(0.70, org.assertj.core.data.Offset.offset(1e-9));
        Sim sim = simulate(1.0, fullSetReduction("IRON"), false);
        assertThat(sim.deathSeconds()).isCloseTo(53.0, withinPercentage(20.0));
        assertThat(sim.corrosionAtDeath()).isCloseTo(53.0, withinPercentage(20.0));
    }

    @Test
    @DisplayName("白天·钻石全 ~91s（腐蚀 ~91%，减免恰 0.85）")
    void dayFullDiamond() {
        assertThat(fullSetReduction("DIAMOND")).isCloseTo(0.85, org.assertj.core.data.Offset.offset(1e-9));
        Sim sim = simulate(1.0, fullSetReduction("DIAMOND"), false);
        assertThat(sim.deathSeconds()).isCloseTo(91.0, withinPercentage(20.0));
        assertThat(sim.corrosionAtDeath()).isCloseTo(91.0, withinPercentage(20.0));
    }

    @Test
    @DisplayName("夜晚·裸装 ~37s（timeFactor=0.5，累积也减半 → 死时腐蚀 ~18%）")
    void nightNaked() {
        Sim sim = simulate(NIGHT_TF, 0.0, false);
        assertThat(sim.deathSeconds()).isCloseTo(37.0, withinPercentage(20.0));
        assertThat(sim.corrosionAtDeath()).isCloseTo(18.0, withinPercentage(20.0));
    }

    @Test
    @DisplayName("夜晚·铁全 ~105s（腐蚀 ~53%）")
    void nightFullIron() {
        Sim sim = simulate(NIGHT_TF, fullSetReduction("IRON"), false);
        assertThat(sim.deathSeconds()).isCloseTo(105.0, withinPercentage(20.0));
        assertThat(sim.corrosionAtDeath()).isCloseTo(53.0, withinPercentage(20.0));
    }

    @Test
    @DisplayName("夜晚·钻石全 ~180s（腐蚀 ~90%，未触 ×2 封顶）")
    void nightFullDiamond() {
        Sim sim = simulate(NIGHT_TF, fullSetReduction("DIAMOND"), false);
        assertThat(sim.deathSeconds()).isCloseTo(180.0, withinPercentage(20.0));
        assertThat(sim.corrosionAtDeath()).isCloseTo(90.0, withinPercentage(20.0));
    }

    @Test
    @DisplayName("全套下界合金 = 免疫：任意时段/腐蚀伤害恒 0；模拟永不死亡（预算表“免疫”行）")
    void fullNetheriteIsImmune() {
        double red = fullSetReduction("NETHERITE");
        assertThat(red).isEqualTo(1.0);
        for (double tf : new double[]{1.0, 0.5, 0.75}) {
            for (double corrosion = 0; corrosion <= 100; corrosion += 25) {
                assertThat(RadiationMath.finalDamage(BASE, tf, corrosion, red, MAX_MULT, CURVE)).isZero();
            }
        }
        assertThat(simulate(1.0, red, false).deathSeconds()).isInfinite();
    }

    @Test
    @DisplayName("室内（遮蔽）恢复：腐蚀 100%→0 恰需 100s（regen 1%/s，预算表“室内”行）")
    void coveredRegenTakes100Seconds() {
        double c = 100.0;
        for (int i = 1; i <= 99; i++) {
            c = RadiationMath.stepCovered(c, 1.0, REGEN);
            assertThat(c).isCloseTo(100.0 - i, org.assertj.core.data.Offset.offset(1e-9));
        }
        c = RadiationMath.stepCovered(c, 1.0, REGEN);
        assertThat(c).isZero(); // 第 100 秒触底（且钳制不为负）
    }

    // ------------------------------------------------------ 默认配置（盔甲参与累积）偏差钉死

    /**
     * 口径偏差说明（写入里程碑报告）：config.yml 默认 armor-affects-accum=true，
     * 而 §1.6 预算表按表头「累积 1%/s」推导（= 盔甲不减免累积）。两者对重甲长曝场景
     * 存在系统性偏差：盔甲越厚、累积越慢、死得越晚——白天钻石全按默认配置 ≈123s（表值 91s，+35%），
     * 远超 ±20% 容差。本测试把「偏差方向与量级」钉死为回归断言：M2b/M4 若以默认配置实测
     * 对照预算表，重甲场景应预期偏慢；要表实相符需 armor-affects-accum: false 或重排表值。
     */
    @Test
    @DisplayName("偏差钉死：armor-affects-accum=true 时白天铁/钻石死亡时间显著晚于预算表（>+20%）")
    void armorAffectsAccumDefaultDeviatesFromTable() {
        Sim iron = simulate(1.0, fullSetReduction("IRON"), true);
        Sim diamond = simulate(1.0, fullSetReduction("DIAMOND"), true);
        // 方向：盔甲减免参与累积 → 更耐晒；量级：铁 ≈61s（+15%，尚在 ±20% 边缘）、钻石 ≈123s（+35%，超容差）
        assertThat(iron.deathSeconds()).isCloseTo(61.0, withinPercentage(20.0));
        assertThat(diamond.deathSeconds()).isCloseTo(123.0, withinPercentage(20.0));
        // 明确晚于预算表值；钻石场景偏离幅度超出 ±20% 容差下限
        assertThat(iron.deathSeconds()).isGreaterThan(53.0);
        assertThat(diamond.deathSeconds()).isGreaterThan(91.0 * 1.2);
        // 而裸装场景两口径等价（无甲 → (1-red)=1）
        Sim nakedOn = simulate(1.0, 0.0, true);
        Sim nakedOff = simulate(1.0, 0.0, false);
        assertThat(nakedOn.deathSeconds()).isEqualTo(nakedOff.deathSeconds());
    }
}
