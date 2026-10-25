package com.ft.radsurvival.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.logging.Logger;

import org.bukkit.Particle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * HUD 字符串渲染纯函数单测（M3 验收「HUD 字符串渲染纯函数化」：格数/符号/颜色/夜晚比例，
 * plan.md §1.5 口径钉死；无服务器依赖）。
 */
class HudRendererTest {

    private static final double WARN = 50.0;
    private static final double DANGER = 75.0;

    @Nested
    @DisplayName("等级条：5 格、夜晚按 timeFactor 比例点亮（向下取整）")
    class LevelBar {

        @Test
        @DisplayName("litLevelCells：白天 5 格、夜晚 0.5→2 格（§1.5 示例 ▲▲）、边界容差")
        void litCellsProportional() {
            assertThat(HudRenderer.litLevelCells(1.0)).isEqualTo(5);          // 白天满格
            assertThat(HudRenderer.litLevelCells(0.5)).isEqualTo(2);          // 夜晚 ▲▲（2.5 向下取整）
            assertThat(HudRenderer.litLevelCells(0.999)).isEqualTo(4);        // 4.995→4
            assertThat(HudRenderer.litLevelCells(0.8)).isEqualTo(4);          // 4.0（+1e-9 容差防浮点尾差丢格）
            assertThat(HudRenderer.litLevelCells(0.0)).isZero();
            assertThat(HudRenderer.litLevelCells(1.4)).isEqualTo(5);          // 钳 1.0
            assertThat(HudRenderer.litLevelCells(-0.3)).isZero();             // 钳 0
            assertThat(HudRenderer.litLevelCells(0.3)).isEqualTo(1);          // 1.5→1
            assertThat(HudRenderer.litLevelCells(0.75)).isEqualTo(3);         // 晨昏插值中段
        }

        @Test
        @DisplayName("符号与段：白天 ▲▲▲▲▲ / 夜晚 ▲▲△△△ / 遮蔽 ●安全 / 抗尘免疫 ◆抗尘（v1.2）")
        void segmentsMatchPlanSample() {
            assertThat(HudRenderer.renderLevelBar(true, 1.0, false))
                    .isEqualTo("§6▲▲▲▲▲");
            assertThat(HudRenderer.renderLevelBar(true, 0.5, false))
                    .isEqualTo("§6▲▲§8△△△");
            assertThat(HudRenderer.renderLevelBar(false, 1.0, false))
                    .isEqualTo("§a●安全");                                   // 遮蔽优先于其他段
            // v1.2：免疫来自抗尘埃药，不再是盔甲减免 ≥1.0
            assertThat(HudRenderer.renderLevelBar(true, 1.0, true))
                    .isEqualTo("§b◆抗尘");
            assertThat(HudRenderer.renderLevelBar(false, 0.5, true))
                    .isEqualTo("§a●安全");                                   // 遮蔽时免疫段让位安全段
            assertThat(HudRenderer.renderLevelBar(true, 0.0, false))
                    .isEqualTo("§8△△△△△");                                   // 全灭格（night-multiplier=0 极端配置）
        }

        @Test
        @DisplayName("v1.2 护甲减免不再产生免疫：旧签名 renderLevelBar(...,1.0) 退化为普通等级格")
        @SuppressWarnings("deprecation")
        void armorReductionNoLongerGrantsImmunityText() {
            // 旧调用点传 1.0 也不会再显示 ◆免疫——护甲削弱后这本就不可能发生，
            // 兼容重载必须明确退化为等级格，避免历史调用静默显示「免疫」骗玩家。
            assertThat(HudRenderer.renderLevelBar(true, 1.0, 1.0)).isEqualTo("§6▲▲▲▲▲");
            assertThat(HudRenderer.IMMUNE_MARK).isEqualTo("◆免疫"); // 常量仅为外部兼容保留
            assertThat(HudRenderer.DUST_IMMUNE_MARK).isEqualTo("◆抗尘");
        }
    }

    @Nested
    @DisplayName("腐蚀条：10 格取整、显示百分比 HALF_UP、阶段色")
    class CorrosionBar {

        @Test
        @DisplayName("显示百分比取整：66.5→67、66.4→66、越界钳 [0,100]")
        void percentRoundsHalfUp() {
            assertThat(HudRenderer.corrosionDisplayPercent(66.5)).isEqualTo(67);
            assertThat(HudRenderer.corrosionDisplayPercent(66.4)).isEqualTo(66);
            assertThat(HudRenderer.corrosionDisplayPercent(-5)).isZero();
            assertThat(HudRenderer.corrosionDisplayPercent(100.4)).isEqualTo(100);
        }

