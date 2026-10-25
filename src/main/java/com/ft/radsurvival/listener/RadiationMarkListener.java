package com.ft.radsurvival.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;

import com.ft.radsurvival.api.RadiationExposureEvent;

/**
 * 伤害来源标记监听（plan.md §1.3 伤害标记主线 / §6「自定义 PDC 标记」）。
 *
 * <p>以 <b>LOWEST</b> 优先级监听 {@link RadiationExposureEvent}，在事件自带的瞬态 PDC 上写
 * {@code radsurvival:source=radiation}（key/value 常量见事件类）——其他插件在 LOWEST 之后
 * 任何优先级监听即可识别该伤害来自辐射；主循环对 {@code player.damage(...)} 的调用发生在
 * {@code callEvent} 返回之后，故所有监听者看到的每一次广播都必然已带标记。</p>
 *
 * <p>不监听原版 {@code EntityDamageEvent} 做二次标记：1.21.11 的
 * {@code damage(double, DamageCause)} 重载已移除（jar 核实），且 §1.3 口径标记落点就是
 * <b>自有事件</b>的 PDC，不污染玩家数据；Paper {@code CustomDamageMeta}（@UnstableApi）
 * 开关延后，见 README。</p>
 *
 * <p>本监听器不取消、不改伤——只盖章；改伤/取消是外部插件经事件 API 的权利（§4.8）。</p>
 */
public final class RadiationMarkListener implements Listener {

    /**
     * LOWEST + ignoreCancelled=false：即便事件在此之前已被更低优先级取消（理论不存在），
     * 标记仍先落上，保证「凡广播必可溯源」的不变式。
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onExposure(RadiationExposureEvent event) {
        event.getPersistentDataContainer().set(
                RadiationExposureEvent.SOURCE_PDC_KEY,
                PersistentDataType.STRING,
                RadiationExposureEvent.SOURCE_PDC_VALUE);
    }
}
