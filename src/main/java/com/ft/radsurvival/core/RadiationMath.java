package com.ft.radsurvival.core;

import com.ft.radsurvival.config.PluginConfig.CorrosionCurve;

/**
 * 辐射/腐蚀伤害公式的纯逻辑内核（plan.md §1.3 / §1.6）。
 *
 * <p>本类不引用任何需要运行中服务器的 Bukkit 类型，仅使用
 * {@link CorrosionCurve}（PluginConfig 的常量枚举），可被单元测试直接调用。
 * M2b 的 DamageApplier / 主循环将复用这里的静态方法作为唯一口径。</p>
 */
public final class RadiationMath {

    /** 腐蚀值下限（plan.md §1.6：数值范围 0~100%，内部浮点）。 */
    public static final double CORROSION_MIN = 0.0;
    /** 腐蚀值上限。 */
    public static final double CORROSION_MAX = 100.0;

    private RadiationMath() {
    }

    // ---------------------------------------------------------------- 钳制

    /** 把腐蚀值钳到 [0,100]（§1.6）。 */
    public static double clampCorrosion(double value) {
        return Math.min(CORROSION_MAX, Math.max(CORROSION_MIN, value));
    }

    /** 把比例值（盔甲减免等）钳到 [0,1]。注意：v1.2 起减免恒 < 1.0（配置校验拒绝任何 ≥1.0 的材质），\n     * 这里的 1.0 分支仅是内核防御网，不是可配置玩法。 */
    public static double clampUnit(double value) {
        return Math.min(1.0, Math.max(0.0, value));
    }

    // ------------------------------------------------------------ §1.6 腐蚀步进

    /**
     * 腐蚀值离散步进内核（§1.6，M2a 口径钉死）。
     *
     * <p>签名 {@code step(current, elapsedSeconds, accumRate, regenRate)} 的可测形态：
     * {@code exposed=true} 时按 <b>累积速率</b>（已由
     * {@link #effectiveAccumRatePerSec} 折算好 timeFactor / 盔甲耦合）前进，
     * {@code exposed=false}（遮蔽或豁免维度）时按 <b>恢复速率</b>（固定 1%/s，
     * 不乘 timeFactor、不受盔甲影响，§1.6「遮蔽于室内后缓慢回落」）后退；
     * 结果钳 [0,100]。elapsedSeconds 允许小数（tick 级结算 = 20tick=1.0s）。</p>
     */
    public static double step(double current, double elapsedSeconds,
                              double accumRatePerSec, double regenRatePerSec, boolean exposed) {
        double delta = exposed
                ? accumRatePerSec * elapsedSeconds
                : -regenRatePerSec * elapsedSeconds;
        return clampCorrosion(current + delta);
    }

    /** 露天累积步进（便捷形态）：current + accumRatePerSec × elapsedSeconds，钳 [0,100]。 */
    public static double stepExposed(double current, double elapsedSeconds, double accumRatePerSec) {
        return clampCorrosion(current + accumRatePerSec * elapsedSeconds);
    }

    /** 遮蔽/豁免恢复步进（便捷形态）：current − regenRatePerSec × elapsedSeconds，钳 [0,100]。 */
    public static double stepCovered(double current, double elapsedSeconds, double regenRatePerSec) {
        return clampCorrosion(current - regenRatePerSec * elapsedSeconds);
    }

    /**
     * 露天时腐蚀累积速率（%/s）——timeFactor 与盔甲的耦合口径（§1.6，M2a 决定，注释即口径）：
     *
     * <ul>
     *   <li>{@code rate = dayAccumPerSec × timeFactor}：与 §1.3 的 timeFactor 同一口径缩放——
     *       夜晚自动 ×night-multiplier（0.5）、黄昏/黎明随线性插值同步过渡，
     *       实现「白天 +1%/s、夜晚 +0.5%/s、晨昏与伤害系数同步插值」（§1.6 累积条目）；</li>
     *   <li>{@code armorAffectsAccum=true} 时再 ×(1 − armorReduction)：盔甲同样作用于累积速率，
     *       全套下界合金既不累积也不掉血（§1.6）；false 则盔甲只减免伤害、不减免累积。</li>
     * </ul>
     */
    public static double effectiveAccumRatePerSec(double dayAccumPerSec, double timeFactor,
                                                  double armorReduction, boolean armorAffectsAccum) {
        double rate = dayAccumPerSec * timeFactor;
        if (armorAffectsAccum) {
            rate *= 1.0 - clampUnit(armorReduction);
        }
        return rate;
    }

