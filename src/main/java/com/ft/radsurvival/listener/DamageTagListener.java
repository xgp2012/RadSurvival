package com.ft.radsurvival.listener;

import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.config.PluginConfig;
import com.ft.radsurvival.core.RadiationServices;
import com.ft.radsurvival.feedback.FeedbackService;
import com.ft.radsurvival.feedback.HudRenderer;

/**
 * 死亡文案定制监听（plan.md §2 listener/DamageTagListener.java「死亡日志文案定制」，M3；
 * 开关 {@code feedback.death-message}）。
 *
 * <p><b>归因口径（可靠 + 不误伤其他死因，双判据）</b>：
 * 玩家死于辐射结算 ⇔ ①{@link FeedbackService} 的最近一次<b>辐射真实掉血记录</b>
 * （{@code DamageApplier} 实际扣血后由 M3 挂点无条件写入，反馈开关全关也在）落在
 * <b>结算时间窗</b>内（≤ tick-interval×50ms + 500ms 宽限——死亡必然发生在击杀轮的
 * damage() 调用同步栈/紧随其后，超窗即非结算击杀）；且 ②死亡事件的
 * {@link DamageSource} 为无实体归因的 {@link DamageType#GENERIC}（§1.3 伤害主线
 * 1.21.11 口径：{@code DamageSource.builder(DamageType.GENERIC)}）——被生物/弹射物/
 * 其他伤害类型击杀一律不改文案。DamageSource 缺失（异常/极端实现）时仅凭①判定
 * （窗口极短，误伤面可控）。</p>
 *
 * <p><b>优先级 LOWEST</b>：必须在 {@link PlayerSessionListener#onDeath}（NORMAL）执行
 * reset-on-death 清零<b>之前</b>读取死亡瞬间的腐蚀值（§1.6 持久化链路口径）。
 * 监听器无状态、配置与反馈层一律动态取 {@code plugin.*()}，reload 后自动指向新装配；
 * 全程 try/catch——文案层异常绝不影响死亡主流程。</p>
 */
public final class DamageTagListener implements Listener {

    /** 一次辐射真实掉血记录（appliedAtMillis = System.currentTimeMillis 口径）。 */
    public record RadiationHit(long appliedAtMillis, double amount) {
    }

    /** 结算时间窗宽限（毫秒），与 FeedbackService.radiationWindowMillis 共用口径。 */
    public static final long HIT_WINDOW_SLICE_MS = 500L;

    private final RadSurvivalPlugin plugin;

    public DamageTagListener(RadSurvivalPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDeath(PlayerDeathEvent event) {
        try {
            PluginConfig cfg = plugin.config();
            if (cfg == null || !cfg.feedback().deathMessage()) {
                return;
            }
            FeedbackService feedback = plugin.feedback();
            if (feedback == null) {
                return;
            }
            Player player = event.getPlayer();
            RadiationHit hit = feedback.lastRadiationHit(player.getUniqueId());
            if (!looksLikeRadiationKill(hit, safeDamageSource(event),
                    System.currentTimeMillis(), feedback.radiationWindowMillis())) {
                return;
            }
            RadiationServices services = plugin.services();
            double corrosion = services == null ? 0.0
                    : services.corrosionMeter().get(player.getUniqueId());
            event.setDeathMessage(radiationDeathMessage(player.getName(), corrosion));
        } catch (RuntimeException | Error ex) {
            plugin.getLogger().warning("死亡文案定制异常（已忽略，不影响死亡流程）: " + ex);
        }
    }

    // ---------------------------------------------------------------- 纯判定（可单测）

    /**
     * 双判据归因（纯函数，单测钉死）：
     * hit==null 或超窗 → false；source 可见时要求 GENERIC 且无 causing/direct 实体；
     * source==null（实现缺失/异常）时仅凭时间窗。
     */
    public static boolean looksLikeRadiationKill(RadiationHit hit, DamageSource source,
                                                 long nowMillis, long windowMillis) {
        if (hit == null) {
            return false;
        }
        long ageMillis = nowMillis - hit.appliedAtMillis();
        if (ageMillis < 0 || ageMillis > windowMillis) {
            return false; // 击杀轮必须刚完成一次辐射结算（超窗 = 其他死因，不误伤）
        }
        if (source != null) {
            if (source.getDamageType() != DamageType.GENERIC) {
                return false; // 明确的其他伤害类型（§1.3 主线为 GENERIC）
            }
            if (source.getCausingEntity() != null || source.getDirectEntity() != null) {
                return false; // 有实体归因（怪/玩家/弹射物补刀）→ 不是纯辐射结算击杀
            }
        }
        return true;
    }

    /** 废土风死亡文案（含腐蚀值，§1.5/M3；腐蚀取显示口径整数）。 */
    public static String radiationDeathMessage(String playerName, double corrosion) {
        int pct = HudRenderer.corrosionDisplayPercent(corrosion);
        String flavor = switch (pct) {
            case 0 -> "在烈日下曝干了最后一丝水分";
            default -> pct < 50
                    ? "被辐射尘吞没，皮肤泛起不健康的灰斑"
                    : "在辐射尘中崩解，化作一缕荧绿的烟";
        };
        if (pct >= 50) {
            flavor += "（腐蚀 " + pct + "%）";
        } else {
            flavor += "（辐射腐蚀 " + pct + "%）";
        }
        return "§8[§c辐照§8] §f" + playerName + " " + flavor;
    }

    private static DamageSource safeDamageSource(PlayerDeathEvent event) {
        try {
            return event.getDamageSource();
        } catch (RuntimeException | Error ex) {
            return null;
        }
    }
}
