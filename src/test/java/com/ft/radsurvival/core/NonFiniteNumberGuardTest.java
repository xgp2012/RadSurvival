package com.ft.radsurvival.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ft.radsurvival.api.RadiationExposureEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * bug hunt P1-5 / P1-6：NaN 与 Infinity 必须在「最靠近写入点」的地方被封死。
 *
 * <p>修复前：{@code Math.min/max} 对 NaN 原样透传（NaN 与任何值比较均为 false），
 * {@code setFinalDamage} 则只做纯赋值。两者的下游守卫都不是 NaN-safe 的
 * （{@code amount <= 0.0} 对 NaN 恒为 false），于是 NaN 能一路走到 PDC 与伤害 API。
 * 本类钉死修复后的边界口径。</p>
 */
class NonFiniteNumberGuardTest {

    // ------------------------------------------------------------ P1-5 钳制

    @Test
    @DisplayName("clampCorrosion：NaN/±Infinity 一律落到下界 0（不猜意图，宁少不多）")
    void clampCorrosionRejectsNonFinite() {
        assertThat(RadiationMath.clampCorrosion(Double.NaN)).isZero();
        assertThat(RadiationMath.clampCorrosion(Double.NEGATIVE_INFINITY)).isZero();
        // 口径说明：+Infinity **不**映射到 100，而是同样落到下界 0。
        // 腐蚀越高伤害越大，把脏值解释成「满腐蚀」等于凭空造伤害；
        // 解释成 0 只是「这一轮按没腐蚀算」，属可接受的保守错向。
        assertThat(RadiationMath.clampCorrosion(Double.POSITIVE_INFINITY)).isZero();
        // 正常区间不受影响
        assertThat(RadiationMath.clampCorrosion(-5.0)).isZero();
        assertThat(RadiationMath.clampCorrosion(105.0)).isEqualTo(100.0);
        assertThat(RadiationMath.clampCorrosion(42.5)).isEqualTo(42.5);
    }

    @Test
    @DisplayName("clampUnit：NaN/±Infinity → 0；正常值钳到 [0,1]")
    void clampUnitRejectsNonFinite() {
        assertThat(RadiationMath.clampUnit(Double.NaN)).isZero();
        assertThat(RadiationMath.clampUnit(Double.POSITIVE_INFINITY)).isZero();
        assertThat(RadiationMath.clampUnit(Double.NEGATIVE_INFINITY)).isZero();
        assertThat(RadiationMath.clampUnit(-0.5)).isZero();
        assertThat(RadiationMath.clampUnit(0.72)).isEqualTo(0.72);
        assertThat(RadiationMath.clampUnit(1.5)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("口径传播：NaN 腐蚀经 clamp 后倍率为 ×1.0（不产生 NaN 伤害）")
    void nanCorrosionYieldsFiniteMultiplier() {
        double mult = RadiationMath.corrosionMultiplier(Double.NaN, 2.0,
                com.ft.radsurvival.config.PluginConfig.CorrosionCurve.LINEAR);
        assertThat(mult).isFinite().isEqualTo(1.0);

        double dmg = RadiationMath.finalDamage(1.0, 1.0, mult, Double.NaN);
        assertThat(dmg).isFinite().isEqualTo(1.0);
    }

    // -------------------------------------------------------- P1-6 事件写入

    @Test
    @DisplayName("setFinalDamage：NaN / Infinity 按 0 处理，负值钳到 0，正值原样保留")
    void setFinalDamageClampsNonFinite() {
        RadiationExposureEvent event = eventWith(1.5);

        event.setFinalDamage(Double.NaN);
        assertThat(event.getFinalDamage())
                .as("NaN 必须变成 0，否则下游 amount <= 0.0 守卫失效")
                .isZero();

        event.setFinalDamage(Double.POSITIVE_INFINITY);
        assertThat(event.getFinalDamage()).as("无穷大会秒杀玩家").isZero();

        event.setFinalDamage(-3.0);
        assertThat(event.getFinalDamage()).as("负值不得产生回血").isZero();

        event.setFinalDamage(2.25);
        assertThat(event.getFinalDamage()).as("正常覆写不受影响").isEqualTo(2.25);

        event.setFinalDamage(0.0);
        assertThat(event.getFinalDamage()).isZero();
    }

    /** 构造一个最小可用事件（不依赖运行中服务器）。 */
    private static RadiationExposureEvent eventWith(double finalDamage) {
        return new RadiationExposureEvent(null, true, 1.0, 0.0, 10.0, finalDamage,
                DamageApplier.DamageChannel.SUN);
    }
}
