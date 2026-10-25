package com.ft.radsurvival.feedback;

import com.ft.radsurvival.core.CorrosionMeter;
import com.ft.radsurvival.core.RadiationMath;

/**
 * HUD 字符串渲染纯函数集（plan.md §1.5 反馈表现，M3；不含任何 Bukkit 服务器调用，
 * 全部单测无需运行中服务器）。
 *
 * <p><b>辐射等级条</b>（5 格）：露天白天 {@code ▲▲▲▲▲}；夜晚/晨昏按 timeFactor
 * 比例点亮格数——{@code 点亮格 = floor(clamp(timeFactor,0,1) × 5 + 1e-9)}（向下取整，
 * 夜晚 0.5 → 2 格 = {@code ▲▲△△△}，与 §1.5 示例一致；1e-9 容差抵消浮点尾差）；
 * 遮蔽 → {@code ●安全}；免疫（减免 ≥1）且露天 → {@code ◆免疫} 替代等级格（M3 口径）。</p>
 *
 * <p><b>腐蚀条</b>（10 格）：{@code 腐蚀 67% ▓▓▓▓▓▓▓░░}——显示百分比取整
 * （{@link Math#round(double)} HALF_UP，内部浮点不外泄，§1.6「显示取整」）；
 * 格数 = round(显示百分比/10)，例 67% → 7 格、95% → 10 格、4% → 0 格；
 * 颜色按阶段：normal 默认白 §f、warn 黄 §e、danger 红 §c（§1.5）。</p>
 */
public final class HudRenderer {

    /** 辐射等级条格数（§1.5 示例 ▲×5）。 */
    public static final int LEVEL_CELLS = 5;
    /** 腐蚀条格数（§1.5 示例 ▓▓▓▓▓▓▓░░ = 7+3）。 */
    public static final int CORROSION_CELLS = 10;

    /** 等级条点亮格符号。 */
    public static final String LEVEL_ON = "▲";
    /** 等级条熄灭格符号（空心，示意「比例未点满」）。 */
    public static final String LEVEL_OFF = "△";
    /** 遮蔽段。 */
    public static final String SAFE_MARK = "●安全";
    /** 免疫段（减免 ≥1 且露天，替代等级格，§1.5/M3）。 */
    public static final String IMMUNE_MARK = "◆免疫";
    /** 腐蚀条填充格。 */
    public static final String CORROSION_ON = "▓";
    /** 腐蚀条空格。 */
    public static final String CORROSION_OFF = "░";

    /** 阶段色：normal = 默认白（§1.5）。 */
    public static final String COLOR_NORMAL = "§f";
    /** 阶段色：warn = 黄。 */
    public static final String COLOR_WARN = "§e";
    /** 阶段色：danger = 红。 */
    public static final String COLOR_DANGER = "§c";
    /** 遮蔽段颜色（绿=安全）。 */
    public static final String COLOR_SAFE = "§a";
    /** 免疫段颜色（青=护甲生效）。 */
    public static final String COLOR_IMMUNE = "§b";
    /** 等级条点亮格颜色（金：日晒警示色，独立于腐蚀阶段色）。 */
    public static final String COLOR_LEVEL_ON = "§6";
    /** 熄灭格 / 分隔符等弱化颜色（灰）。 */
    public static final String COLOR_DIM = "§8";
    /** 双条分隔符。 */
    public static final String SEPARATOR = COLOR_DIM + " | ";

    private HudRenderer() {
    }

    // ---------------------------------------------------------------- 等级条

    /** timeFactor → 点亮格数：clamp [0,1] 后 ×5 向下取整（+1e-9 容差；夜晚 0.5 → 2）。 */
    public static int litLevelCells(double timeFactor) {
        double clamped = RadiationMath.clampUnit(timeFactor);
        return (int) Math.floor(clamped * LEVEL_CELLS + 1e-9);
    }

