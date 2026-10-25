package com.ft.radsurvival.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.core.CorrosionMeter;
import com.ft.radsurvival.core.RadiationServices;
import com.ft.radsurvival.core.RadiationTask;

/**
 * 玩家会话生命周期监听（plan.md §2 listener/PlayerSessionListener.java、§1.6 持久化、§2 玩家状态）。
 *
 * <p>口径：</p>
 * <ul>
 *   <li><b>join</b>：{@link RadiationTask#registerPlayer} —— 从 Player PDC
 *       {@code radsurvival:corrosion} 读回腐蚀值初始化内存态（缺省 0，§1.6）并建状态条目；</li>
 *   <li><b>quit</b>：先 {@link CorrosionMeter#saveToPlayer} 写回 PDC，再 forget 内存值、
 *       移除状态条目 → 离线期间腐蚀冻结（重新上线从原值继续，§1.6）；</li>
 *   <li><b>death</b>：{@link CorrosionMeter#onDeath}（reset-on-death 清零 或 decay-on-death
 *       按比例保留，二选一已由 M1 校验兜底）并<b>立即</b>写回 PDC（死亡后紧接着退服的
 *       场景也不能丢处理结果）；</li>
 *   <li><b>world change</b>：无需处理——下一轮主循环自然按新世界/新维度走
 *       过滤链与恢复分支（M2b 拍板）。</li>
 * </ul>
 *
 * <p>监听器无状态，只经 {@code plugin.services()} 动态取当前装配（reload 重建后自动指向新实例）。</p>
 */
public final class PlayerSessionListener implements Listener {

    private final RadSurvivalPlugin plugin;

    public PlayerSessionListener(RadSurvivalPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 会话登记（主线程）。
     *
     * <p><b>刻意不监听 {@code PlayerLoginEvent}</b>：Paper 会在启动日志里告警
     * 「[HorriblePlayerLoginEventHack] You have plugins listening to the PlayerLoginEvent,
     * this will cause re-configuration APIs to be unavailable」——任何插件监听该事件
     * 都会让服务端的 {@code /reload}-less re-configuration（客户端不重连即可重进配置阶段）
     * 能力失效；本插件对登录阶段没有任何业务需求，因此只在 {@code PlayerJoinEvent}
     * 这一主线程事件上做登记，避免无谓地削减服务端能力。</p>
     *
     * <p>若将来确实需要在异步登录阶段读写状态，唯一安全的做法是只在主线程触达玩家实体
     * （Player PDC / 背包 / 属性），异步侧只做不可变数据的准备——但在此之前不要新增
     * {@code PlayerLoginEvent} 监听。</p>
     */
    @EventHandler(ignoreCancelled = false)
    public void onJoin(PlayerJoinEvent event) {
        RadiationServices services = plugin.services();
        if (services == null) {
            return; // 插件未启用/校验失败：无服务可接线
        }
        services.radiationTask().registerPlayer(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = false)
    public void onQuit(PlayerQuitEvent event) {
        RadiationServices services = plugin.services();
        if (services == null) {
            return;
        }
        Player player = event.getPlayer();
        CorrosionMeter meter = services.corrosionMeter();
        meter.saveToPlayer(player);      // ① 写回 PDC（player.dat 随退出落盘）
        meter.forget(player.getUniqueId()); // ② 清内存权威值与阶段基线（firstWarnSeen 保留，M2a 口径）
        services.radiationTask().unregisterPlayer(player.getUniqueId()); // ③ 移除状态条目（离线冻结）
    }

    @EventHandler(ignoreCancelled = false)
    public void onDeath(PlayerDeathEvent event) {
        RadiationServices services = plugin.services();
        if (services == null) {
            return;
        }
        Player player = event.getPlayer();
        CorrosionMeter meter = services.corrosionMeter();
        meter.onDeath(player.getUniqueId()); // §1.6 reset/decay 二选一
        meter.saveToPlayer(player);          // 立即持久化
        var state = services.radiationTask().stateOf(player.getUniqueId());
        if (state != null) {
            state.setCorrosion(meter.get(player.getUniqueId())); // 镜像刷新
            state.setLastDamage(0.0);
        }
    }
}
