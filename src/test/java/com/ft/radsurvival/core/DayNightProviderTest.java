package com.ft.radsurvival.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ft.radsurvival.core.DayNightProvider.Phase;
import com.ft.radsurvival.core.DayNightProvider.TimeInfo;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DayNightProvider 纯逻辑测试（plan.md §1.2，M2a）：全区间边界值、插值中点、
 * 硬切换归属口径、factor 单调性与值域。无 Bukkit 服务器依赖。
 */
class DayNightProviderTest {

    private static final double NIGHT = 0.5; // 默认 night-multiplier（§3）
    private static final double EPS = 1e-9;

    // ---------------------------------------------------------------- 阶段边界

    @Test
    @DisplayName("阶段划分：左闭右开 0..12000 白天 / 12000..13000 黄昏 / 13000..23000 夜晚 / 23000..24000 黎明")
    void phaseBoundaries() {
        assertThat(DayNightProvider.phaseOfDayTime(0)).isEqualTo(Phase.DAY);
        assertThat(DayNightProvider.phaseOfDayTime(1)).isEqualTo(Phase.DAY);
        assertThat(DayNightProvider.phaseOfDayTime(11999)).isEqualTo(Phase.DAY);
        assertThat(DayNightProvider.phaseOfDayTime(12000)).isEqualTo(Phase.DUSK); // 黄昏含首刻
        assertThat(DayNightProvider.phaseOfDayTime(12500)).isEqualTo(Phase.DUSK);
        assertThat(DayNightProvider.phaseOfDayTime(12999)).isEqualTo(Phase.DUSK);
        assertThat(DayNightProvider.phaseOfDayTime(13000)).isEqualTo(Phase.NIGHT); // 夜晚含首刻
        assertThat(DayNightProvider.phaseOfDayTime(18000)).isEqualTo(Phase.NIGHT);
        assertThat(DayNightProvider.phaseOfDayTime(22999)).isEqualTo(Phase.NIGHT);
        assertThat(DayNightProvider.phaseOfDayTime(23000)).isEqualTo(Phase.DAWN); // 黎明含首刻
        assertThat(DayNightProvider.phaseOfDayTime(23500)).isEqualTo(Phase.DAWN);
        assertThat(DayNightProvider.phaseOfDayTime(23999)).isEqualTo(Phase.DAWN);
    }

    @Test
    @DisplayName("floorMod 归一：24000≡0 回白天；累计 worldTime 与负值正确取模")
    void dayTimeWrappedAndAccumulated() {
        assertThat(DayNightProvider.phaseOfDayTime(24000)).isEqualTo(Phase.DAY); // ≡ 0
        assertThat(DayNightProvider.phaseOfDayTime(24001)).isEqualTo(Phase.DAY);
        assertThat(DayNightProvider.phaseOfDayTime(25000)).isEqualTo(Phase.DAY); // ≡ 1000
        assertThat(DayNightProvider.phaseOfDayTime(36000)).isEqualTo(Phase.DUSK); // ≡ 12000
        assertThat(DayNightProvider.phaseOfDayTime(-1)).isEqualTo(Phase.DAWN); // floorMod(-1,24000)=23999
        assertThat(DayNightProvider.phaseOfDayTime(-12000)).isEqualTo(Phase.DUSK); // floorMod(-12000,24000)=12000
        // 因子同样先取模：61000 ≡ 13000 → 夜晚值
        assertThat(DayNightProvider.timeFactorOfDayTime(61000, NIGHT, true))
                .isCloseTo(NIGHT, org.assertj.core.data.Offset.offset(EPS));
    }

    // ---------------------------------------------------------------- 平滑插值

    @Test
    @DisplayName("smooth=true：边界/中点插值——12500 与 23500 恰为 (1.0+0.5)/2=0.75")
    void smoothInterpolationKeyPoints() {
        assertThat(DayNightProvider.timeFactorOfDayTime(0, NIGHT, true)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(EPS));
        assertThat(DayNightProvider.timeFactorOfDayTime(11999, NIGHT, true)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(EPS));
        // 黄昏两端点：12000 连续取白天值、13000 连续取夜晚值
        assertThat(DayNightProvider.timeFactorOfDayTime(12000, NIGHT, true)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(EPS));
        assertThat(DayNightProvider.timeFactorOfDayTime(12500, NIGHT, true)).isCloseTo(0.75, org.assertj.core.data.Offset.offset(EPS));
        assertThat(DayNightProvider.timeFactorOfDayTime(13000, NIGHT, true)).isCloseTo(NIGHT, org.assertj.core.data.Offset.offset(EPS));
        assertThat(DayNightProvider.timeFactorOfDayTime(18000, NIGHT, true)).isCloseTo(NIGHT, org.assertj.core.data.Offset.offset(EPS));
        assertThat(DayNightProvider.timeFactorOfDayTime(22999, NIGHT, true)).isCloseTo(NIGHT, org.assertj.core.data.Offset.offset(EPS));
        assertThat(DayNightProvider.timeFactorOfDayTime(23000, NIGHT, true)).isCloseTo(NIGHT, org.assertj.core.data.Offset.offset(EPS));
        assertThat(DayNightProvider.timeFactorOfDayTime(23500, NIGHT, true)).isCloseTo(0.75, org.assertj.core.data.Offset.offset(EPS));
        // 黎明末端趋近 1.0，24000 ≡ 0 回白天
        assertThat(DayNightProvider.timeFactorOfDayTime(23999, NIGHT, true)).isCloseTo(0.5 + 0.5 * 0.999, org.assertj.core.data.Offset.offset(EPS));
        assertThat(DayNightProvider.timeFactorOfDayTime(24000, NIGHT, true)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(EPS));
    }

