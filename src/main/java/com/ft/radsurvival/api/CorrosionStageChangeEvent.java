package com.ft.radsurvival.api;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import com.ft.radsurvival.core.CorrosionMeter;
import com.ft.radsurvival.core.RadiationMath;

/**
 * 腐蚀阶段跳变事件（plan.md §2 api/CorrosionStageChangeEvent.java、§1.6「阶段与提示」接口形态）。
 *
 * <p>包装 {@link CorrosionMeter.CorrosionStageListener} 的内存回调：腐蚀值跨越
 * stages.warn / stages.danger 阈值（含 {@code /rad corrosion set}、{@link RadSurvivalApi}
 * 写入与死亡 reset/decay 引起的回档）时，由 M3 反馈层
 * （{@code feedback/FeedbackService}）在主线程构造并广播本事件。</p>
 *
 * <p><b>Cancellable 语义（§1.6/M3 钉死）</b>：取消本事件 <b>仅抑制播报反馈</b>
 * （title/subtitle + 音效）——腐蚀数值在回调发生前已由 {@link CorrosionMeter} 权威写入，
 * 取消不会回滚、冻结或修改任何数值。外部插件若想改数值，请在跳变后经
 * {@link RadSurvivalApi#setCorrosion} 覆写。</p>
 *
 * <p>携带：玩家 UUID、在线玩家（离线时 null——如死亡回档后立刻退服的极端时序）、
 * old/new 阶段（口径同 {@link RadiationMath}：0=normal、1=warn、2=danger）、
 * 跳变后的腐蚀值与新阶段阈值（stages.warn / stages.danger 配置值）。</p>
 */
public final class CorrosionStageChangeEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final Player player;
    private final double corrosion;
    private final int oldStage;
    private final int newStage;
    private final double warnThreshold;
    private final double dangerThreshold;
    private boolean cancelled;

    /**
     * 由反馈层在阶段回调处构造并广播（主线程）。
     *
     * @param playerId       玩家 UUID
     * @param player         在线玩家；离线为 null
     * @param corrosion      跳变后的权威腐蚀值 [0,100]（§1.6，内部浮点）
     * @param oldStage       旧阶段（0/1/2，{@link CorrosionMeter} 常量口径）
     * @param newStage       新阶段（0/1/2）
     * @param warnThreshold  stages.warn 阈值（%）
     * @param dangerThreshold stages.danger 阈值（%）
     */
    public CorrosionStageChangeEvent(UUID playerId, Player player, double corrosion,
                                     int oldStage, int newStage,
                                     double warnThreshold, double dangerThreshold) {
        this.playerId = playerId;
        this.player = player;
        this.corrosion = corrosion;
        this.oldStage = oldStage;
        this.newStage = newStage;
        this.warnThreshold = warnThreshold;
        this.dangerThreshold = dangerThreshold;
    }

    // ------------------------------------------------------------------ 只读字段

    public UUID getPlayerId() {
        return playerId;
    }

    /** 在线玩家；离线为 null（播报侧应跳过，事件仍完整广播供外部订阅）。 */
    public Player getPlayer() {
        return player;
    }

    /** 跳变后的权威腐蚀值 [0,100]（§1.6）。 */
    public double getCorrosion() {
        return corrosion;
    }

    public int getOldStage() {
        return oldStage;
    }

    public int getNewStage() {
        return newStage;
    }

    /** stages.warn 配置阈值（%，§3）。 */
    public double getWarnThreshold() {
        return warnThreshold;
    }

    /** stages.danger 配置阈值（%，§3）。 */
    public double getDangerThreshold() {
        return dangerThreshold;
    }

    /** 是否为升档（normal→warn、warn→danger 等，§1.6 穿越方向）。 */
    public boolean isUpward() {
        return newStage > oldStage;
    }

    /** 阶段常量的中文可读名（播报与日志用）。 */
    public static String stageName(int stage) {
        return switch (stage) {
            case CorrosionMeter.STAGE_WARN -> "警告";
            case CorrosionMeter.STAGE_DANGER -> "危险";
            default -> "正常";
        };
    }

    // ---------------------------------------------------------------- Cancellable

    /**
     * 取消 = 仅抑制播报反馈（title/音效），不改任何腐蚀数值——见类 javadoc 语义钉死（§1.6）。
     */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    // -------------------------------------------------------------------- Handler

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
