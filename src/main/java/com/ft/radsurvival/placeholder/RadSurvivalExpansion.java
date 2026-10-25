package com.ft.radsurvival.placeholder;

import java.util.Locale;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import com.ft.radsurvival.RadSurvivalPlugin;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

/**
 * PlaceholderAPI expansion（plan.md §5.2 PAPI 行、§4.7「占位符本期即建好」，M3）。
 *
 * <p>四个只读占位符（数据一律经 {@link com.ft.radsurvival.api.RadSurvivalApi} 门面/
 * 状态镜像读取，§6 收敛；PAPI 侧调用发生在主线程）：</p>
 * <ul>
 *   <li>{@code %radsurvival_exposure%} —— 露天判定 0/1（§1.1）；</li>
 *   <li>{@code %radsurvival_reduction%} —— 盔甲减免百分比，两位小数（§1.4，如 40.00）；</li>
 *   <li>{@code %radsurvival_timemul%} —— 当前昼夜伤害因子（§1.2，两位小数，如 0.50）；</li>
 *   <li>{@code %radsurvival_corrosion%} —— 腐蚀值整数 [0,100]（§1.6 显示取整口径）；</li>
 *   <li>{@code %radsurvival_dust%} —— 抗尘埃药免疫剩余秒数（v1.2；未在窗口内为 0）。</li>
 * </ul>
 *
 * <p><b>隔离</b>：{@code compileOnly} 依赖，本类只在 {@link PlaceholderBridge} 检测到
 * PlaceholderAPI 已启用时才被构造/加载——PAPI 缺失时该类永不被加载，不报错、零影响（§5.2）。</p>
 */
public final class RadSurvivalExpansion extends PlaceholderExpansion {

    private final RadSurvivalPlugin plugin;

    public RadSurvivalExpansion(RadSurvivalPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "radsurvival";
    }

    @Override
    public String getAuthor() {
        return "FT";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // PAPI 重载后仍保留（reload 由 bridge 重注册覆盖，幂等）
    }

    /**
     * 解析入口（PAPI 标准形态）：params 为 {@code radsurvival_<name>} 去前缀段。
     * 未知参数返回 null（PAPI 惯例 = 占位符不响应）。
     */
    @Override
    public String onRequest(OfflinePlayer offlinePlayer, String params) {
        if (offlinePlayer == null || !offlinePlayer.isOnline()) {
            return ""; // 占位符仅对在线玩家有意义（状态镜像/腐蚀均为在线口径）
        }
        Player player = offlinePlayer.getPlayer();
        if (player == null) {
            return "";
        }
        com.ft.radsurvival.api.RadSurvivalApi api = plugin.api();
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "exposure" -> api.isExposed(player) ? "1" : "0";
            case "reduction" -> String.format(Locale.ROOT, "%.2f", api.getArmorReduction(player) * 100.0);
            case "timemul" -> String.format(Locale.ROOT, "%.2f", api.getTimeFactor(player));
            case "corrosion" -> String.valueOf(
                    com.ft.radsurvival.feedback.HudRenderer
                            .corrosionDisplayPercent(api.getCorrosion(player)));
            // v1.2 抗尘埃药免疫窗口：给计分板/计时器插件用（未免疫时为 0）
            case "dust" -> String.valueOf(api.getImmunityRemainingSeconds(player));
            default -> null;
        };
    }
}