    /**
     * 辐射等级条（着色）。优先级：遮蔽 → 免疫 → 比例格（§1.5）。
     *
     * @param exposed        §1.1 露天判定（状态镜像值）
     * @param timeFactor     §1.2 昼夜因子（夜晚比例点亮依据）
     * @param armorReduction §1.4 盔甲减免（≥1.0 = 免疫）
     */
    public static String renderLevelBar(boolean exposed, double timeFactor, double armorReduction) {
        if (!exposed) {
            return COLOR_SAFE + SAFE_MARK;
        }
        if (armorReduction >= 1.0) {
            return COLOR_IMMUNE + IMMUNE_MARK;
        }
        int lit = litLevelCells(timeFactor);
        StringBuilder sb = new StringBuilder();
        if (lit > 0) {
            sb.append(COLOR_LEVEL_ON).append(repeat(LEVEL_ON, lit));
        }
        if (lit < LEVEL_CELLS) {
            sb.append(COLOR_DIM).append(repeat(LEVEL_OFF, LEVEL_CELLS - lit));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 腐蚀条

    /** 腐蚀显示百分比（0~100 整数，HALF_UP 取整；内部浮点不外泄，§1.6）。 */
    public static int corrosionDisplayPercent(double corrosion) {
        return (int) Math.round(RadiationMath.clampCorrosion(corrosion));
    }

    /** 腐蚀条填充格数 = round(显示百分比 / 10)，钳 [0,10]（§1.5 示例 67% → 7 格）。 */
    public static int corrosionCells(double corrosion) {
        int pct = corrosionDisplayPercent(corrosion);
        return Math.min(CORROSION_CELLS, Math.round(pct / 10.0f));
    }

    /**
     * 腐蚀条（着色）：{@code 腐蚀 67% ▓▓▓▓▓▓▓░░}，颜色按阶段映射
     * （{@link #stageColor(int)}：normal §f / warn §e / danger §c；阈值取配置 stages.warn/danger）。
     */
    public static String renderCorrosionBar(double corrosion, double warnThreshold, double dangerThreshold) {
        int pct = corrosionDisplayPercent(corrosion);
        String color = stageColor(RadiationMath.stageOf(corrosion, warnThreshold, dangerThreshold));
        int filled = corrosionCells(corrosion);
        StringBuilder sb = new StringBuilder(color).append("腐蚀 ").append(pct).append("% ")
                .append(color).append(repeat(CORROSION_ON, filled));
        if (filled < CORROSION_CELLS) { // 满格不留悬空 §8 灰码（HUD 纯函数单测钉死）
            sb.append(COLOR_DIM).append(repeat(CORROSION_OFF, CORROSION_CELLS - filled));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 阶段配色

    /** 阶段 → 颜色代码（§1.5：normal 默认白、warn 黄、danger 红；未知按 normal）。 */
    public static String stageColor(int stage) {
        return switch (stage) {
            case CorrosionMeter.STAGE_WARN -> COLOR_WARN;
            case CorrosionMeter.STAGE_DANGER -> COLOR_DANGER;
            default -> COLOR_NORMAL;
        };
    }

    /** BossBar 常驻腐蚀条标题（feedback.bossbar-corrosion=true 时使用，M3 新键）。 */
    public static String renderBossBarTitle(double corrosion, double warnThreshold, double dangerThreshold) {
        int pct = corrosionDisplayPercent(corrosion);
        int stage = RadiationMath.stageOf(corrosion, warnThreshold, dangerThreshold);
        String suffix = switch (stage) {
            case CorrosionMeter.STAGE_WARN -> "·警告";
            case CorrosionMeter.STAGE_DANGER -> "·危险";
            default -> "";
        };
        return "辐射腐蚀 " + pct + "%" + suffix;
    }

    /**
     * actionbar 完整行：等级条 [SEPARATOR 腐蚀条]（{@code bossbarCorrosion=true} 时
     * 腐蚀条走 BossBar 常驻、actionbar 仅等级条，M3 新键口径）。
     */
    public static String renderActionBar(boolean exposed, double timeFactor, double armorReduction,
                                         double corrosion, double warnThreshold, double dangerThreshold,
                                         boolean bossbarCorrosion) {
        String level = renderLevelBar(exposed, timeFactor, armorReduction);
        if (bossbarCorrosion) {
            return level;
        }
        return level + SEPARATOR + renderCorrosionBar(corrosion, warnThreshold, dangerThreshold);
    }

    /** 纯 ASCII 渲染辅助：重复符号 n 次（n≤0 返回空串）。 */
    public static String repeat(String unit, int times) {
        return times <= 0 ? "" : unit.repeat(times);
    }
}
