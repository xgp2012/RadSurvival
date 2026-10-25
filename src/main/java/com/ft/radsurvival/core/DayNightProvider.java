package com.ft.radsurvival.core;

import org.bukkit.World;

import com.ft.radsurvival.config.PluginConfig;

/**
 * 昼夜判定与伤害时间因子（plan.md §1.2）。
 *
 * <p>拆分为「纯逻辑内核 + 薄 Bukkit 适配层」：</p>
 * <ul>
 *   <li>纯内核：静态方法 {@link #phaseOfDayTime(long)} / {@link #timeFactorOfDayTime(long, double, boolean)}，
 *       只接受取模后的游戏日时刻，不触服务器，单元测试直接调用；</li>
 *   <li>适配层：实例方法从 {@link PluginConfig} 读 night-multiplier / smooth-transition，
 *       {@link #forWorld(World)} 以 {@code world.getTime()} 为输入（§2 core/DayNightProvider.java）。</li>
 * </ul>
 *
 * <p>时段划分（§1.2，边界<b>左闭右开</b>）：{@code 0..12000} 白天、{@code 12000..13000} 黄昏、
 * {@code 13000..23000} 夜晚、{@code 23000..24000} 黎明。</p>
 */
public final class DayNightProvider {

    /** MC 一天 24000 tick（§1.2）。 */
    public static final long DAY_LENGTH_TICKS = 24000L;
    /** 黄昏起点：白天 → 夜晚过渡开始（§1.2）。 */
    public static final long DUSK_START = 12000L;
    /** 夜晚起点（黄昏终点）：插值到达 night-multiplier（§1.2）。 */
    public static final long NIGHT_START = 13000L;
    /** 黎明起点：夜晚 → 白天过渡开始（§1.2）。 */
    public static final long DAWN_START = 23000L;
    /** 黄昏/黎明过渡带宽：各 1000 tick（§1.2）。 */
    public static final long TRANSITION_TICKS = 1000L;

    /** 昼夜阶段（§1.2 四段）。 */
    public enum Phase {
        DAY("白天"), DUSK("黄昏"), NIGHT("夜晚"), DAWN("黎明");

        private final String displayName;

        Phase(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** 某一时刻的阶段 + 伤害因子快照。 */
    public record TimeInfo(Phase phase, double timeFactor) {
    }

    private final double nightMultiplier;
    private final boolean smoothTransition;

    public DayNightProvider(double nightMultiplier, boolean smoothTransition) {
        this.nightMultiplier = nightMultiplier;
        this.smoothTransition = smoothTransition;
    }

    /** 从类型安全配置装配（night-multiplier / smooth-transition，§3；值域已由 M1 校验）。 */
    public DayNightProvider(PluginConfig config) {
        this(config.nightMultiplier(), config.smoothTransition());
    }

    public double nightMultiplier() {
        return nightMultiplier;
    }

    public boolean smoothTransition() {
        return smoothTransition;
    }

    // ---------------------------------------------------------------- 适配层

    /** 输入任意 world.getTime()（自世界创建以来的累计 tick，可能 ≥ 24000），内部自动取模。 */
    public TimeInfo forWorldTime(long worldTime) {
        return new TimeInfo(phase(worldTime), timeFactor(worldTime));
    }

    /** 薄 Bukkit 适配：以 world.getTime() 为输入（§1.2；主循环 M2b 接线用）。 */
    public TimeInfo forWorld(World world) {
        return forWorldTime(world.getTime());
    }

    public Phase phase(long worldTime) {
        return phaseOfDayTime(worldTime);
    }

    public double timeFactor(long worldTime) {
        return timeFactorOfDayTime(worldTime, nightMultiplier, smoothTransition);
    }

    // ---------------------------------------------------------------- 纯内核

    /**
     * 纯内核：阶段判定。先 {@code floorMod(24000)} 归一到 [0,24000)，再按 §1.2 左闭右开分段。
     * 边界口径（单测钉死）：12000 = 黄昏首刻（因子仍是 1.0，插值起点）、13000 = 夜晚首刻、
     * 23000 = 黎明首刻、24000 ≡ 0 = 白天首刻。
     */
    public static Phase phaseOfDayTime(long dayTime) {
        long t = Math.floorMod(dayTime, DAY_LENGTH_TICKS);
        if (t < DUSK_START) {
            return Phase.DAY;
        }
        if (t < NIGHT_START) {
            return Phase.DUSK;
        }
        if (t < DAWN_START) {
            return Phase.NIGHT;
        }
        return Phase.DAWN;
    }

    /**
     * 纯内核：伤害时间因子（白天 1.0、夜晚 night-multiplier，默认 0.5，§1.2/§1.3）。
     *
     * <p>smooth=true：黄昏 [12000,13000) 从 1.0 线性插值到 night（progress=(t−12000)/1000），
     * 黎明 [23000,24000) 从 night 线性插值回 1.0；过渡带两端点取值连续（12000→1.0、13000→night、
     * 23000→night、24000≡0→1.0）。</p>
     *
     * <p>smooth=false 硬切换（M2a 钉死口径）：<b>切换点取 12000 / 23000</b>——黄昏余段
     * [12000,13000) 整体归夜晚档（12000 起即 ×night-multiplier），黎明余段 [23000,24000)
     * 整体归白天档（23000 起即 ×1.0）。即 t ∈ [12000,23000) → night，否则 → 1.0。
     * 注意与 M1 {@code /rad status} 的内联实现（以 13000/23000 为界）口径不同，
     * M2b 接线后统一以本类为准。</p>
     */
    public static double timeFactorOfDayTime(long dayTime, double nightMultiplier, boolean smooth) {
        long t = Math.floorMod(dayTime, DAY_LENGTH_TICKS);
        if (!smooth) {
            return (t >= DUSK_START && t < DAWN_START) ? nightMultiplier : 1.0;
        }
        if (t < DUSK_START) {
            return 1.0;
        }
        if (t < NIGHT_START) {
            return lerp(1.0, nightMultiplier, (t - DUSK_START) / (double) TRANSITION_TICKS);
        }
        if (t < DAWN_START) {
            return nightMultiplier;
        }
        return lerp(nightMultiplier, 1.0, (t - DAWN_START) / (double) TRANSITION_TICKS);
    }

    /** 线性插值（内核私有工具，口径即 from + (to−from)×progress，progress ∈ [0,1]）。 */
    private static double lerp(double from, double to, double progress) {
        return from + (to - from) * progress;
    }
}
