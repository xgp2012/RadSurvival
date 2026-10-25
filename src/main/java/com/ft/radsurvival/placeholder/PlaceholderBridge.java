package com.ft.radsurvival.placeholder;

import org.bukkit.plugin.Plugin;

import com.ft.radsurvival.RadSurvivalPlugin;

/**
 * PlaceholderAPI 检测与注册桥（plan.md §5.2 PAPI 行「compileOnly + softdepend，缺失不加载」，M3）。
 *
 * <p><b>类加载隔离</b>（反射-free）：本类自身<b>不引用任何 PAPI 类型</b>——只有当
 * {@code getPlugin("PlaceholderAPI")} 确认存在且启用后，才在 try/catch(Throwable)
 * 内触碰 {@link RadSurvivalExpansion}（其继承 {@code PlaceholderExpansion}）。
 * PAPI 缺失时该类永不被 JVM 加载，插件零影响；PAPI 异常/版本漂移仅记警告日志。</p>
 *
 * <p><b>重载幂等（本轮修复）</b>：{@code /rad reload} 后必须重新注册，否则旧 expansion
 * （持有旧插件引用、版本号停留在旧值）继续伺服 {@code %radsurvival_*%}。旧实现只在
 * onEnable 注册一次且模块内无注销路径，reload 后占位符既不刷新也不释放。
 * 现由桥自身持有当前 expansion 引用：重载时先 {@link #unregister()} 再注册，
 * 同时 {@code onDisable} 也走注销（PAPI 重载场景由 {@code persist()=true} 覆盖）。</p>
 */
public final class PlaceholderBridge {

    /** 当前已注册的 expansion 实例（null = 未注册/PAPI 缺失）。仅主线程读写。 */
    private static volatile RadSurvivalExpansion registered;

    private PlaceholderBridge() {
    }

    /**
     * onEnable / reload 调用：检测到 PlaceholderAPI 则（重）注册 expansion。
     *
     * @return true = 已注册；false = 未检测到 PAPI 或注册失败（均已降级，不影响核心）
     */
    public static boolean registerIfAvailable(RadSurvivalPlugin plugin) {
        Plugin papi = plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI");
        if (papi == null || !papi.isEnabled()) {
            unregister(); // 热卸载/未安装：确保不留旧实例
            return false; // PAPI 缺失：RadSurvivalExpansion 类不会被加载（§5.2 口径）
        }
        try {
            unregister(); // 幂等：旧实例先摘除，避免同 identifier 双份占位符
            RadSurvivalExpansion expansion = new RadSurvivalExpansion(plugin);
            boolean ok = expansion.register();
            if (ok) {
                registered = expansion;
                plugin.getLogger().info("PlaceholderAPI 已检测到：注册 %radsurvival_*% 占位符"
                        + "（exposure/reduction/timemul/corrosion）。");
            } else {
                plugin.getLogger().warning("PlaceholderAPI expansion 注册失败（不影响核心功能）。");
            }
            return ok;
        } catch (Throwable t) { // 版本漂移/类缺失等一律降级
            plugin.getLogger().warning("PlaceholderAPI expansion 注册异常（不影响核心功能）: " + t);
            return false;
        }
    }

    /**
     * 注销当前 expansion（onDisable / reload 前调用；未注册时无副作用）。
     * PAPI 缺失时 {@code registered} 恒 null，本方法不会触碰任何 PAPI 类型。
     */
    public static void unregister() {
        RadSurvivalExpansion current = registered;
        registered = null;
        if (current == null) {
            return;
        }
        try {
            current.unregister();
        } catch (Throwable ignored) {
            // PAPI 热卸载/类缺失：引用已置空即可，不影响核心
        }
    }

    /** 当前是否已注册（排障/测试读口）。 */
    public static boolean isRegistered() {
        return registered != null;
    }
}
