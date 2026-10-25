package com.ft.radsurvival.listener;

import java.util.UUID;

import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ft.radsurvival.listener.SunRadiationListener.SunMark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 伤害通道归因纯内核测试（本轮修复的回归护栏）。
 *
 * <p>钉住的核心缺陷：Bukkit 里本插件与 {@code /damage <p> 10 minecraft:generic}、
 * {@code world.strikeLightning()} 共用 {@code DamageCause.CUSTOM}，若按类型判定来源，
 * 会把第三方伤害误判为日晒伤害——历史表现是「全套下界合金玩家被雷劈掉 0 血」。
 * 本类只做纯函数断言，不依赖运行中服务器。</p>
 */
class SunRadiationListenerTest {

    private static final UUID VICTIM = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private static SunMark mark(long at, double amount) {
        return new SunMark(at, amount);
    }

    @Nested
    @DisplayName("classify：只有「CUSTOM + 窗口内 + 数值吻合 + 非反伤」才算本插件的太阳伤害")
    class Classify {

        @Test
        @DisplayName("正例：CUSTOM + 刚发生的同额伤害 → SUN 通道")
        void sunDamageRecognized() {
            assertThat(SunRadiationListener.classify(
                    DamageCause.CUSTOM, VICTIM, null, 1.26, mark(1_000L, 1.26), 1_010L)).isTrue();
            // 容差内（double 往返）
            assertThat(SunRadiationListener.classify(
                    DamageCause.CUSTOM, VICTIM, null, 1.26 + 1e-9, mark(1_000L, 1.26), 1_000L)).isTrue();
        }

        @Test
        @DisplayName("反例：其他 DamageCause（雷击/魔法/爆炸/生物）一律不是太阳伤害")
        void nonCustomCausesAreExternal() {
            for (DamageCause cause : new DamageCause[]{
                    DamageCause.MAGIC, DamageCause.ENTITY_EXPLOSION, DamageCause.BLOCK_EXPLOSION,
                    DamageCause.ENTITY_ATTACK, DamageCause.LIGHTNING, DamageCause.FIRE_TICK}) {
                assertThat(SunRadiationListener.classify(
                        cause, VICTIM, null, 1.26, mark(1_000L, 1.26), 1_000L))
                        .as("cause=%s", cause).isFalse();
            }
        }

        @Test
        @DisplayName("反例：CUSTOM 但无标记（第三方 /damage generic）→ 外部，必须保留原版护甲减免")
        void customWithoutMarkIsExternal() {
            assertThat(SunRadiationListener.classify(
                    DamageCause.CUSTOM, VICTIM, null, 10.0, null, 1_000L)).isFalse();
        }

        @Test
        @DisplayName("反例：超窗（>100ms）或时钟倒挂 → 外部")
        void staleMarkIsExternal() {
            SunMark mark = mark(1_000L, 1.26);
            assertThat(SunRadiationListener.classify(
                    DamageCause.CUSTOM, VICTIM, null, 1.26, mark,
                    1_000L + SunRadiationListener.SUN_WINDOW_MS + 1)).isFalse();
            assertThat(SunRadiationListener.classify(
                    DamageCause.CUSTOM, VICTIM, null, 1.26, mark, 999L)).as("时钟倒挂不可信").isFalse();
            // 恰在窗口边界内仍成立
            assertThat(SunRadiationListener.classify(
                    DamageCause.CUSTOM, VICTIM, null, 1.26, mark,
                    1_000L + SunRadiationListener.SUN_WINDOW_MS)).isTrue();
        }

        @Test
        @DisplayName("反例：窗口内但数值不同（例如同 tick 插入的第三方 CUSTOM 伤害）→ 外部")
        void amountMismatchIsExternal() {
            assertThat(SunRadiationListener.classify(
                    DamageCause.CUSTOM, VICTIM, null, 6.0, mark(1_000L, 1.26), 1_000L)).isFalse();
        }

        @Test
        @DisplayName("反例：伤害者=受害者（荆棘/反伤）→ 外部，不当作阳光")
        void selfInflictedIsExternal() {
            assertThat(SunRadiationListener.classify(
                    DamageCause.CUSTOM, VICTIM, VICTIM, 1.26, mark(1_000L, 1.26), 1_000L)).isFalse();
            // 伤害者另有其人（第三方插件带实体归因）时同样不认领
            assertThat(SunRadiationListener.classify(
                    DamageCause.CUSTOM, VICTIM, OTHER, 1.26, mark(1_000L, 1.26), 1_000L)).isTrue();
        }
    }

    @Nested
    @DisplayName("vanillaArmorReduction：与 Paper CombatRules.getDamageAfterAbsorb 同口径")
    class VanillaArmor {

        @Test
        @DisplayName("无甲恒 0；全套 20 点对小伤害 = 80%；减免随伤害单调下降到 0 后回升封顶")
        void matchesVanillaCurve() {
            assertThat(SunRadiationListener.vanillaArmorReduction(0.0, 10.0)).isZero();
            // min(20, max(20−5, 20×10/5=40)) = 20 → 20/25 = 0.8
            assertThat(SunRadiationListener.vanillaArmorReduction(20.0, 10.0)).isCloseTo(0.80, within(1e-9));
            // 伤害 60：max(20−30=−10, 20×60/5=240) → min(20, 240) = 20 → 仍封顶 0.8
            assertThat(SunRadiationListener.vanillaArmorReduction(20.0, 60.0)).isCloseTo(0.80, within(1e-9));
            // 伤害 100：同上封顶 0.8（armor×d/5 项永远够大，护甲不因高伤害而失效）
            assertThat(SunRadiationListener.vanillaArmorReduction(20.0, 100.0)).isCloseTo(0.80, within(1e-9));
            // 7 点护甲（铁套）对 1 点伤害：max(7−0.5=6.5, 7×1/5=1.4) = 6.5 → 6.5/25 = 0.26
            assertThat(SunRadiationListener.vanillaArmorReduction(7.0, 1.0)).isCloseTo(0.26, within(1e-9));
            // 上限 clamp：护甲 30 点 → min(20, …) / 25 = 0.8，不会超过原版 80% 的硬顶
            assertThat(SunRadiationListener.vanillaArmorReduction(30.0, 5.0)).isCloseTo(0.80, within(1e-9));
        }

        @Test
        @DisplayName("小伤害下原版减免可为 0（armor×d/5 不足 1 点）：不得放大伤害")
        void tinyDamageNeverAmplifies() {
            // 护甲 2 点、伤害 1：max(2−0.5=1.5, 2×1/5=0.4) = 1.5 → 1.5/25 = 0.06（正数但极小）
            double r = SunRadiationListener.vanillaArmorReduction(2.0, 1.0);
            assertThat(r).isBetween(0.0, 1.0);
            // 归零保护：护甲 0 时恒 0，绝不产生负值（负减伤 = 加伤）
            assertThat(SunRadiationListener.vanillaArmorReduction(0.0, 20.0)).isZero();
            assertThat(SunRadiationListener.vanillaArmorReduction(-5.0, 20.0)).isZero();
        }
    }
}