    // -------------------------------------------------------- §1.3/§1.6 伤害倍率

    /**
     * 腐蚀伤害倍率（§1.3 基线 + §1.6 curve）。r = clamp(c,0,100)/100，余量 = maxMultiplier − 1。
     *
     * <p>三式写死（M2a 决定，单测钉死 0/50/100 三点）：</p>
     * <ul>
     *   <li>{@code LINEAR}：1 + r × (max−1)                     ——§1.3 原式，默认；</li>
     *   <li>{@code SQRT}： 1 + √r × (max−1)                      ——先高后猛（前期惩罚重）；</li>
     *   <li>{@code EXP}：  1 + r² × (max−1)                      ——先缓后爆（M2a 钉死为平方二次曲线；
     *       注：并非自然指数 e^r，口径以本注释为准，§1.6「非线性预留」）。</li>
     * </ul>
     * 三条曲线在 c=0 恒为 ×1.0、c=100 恒为 ×maxMultiplier 封顶（max ≥ 1 由 M1 校验保证）。
     */
    public static double corrosionMultiplier(double corrosion, double maxMultiplier, CorrosionCurve curve) {
        double r = clampCorrosion(corrosion) / CORROSION_MAX;
        double headroom = maxMultiplier - 1.0;
        return switch (curve) {
            case LINEAR -> 1.0 + r * headroom;
            case SQRT -> 1.0 + Math.sqrt(r) * headroom;
            case EXP -> 1.0 + r * r * headroom;
        };
    }

    // ---------------------------------------------------------------- §1.3 公式

    /**
     * §1.3 最终伤害 = base × timeFactor × corrosionMult × (1 − armorReduction)。
     * 纯函数，无钳制、无副作用——M2b 的 DamageApplier 直接复用本签名。
     */
    public static double finalDamage(double base, double timeFactor,
                                     double corrosionMult, double armorReduction) {
        return base * timeFactor * corrosionMult * (1.0 - clampUnit(armorReduction));
    }

    /**
     * §1.3 全参数便捷重载：内部先算 {@link #corrosionMultiplier} 再套用公式。
     * armorReduction 在进公式前钳 [0,1]。v1.2 起该值不可能达到 1.0（配置层拒绝），\n     * 因此本函数不再产生「零伤害」——真正的零伤害只来自抗尘埃药免疫窗口（见 RadiationTask）。
     */
    public static double finalDamage(double base, double timeFactor, double corrosion,
                                     double armorReduction, double maxMultiplier, CorrosionCurve curve) {
        double mult = corrosionMultiplier(clampCorrosion(corrosion), maxMultiplier, curve);
        return finalDamage(base, timeFactor, mult, armorReduction);
    }

    // ---------------------------------------------------------------- §1.6 阶段

    /**
     * 腐蚀阶段判定（§1.6 stages{warn,danger}）：0=normal、1=warn、2=danger。
     * 口径：<b>到达即算</b>（c ≥ 阈值 → 进入该阶段，含等于阈值）；danger 优先于 warn。
     */
    public static int stageOf(double corrosion, double warn, double danger) {
        if (corrosion >= danger) {
            return CorrosionMeter.STAGE_DANGER;
        }
        if (corrosion >= warn) {
            return CorrosionMeter.STAGE_WARN;
        }
        return CorrosionMeter.STAGE_NORMAL;
    }

    /**
     * 死亡时的腐蚀值处理（§1.6）：reset-on-death=true → 0；否则按 decay-on-death 比例保留（c × k）。
     * 两配置互斥已由 M1 PluginConfig 校验兜底；resetOnDeath=true 时忽略 decay。
     * decayOnDeath 为 null（未配置）视同 reset 清零。
     */
    public static double applyDeath(double current, boolean resetOnDeath, Double decayOnDeath) {
        if (resetOnDeath || decayOnDeath == null) {
            return CORROSION_MIN;
        }
        return clampCorrosion(current * decayOnDeath);
    }
}