        @Test
        @DisplayName("格数 = round(pct/10)：67%→7、95%→10、94.4%→9、4%→0、0/100 边界")
        void cellsTenNotches() {
            assertThat(HudRenderer.corrosionCells(67)).isEqualTo(7);
            assertThat(HudRenderer.corrosionCells(95)).isEqualTo(10);   // round(9.5) 向上
            assertThat(HudRenderer.corrosionCells(94.4)).isEqualTo(9);
            assertThat(HudRenderer.corrosionCells(4)).isZero();
            assertThat(HudRenderer.corrosionCells(0)).isZero();
            assertThat(HudRenderer.corrosionCells(100)).isEqualTo(10);
            assertThat(HudRenderer.corrosionCells(150)).isEqualTo(10);  // 钳上限
        }

        @Test
        @DisplayName("渲染样例钉死：normal §f / warn §e / danger §c（§1.5 颜色随阶段变黄/红）")
        void barRendersWithStageColor() {
            assertThat(HudRenderer.renderCorrosionBar(67, WARN, DANGER))
                    .isEqualTo("§e腐蚀 67% §e▓▓▓▓▓▓▓§8░░░");            // 67→warn 黄，7 填 3 空
            assertThat(HudRenderer.renderCorrosionBar(10, WARN, DANGER))
                    .isEqualTo("§f腐蚀 10% §f▓§8░░░░░░░░░");             // normal 默认白，1 填 9 空
            assertThat(HudRenderer.renderCorrosionBar(80, WARN, DANGER))
                    .isEqualTo("§c腐蚀 80% §c▓▓▓▓▓▓▓▓§8░░");             // danger 红，8 填 2 空
            assertThat(HudRenderer.renderCorrosionBar(0, WARN, DANGER))
                    .isEqualTo("§f腐蚀 0% §f§8░░░░░░░░░░");              // 全空
            assertThat(HudRenderer.renderCorrosionBar(100, WARN, DANGER))
                    .isEqualTo("§c腐蚀 100% §c▓▓▓▓▓▓▓▓▓▓");              // 全满
        }

        @Test
        @DisplayName("阶段色映射：0→§f、1→§e、2→§c（§1.5）")
        void stageColorMapping() {
            assertThat(HudRenderer.stageColor(0)).isEqualTo("§f");
            assertThat(HudRenderer.stageColor(1)).isEqualTo("§e");
            assertThat(HudRenderer.stageColor(2)).isEqualTo("§c");
        }
    }

    @Nested
    @DisplayName("整行与 BossBar：分隔符、bossbar-corrosion 时仅等级条")
    class Composite {

        @Test
        @DisplayName("actionbar 双条 = 等级条 §8 | 腐蚀条；自定义阈值生效")
        void actionBarJoinsBothBars() {
            assertThat(HudRenderer.renderActionBar(true, 1.0, 0.0, 67, WARN, DANGER, false))
                    .isEqualTo("§6▲▲▲▲▲§8 | §e腐蚀 67% §e▓▓▓▓▓▓▓§8░░░");
            // 阈值变体：warn=30 → 67 仍是 warn；danger=60 → 67 升 danger 红
            assertThat(HudRenderer.renderActionBar(true, 1.0, 0.0, 67, 30, 60, false))
                    .isEqualTo("§6▲▲▲▲▲§8 | §c腐蚀 67% §c▓▓▓▓▓▓▓§8░░░");
        }

        @Test
        @DisplayName("feedback.bossbar-corrosion=true → actionbar 仅等级条（腐蚀走 BossBar）")
        void bossbarModeDropsCorrosionFromActionBar() {
            assertThat(HudRenderer.renderActionBar(true, 0.5, 0.0, 67, WARN, DANGER, true))
                    .isEqualTo("§6▲▲§8△△△");
            assertThat(HudRenderer.renderActionBar(false, 1.0, 0.0, 0, WARN, DANGER, true))
                    .isEqualTo("§a●安全");
        }

        @Test
        @DisplayName("BossBar 标题带阶段后缀：·警告 / ·危险 / 无后缀")
        void bossBarTitleStageSuffix() {
            assertThat(HudRenderer.renderBossBarTitle(67, WARN, DANGER)).isEqualTo("辐射腐蚀 67%·警告");
            assertThat(HudRenderer.renderBossBarTitle(80, WARN, DANGER)).isEqualTo("辐射腐蚀 80%·危险");
            assertThat(HudRenderer.renderBossBarTitle(12, WARN, DANGER)).isEqualTo("辐射腐蚀 12%");
        }
    }

    @Nested
    @DisplayName("受击粒子降级链（§1.5：1.21.11 无 WIHN → 最接近烟雾类）")
    class ParticleFallback {

        @Test
        @DisplayName("1.21.11 jar 实钉：WIHN/WINK 缺失，解析结果 = WHITE_SMOKE")
        void resolvesToWhiteSmokeOnThisApi() {
            assertThat(FeedbackService.resolveHitParticle(Logger.getGlobal()))
                    .isEqualTo(Particle.WHITE_SMOKE);
        }
    }
}
