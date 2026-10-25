package com.ft.radsurvival.listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.config.PluginConfig;
import com.ft.radsurvival.core.RadiationServices;
import com.ft.radsurvival.feedback.HudRenderer;
import com.ft.radsurvival.item.DustItemFactory;

/**
 * 抗尘埃药的服用结算（v1.2 新增）：降腐蚀 + 短暂免疫 + 解毒 + 冷却。
 *
 * <p><b>为什么用 {@link PlayerItemConsumeEvent} 而不是右键事件</b>：抗尘埃药本身写入
 * {@code DataComponentTypes.CONSUMABLE}，原版会完整播放「饮用」动画、声音、粒子，并在结束时
 * 触发一次 {@code PlayerItemConsumeEvent}——原版此时<b>已经</b>扣掉物品。我们只需在事件里
 * 结算效果，既拿到了原版手感，也不用自己写扣物品/动画逻辑（自己写就要处理副手、堆叠、
 * 装备槽位、物品栏满等一堆边界）。</p>
 *
 * <p><b>冷却是在「消耗之后」的空消耗上拦的</b>：冷却期间玩家仍能做出饮用动作，
 * 但药效不结算、且物品照常被消耗——这不理想，所以另由 {@code DustCraftGui} 之外的
 * {@link #onConsume} 在冷却中<b>取消事件</b>：Bukkit 在取消 {@code PlayerItemConsumeEvent}
 * 时不会扣物品，于是「冷却中喝不掉」自然成立。</p>
 *
 * <p><b>腐蚀下降</b>：走 {@code RadSurvivalApi#addCorrosion} 权威路径
 * （钳 [0,100] + 阶段迁移回调 + PDC 落盘），与命令/外部插件的写入口径完全一致。</p>
 */
public final class AntiDustListener implements Listener {

    /** 玩家 → 上次服药时间戳（毫秒）。quit 清理，避免内存堆积。 */
    private final Map<UUID, Long> lastUseMillis = new ConcurrentHashMap<>();

    private final RadSurvivalPlugin plugin;

    public AntiDustListener(RadSurvivalPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 服用结算。
     *
     * <p>优先级 HIGH 且不 ignoreCancelled：冷却判断需要在其他插件取消之前拿到决定权，
     * 但真正的「取消」只发生在冷却/失效两种情况下，其余一律放行。</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!DustItemFactory.isDust(event.getItem())) {
            return; // 不是抗尘埃药（普通蜂蜜瓶等）：完全不干预
        }
        Player player = event.getPlayer();
        PluginConfig cfg = plugin.config();
        RadiationServices services = plugin.services();
        if (cfg == null || services == null) {
            event.setCancelled(true); // 插件未就绪：不消耗玩家物品
            return;
        }
        // 档位在配置里已不存在（服主删了档位）：取消消耗并说明，绝不白吃
        PluginConfig.DustLevel level = cfg.antiDust().levelById(DustItemFactory.levelIdOf(event.getItem()));
        if (level == null) {
            denyConsume(event, player);
            player.sendActionBar(legacy("§c这瓶药剂的配方已失效（服务器配置已更改）"));
            return;
        }
        // 冷却中：拦下本次消耗
        long remaining = cooldownRemaining(player);
        if (remaining > 0) {
            denyConsume(event, player);
            player.sendActionBar(legacy("§e抗尘埃药冷却中§8 · §7还需 "
                    + String.format(java.util.Locale.ROOT, "%.1f", remaining / 1000.0) + " 秒"));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.4f, 0.6f);
            return;
        }

        // —— ① 降腐蚀（权威路径）
        double before = plugin.api().getCorrosion(player);
        double after = plugin.api().addCorrosion(player, -level.corrosionReduction());
        double actualDrop = Math.max(0.0, before - after);

        // —— ② 免疫窗口
        services.dustImmunity().grant(player.getUniqueId(), level.immuneSeconds());

        // —— ③ 解毒（配置项；未知效果名静默跳过，配置层已做名字校验）
        int cured = 0;
        for (String name : cfg.antiDust().cures()) {
            PotionEffectType type = PotionEffectType.getByName(name);
            if (type != null && player.hasPotionEffect(type)) {
                player.removePotionEffect(type);
                cured++;
            }
        }
        lastUseMillis.put(player.getUniqueId(), System.currentTimeMillis());

        // —— ④ 反馈
        player.sendActionBar(legacy("§a已服下 §f" + level.displayName()
                + " §8· §7腐蚀 −" + fmt(actualDrop) + "% §8→ §f"
                + HudRenderer.corrosionDisplayPercent(plugin.api().getCorrosion(player)) + "%"
                + " §8· §b免疫 " + level.immuneSeconds() + "s"));
        if (cfg.feedback().particles()) {
            player.getWorld().spawnParticle(org.bukkit.Particle.INSTANT_EFFECT,
                    player.getLocation().add(0, 1.0, 0), 14, 0.35, 0.5, 0.35, 0.02);
        }
        if (cured > 0) {
            player.sendMessage(legacy("§8[§a抗尘§8] §7药效清除了 " + cured + " 项中毒状态"));
        }
    }

    /**
     * 拦下一次消耗，且**保证物品不被扣掉**。
     *
     * <p>按 Bukkit 契约，取消 {@link PlayerItemConsumeEvent} 时服务端不会扣物品
     * （{@code PlayerItemConsumeEvent} 的文档原话：*"If the event is cancelled the effect
     * will not be applied and the item will not be removed from the player's inventory"*）。
     * 但这条"不扣"只对**取消**成立、且依赖服务端实现；为了让「冷却中喝不掉也不亏东西」
     * 在任何情况下都成立，这里额外把 {@code replacement} 设成**同一样物品**——
     * 即便将来某个实现先扣后判，也会按 replacement 原样补回同一件物品，
     * 玩家净损失恒为 0。属于低成本的双保险，不改变正常路径行为。</p>
     */
    private static void denyConsume(PlayerItemConsumeEvent event, Player player) {
        ItemStack item = event.getItem();
        if (item != null && item.getType() != Material.AIR) {
            event.setReplacement(item.clone());
        }
        event.setCancelled(true);
    }

    /** quit 清理冷却与免疫窗口（免疫不跨会话，防止「下线喝药上线免伤」）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onQuit(PlayerQuitEvent event) {
        lastUseMillis.remove(event.getPlayer().getUniqueId());
        RadiationServices services = plugin.services();
        if (services != null) {
            services.dustImmunity().clear(event.getPlayer().getUniqueId());
        }
    }

    /** 剩余冷却毫秒（0 = 可用；命令/测试读口）。 */
    public long cooldownRemaining(Player player) {
        PluginConfig cfg = plugin.config();
        if (cfg == null || cfg.antiDust().cooldownSeconds() <= 0) {
            return 0L;
        }
        Long last = lastUseMillis.get(player.getUniqueId());
        if (last == null) {
            return 0L;
        }
        return Math.max(0L, last + cfg.antiDust().cooldownSeconds() * 1000L - System.currentTimeMillis());
    }

    /** 清除全部冷却（reload 后旧冷却不该继续生效）。 */
    public void clearCooldowns() {
        lastUseMillis.clear();
    }

    /** 当前追踪的冷却记录数（测试读口）。 */
    public int tracked() {
        return lastUseMillis.size();
    }

    private static Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text);
    }

    private static String fmt(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
