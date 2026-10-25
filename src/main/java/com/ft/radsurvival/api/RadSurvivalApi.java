package com.ft.radsurvival.api;

import java.util.UUID;

import org.bukkit.entity.Player;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.core.CorrosionMeter;
import com.ft.radsurvival.core.RadiationServices;

/**
 * 对外扩展统一入口（plan.md §2 api/RadSurvivalApi.java、§6「读写收敛」）。
 *
 * <p>M2b 最小门面：仅腐蚀值读/写两族方法——<b>读</b>优先内存权威、在线未登记时回读
 * Player PDC（{@code radsurvival:corrosion}）；<b>写</b>一律走
 * {@link CorrosionMeter}（钳 [0,100] + 阶段迁移回调分发）并同步落 PDC 与状态镜像，
 * 保证外部插件写入与主循环/命令写入口径完全一致（§6「腐蚀 PDC 被其他插件乱写」对策）。</p>
 *
 * <p>M3 已接线（§1.5/§1.6）：{@code CorrosionMeter} 的内存阶段回调由反馈层桥接为
 * Bukkit 事件 {@link CorrosionStageChangeEvent}（取消仅抑制播报、不改数值）；本门面
 * 同步收敛<b>阶段读</b>（{@link #getCorrosionStage}）与<b>辐射状态只读</b>
 * （露天/时间因子/减免/免疫）入口——外部插件一律经此读取，不触碰
 * {@code RadiationTask#statesView}；<b>写</b>仍只走 {@link #setCorrosion}/{@link #addCorrosion}
 * （内部 CorrosionMeter，钳制 + 阶段广播 + PDC 落盘）。</p>
 *
 * <p>获取方式：{@code plugin.api()}。</p>
 */
public final class RadSurvivalApi {

    private final RadSurvivalPlugin plugin;

    public RadSurvivalApi(RadSurvivalPlugin plugin) {
        this.plugin = plugin;
    }

    /** 插件是否已启用（配置合法且服务完成装配）。 */
    public boolean isReady() {
        return plugin.services() != null;
    }

    /**
     * 读腐蚀值 [0,100]：内存权威优先；无内存记录（离线/未 join）返回 0——
     * 离线玩家的持久值请经 {@link #getCorrosion(Player)} 传入在线玩家读取。
     */
    public double getCorrosion(UUID playerId) {
        RadiationServices services = plugin.services();
        return services == null ? 0.0 : services.corrosionMeter().get(playerId);
    }

    /** 读玩家腐蚀值：内存无权威记录时从该玩家 PDC 恢复读取（不强制登记状态表，只回灌内存权威值）。 */
    public double getCorrosion(Player player) {
        RadiationServices services = plugin.services();
        if (services == null) {
            return 0.0;
        }
        CorrosionMeter meter = services.corrosionMeter();
        return meter.hasRecorded(player.getUniqueId())
                ? meter.get(player.getUniqueId())
                : meter.loadFromPlayer(player);
    }

    /**
     * 写玩家腐蚀值（钳 [0,100]，§1.6）：CorrosionMeter.setValue → PDC 落盘 →
     * 状态镜像同步；触发阶段迁移内存回调（跨 warn/danger 时 M3 播报据此消费）。
     *
     * @return 钳制后的新值
     */
    public double setCorrosion(Player player, double rawValue) {
        RadiationServices services = plugin.services();
        if (services == null) {
            return 0.0;
        }
        CorrosionMeter meter = services.corrosionMeter();
        double applied = meter.setValue(player.getUniqueId(), rawValue).value();
        meter.saveToPlayer(player);
        var state = services.radiationTask().stateOf(player.getUniqueId());
        if (state != null) {
            state.setCorrosion(applied);
        }
        return applied;
    }

    /** 增量写（预留 §4.3「抗辐宁 = add(-n)」；钳制与持久化口径同 {@link #setCorrosion}）。 */
    public double addCorrosion(Player player, double delta) {
        RadiationServices services = plugin.services();
        if (services == null) {
            return 0.0;
        }
        CorrosionMeter meter = services.corrosionMeter();
        double applied = meter.addValue(player.getUniqueId(), delta).value();
        meter.saveToPlayer(player);
        var state = services.radiationTask().stateOf(player.getUniqueId());
        if (state != null) {
            state.setCorrosion(applied);
        }
        return applied;
    }

    // ------------------------------------------------------ M3：阶段与辐射状态只读入口

    /**
     * 当前腐蚀阶段（0=normal / 1=warn / 2=danger，常量同 {@link CorrosionMeter}，§1.6）。
     * 读取 {@code CorrosionMeter} 的内存阶段基线；未登记玩家按 normal。
     */
    public int getCorrosionStage(UUID playerId) {
        RadiationServices services = plugin.services();
        return services == null ? CorrosionMeter.STAGE_NORMAL
                : services.corrosionMeter().currentStage(playerId);
    }

    /** 玩家当前腐蚀阶段（M2b 口径先保证内存权威记录存在，再读阶段）。 */
    public int getCorrosionStage(Player player) {
        getCorrosion(player); // 未登记时从 PDC 回灌（幂等）
        RadiationServices services = plugin.services();
        return services == null ? CorrosionMeter.STAGE_NORMAL
                : services.corrosionMeter().currentStage(player.getUniqueId());
    }

    /** stages.warn 阈值（%，§3；未配置/未启用回 50 默认口径）。 */
    public double getStageWarnThreshold() {
        var cfg = plugin.config();
        return cfg == null ? 50.0 : cfg.corrosion().stageWarn();
    }

    /** stages.danger 阈值（%，§3；未配置/未启用回 75 默认口径）。 */
    public double getStageDangerThreshold() {
        var cfg = plugin.config();
        return cfg == null ? 75.0 : cfg.corrosion().stageDanger();
    }

    /** §1.1 露天判定（状态镜像，最近一轮结算口径；未登记视为遮蔽安全）。 */
    public boolean isExposed(Player player) {
        var state = stateOf(player);
        return state != null && state.isExposed();
    }

    /** §1.2 当前昼夜伤害因子（状态镜像；未登记缺省 1.0 白天口径）。 */
    public double getTimeFactor(Player player) {
        var state = stateOf(player);
        return state == null ? 1.0 : state.getTimeFactor();
    }

    /** §1.4 当前盔甲减免 [0,1]（状态镜像；未登记缺省 0）。 */
    public double getArmorReduction(Player player) {
        var state = stateOf(player);
        return state == null ? 0.0 : state.getArmorReduction();
    }

    /** 是否处于完全免疫（减免 ≥ 1.0，§1.3 口径；未登记 = 不免疫）。 */
    public boolean isImmune(Player player) {
        return getArmorReduction(player) >= 1.0;
    }

    private com.ft.radsurvival.core.PlayerRadState stateOf(Player player) {
        RadiationServices services = plugin.services();
        return services == null ? null : services.radiationTask().stateOf(player.getUniqueId());
    }
}