    @Test
    @DisplayName("smooth=true 自定义 night-multiplier=0.25：插值中点 0.625")
    void smoothHonorsCustomNightMultiplier() {
        assertThat(DayNightProvider.timeFactorOfDayTime(12500, 0.25, true)).isCloseTo(0.625, org.assertj.core.data.Offset.offset(EPS));
        assertThat(DayNightProvider.timeFactorOfDayTime(23500, 0.25, true)).isCloseTo(0.625, org.assertj.core.data.Offset.offset(EPS));
        assertThat(DayNightProvider.timeFactorOfDayTime(13000, 0.25, true)).isCloseTo(0.25, org.assertj.core.data.Offset.offset(EPS));
    }

    @Test
    @DisplayName("smooth=true 单调性：黄昏非增且严格递减段中点递减、夜晚平坦、黎明非减")
    void smoothMonotonic() {
        // 黄昏：单调非增，且相邻步（250 tick）严格递减直到进入夜晚值
        double prev = DayNightProvider.timeFactorOfDayTime(12000, NIGHT, true);
        for (long t = 12250; t <= 13000; t += 250) {
            double cur = DayNightProvider.timeFactorOfDayTime(t, NIGHT, true);
            assertThat(cur).isLessThanOrEqualTo(prev + EPS);
            assertThat(cur).isLessThan(prev); // 过渡带内严格递减
            prev = cur;
        }
        // 夜晚平坦
        for (long t = 13000; t < 23000; t += 500) {
            assertThat(DayNightProvider.timeFactorOfDayTime(t, NIGHT, true)).isCloseTo(NIGHT, org.assertj.core.data.Offset.offset(EPS));
        }
        // 黎明：单调非减，严格递增至白天值
        prev = DayNightProvider.timeFactorOfDayTime(23000, NIGHT, true);
        for (long t = 23250; t <= 23999; t += 250) {
            double cur = DayNightProvider.timeFactorOfDayTime(t, NIGHT, true);
            assertThat(cur).isGreaterThanOrEqualTo(prev - EPS);
            assertThat(cur).isGreaterThan(prev);
            prev = cur;
        }
    }

    @Test
    @DisplayName("全天采样值域恒在 [night, 1.0]，不发生越界（插值不产生 <0.5 或 >1.0）")
    void smoothRangeBounded() {
        for (long t = 0; t < 24000; t += 37) {
            double f = DayNightProvider.timeFactorOfDayTime(t, NIGHT, true);
            assertThat(f).isBetween(NIGHT, 1.0);
        }
    }

    // ---------------------------------------------------------------- 硬切换

    @Test
    @DisplayName("smooth=false 硬切换口径钉死：12000 起整段黄昏归夜晚档(0.5)、23000 起整段黎明归白天档(1.0)")
    void hardSwitchOwnershipPinned() {
        assertThat(DayNightProvider.timeFactorOfDayTime(11999, NIGHT, false)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(EPS));
        assertThat(DayNightProvider.timeFactorOfDayTime(12000, NIGHT, false)).isCloseTo(NIGHT, org.assertj.core.data.Offset.offset(EPS)); // 边界跳变点
        assertThat(DayNightProvider.timeFactorOfDayTime(12500, NIGHT, false)).isCloseTo(NIGHT, org.assertj.core.data.Offset.offset(EPS)); // 黄昏余段 = 夜晚
        assertThat(DayNightProvider.timeFactorOfDayTime(12999, NIGHT, false)).isCloseTo(NIGHT, org.assertj.core.data.Offset.offset(EPS));
        assertThat(DayNightProvider.timeFactorOfDayTime(13000, NIGHT, false)).isCloseTo(NIGHT, org.assertj.core.data.Offset.offset(EPS));
        assertThat(DayNightProvider.timeFactorOfDayTime(22999, NIGHT, false)).isCloseTo(NIGHT, org.assertj.core.data.Offset.offset(EPS));
        assertThat(DayNightProvider.timeFactorOfDayTime(23000, NIGHT, false)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(EPS)); // 边界跳变点
        assertThat(DayNightProvider.timeFactorOfDayTime(23500, NIGHT, false)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(EPS)); // 黎明余段 = 白天
        assertThat(DayNightProvider.timeFactorOfDayTime(23999, NIGHT, false)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(EPS));
        assertThat(DayNightProvider.timeFactorOfDayTime(0, NIGHT, false)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(EPS));
        assertThat(DayNightProvider.timeFactorOfDayTime(24000, NIGHT, false)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(EPS));
    }

    // ---------------------------------------------------------------- 实例装配

    @Test
    @DisplayName("实例/适配入口与纯内核同口径（含累计 worldTime 取模）")
    void instanceDelegatesToKernel() {
        DayNightProvider provider = new DayNightProvider(NIGHT, true);
        assertThat(provider.timeFactor(12500)).isCloseTo(0.75, org.assertj.core.data.Offset.offset(EPS));
        assertThat(provider.phase(12500)).isEqualTo(Phase.DUSK);
        TimeInfo wrapped = provider.forWorldTime(24000L + 12500L); // 第二天黄昏
        assertThat(wrapped.phase()).isEqualTo(Phase.DUSK);
        assertThat(wrapped.timeFactor()).isCloseTo(0.75, org.assertj.core.data.Offset.offset(EPS));
        assertThat(provider.nightMultiplier()).isEqualTo(NIGHT);
        assertThat(provider.smoothTransition()).isTrue();
    }
}
