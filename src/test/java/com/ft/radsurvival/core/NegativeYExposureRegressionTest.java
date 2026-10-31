package com.ft.radsurvival.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 回归钉子：负 Y 区间（1.21 世界 y ∈ -64..319）的露天判定。
 *
 * <p>缺陷来源（实机复现）：v1.3 把列扫描下界写死为 {@code 0 - 1 = -1}，注释理由
 * 「1.21 世界 y 从 -64 起，0 已在其上」是错的。扫描从未读取 {@code [-64,-1]} 的方块，
 * 因此站在负 Y 的露天玩家（深板岩层洞口、矿区竖井、部分出生点）会被判成「遮蔽」，
 * 白天扛着太阳一滴血不掉——正是 plan.md §1.1 最忌讳的漏判方向。</p>
 *
 * <p>本类按「应然」断言，修复前必须红、修复后必须绿（反向验证见 DEVELOPMENT §10.5）。</p>
 */
class NegativeYExposureRegressionTest {

    private static final int MAX_HEIGHT = 320;
    private static final int MIN_HEIGHT = -64;
    private static final String WORLD = "world";

    private static SunExposureChecker checker() {
        return new SunExposureChecker(java.util.Set.of(World.Environment.NORMAL), true);
    }

    @Test
    @DisplayName("负 Y 露天：站在 y=-60，头顶全空、脚下实心 → 判露天")
    void exposedAtNegativeY() {
        SunExposureChecker c = checker();
        boolean exposed = c.isExposed(World.Environment.NORMAL, WORLD,
                0, -60, 0, MAX_HEIGHT, MIN_HEIGHT, y -> y <= -61);

        assertThat(exposed)
                .as("y=-60 露天玩家必须判露天（修复前误判为遮蔽）")
                .isTrue();
    }

    @Test
    @DisplayName("负 Y 遮蔽：站在 y=-60，头顶 y=-50 有顶 → 判遮蔽")
    void coveredAtNegativeY() {
        SunExposureChecker c = checker();
        boolean exposed = c.isExposed(World.Environment.NORMAL, WORLD,
                0, -60, 0, MAX_HEIGHT, MIN_HEIGHT, y -> y <= -61 || y == -50);

        assertThat(exposed)
                .as("负 Y 上空有实心顶时必须判遮蔽")
                .isFalse();
    }

    @Test
    @DisplayName("负 Y 边界：站在世界最低层 y=-63，头顶全空 → 判露天")
    void exposedAtWorldBottom() {
        SunExposureChecker c = checker();
        boolean exposed = c.isExposed(World.Environment.NORMAL, WORLD,
                0, -63, 0, MAX_HEIGHT, MIN_HEIGHT, y -> y <= -64);

        assertThat(exposed).as("世界最底层露天也必须判露天").isTrue();
    }

    @Test
    @DisplayName("正 Y 对照：同样口径在 y=64 判定不受影响")
    void exposedAtPositiveY() {
        SunExposureChecker c = checker();
        boolean exposed = c.isExposed(World.Environment.NORMAL, WORLD,
                0, 64, 0, MAX_HEIGHT, MIN_HEIGHT, y -> y <= 63);

        assertThat(exposed).as("正 Y 区间行为不变").isTrue();
    }

    @Test
    @DisplayName("内核契约：扫描起点 = worldMinHeight - 1")
    void scanStartFollowsWorldMinHeight() {
        assertThat(SunExposureChecker.columnScanStartY(-64)).isEqualTo(-65);
        assertThat(SunExposureChecker.columnScanStartY(0)).isEqualTo(-1);
        assertThat(SunExposureChecker.columnScanStartY(256)).isEqualTo(255);
    }
}
