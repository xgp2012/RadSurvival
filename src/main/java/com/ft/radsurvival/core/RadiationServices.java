package com.ft.radsurvival.core;

import org.bukkit.entity.Player;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.config.PluginConfig;

/**
 * core 服务装配容器（plan.md §2「服务装配」，M2b）。
 *
 * <p>把 {@link PluginConfig} 快照注入的六个单例（昼夜/盔甲/露天/腐蚀/结算器/主循环）
 * 收拢为一个不可变持有者：{@code RadSurvivalPlugin} 在 onEnable 与 {@code /rad reload}
 * 成功后整体重建（reload 时旧实例的任务先取消、在线玩家腐蚀先冲刷到 Player PDC，
 * 新实例经 join 语义的 {@code loadFromPlayer} 从 PDC 恢复 → 权威值不丢，§1.6 持久化口径）。
 * 监听器与命令一律通过 {@code plugin.services()} 动态取当前实例，不做长期引用缓存。</p>
 */
public final class RadiationServices {

    private final PluginConfig config;
    private final DayNightProvider dayNightProvider;
    private final ArmorCalculator armorCalculator;
    private final SunExposureChecker sunExposureChecker;
    private final CorrosionMeter corrosionMeter;
    private final DamageApplier damageApplier;
    private final DustImmunity dustImmunity;
    private final RadiationTask radiationTask;

    public RadiationServices(RadSurvivalPlugin plugin, PluginConfig config) {
        this(plugin, config, null, null);
    }

    /**
     * 测试装配：覆写露天解析器/伤害执行器（生产路径传 null 用默认实现）。
     * 除这两个观测量注入点外，过滤链、腐蚀步进、免疫短路、事件广播、命令与监听装配
     * 全部与生产一致（见 {@link RadiationTask.ExposureResolver}、
     * {@link DamageApplier.DamageExecutor} 的 javadoc——均为 MockBukkit 未实现面所迫，
     * plan.md §5.2 允许测试降级口径）。
     */
    public RadiationServices(RadSurvivalPlugin plugin, PluginConfig config,
                             RadiationTask.ExposureResolver exposureResolverOverride,
                             DamageApplier.DamageExecutor damageExecutorOverride) {
        this.config = config;
        this.dayNightProvider = new DayNightProvider(config);
        this.armorCalculator = new ArmorCalculator(config);
        // §2 线程模型异步化：async-exposure-scan=true（默认）→ 段缓存过期后的露天补扫
        // 走 Bukkit 异步池（只读已加载区块），主线程当轮用粘滞值；false → 全同步旧口径。
        // 伤害/事件/HUD/PDC 等全部主线程操作不受影响（RadiationTask/DamageApplier 未变）。
        this.sunExposureChecker = config.asyncExposureScan()
                ? new SunExposureChecker(config,
                        task -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task))
                : new SunExposureChecker(config);
        this.corrosionMeter = new CorrosionMeter(config.corrosion());
        this.damageApplier = damageExecutorOverride == null
                ? new DamageApplier(plugin)
                : new DamageApplier(plugin.getServer().getPluginManager(), damageExecutorOverride);
        // v1.2：抗尘埃药免疫窗口随服务容器同生命周期（reload 重建 = 窗口清零，
        // 避免旧配置下的长时长泄漏到新配置；玩家重新服药即可）
        this.dustImmunity = new DustImmunity();
        this.radiationTask = exposureResolverOverride == null
                ? new RadiationTask(plugin, config, sunExposureChecker, defaultResolverEnabled(sunExposureChecker),
                        dayNightProvider, armorCalculator, corrosionMeter, damageApplier, dustImmunity)
                : new RadiationTask(plugin, config, sunExposureChecker, exposureResolverOverride,
                        dayNightProvider, armorCalculator, corrosionMeter, damageApplier, dustImmunity);
    }

    /** 生产默认露天解析器（与 {@link RadiationTask#defaultResolver} 同口径，抽成方法便于两个构造共用）。 */
    private static RadiationTask.ExposureResolver defaultResolverEnabled(SunExposureChecker checker) {
        return RadiationTask.defaultResolver(checker);
    }

    /** 启动主循环任务（监听器注册、状态表预填完成后调用）。 */
    public void start() {
        radiationTask.start();
    }

    /**
     * 关停：取消任务 → 在线玩家腐蚀冲刷到 PDC（§1.6「重进服/重启不丢」）→
     * 清玩家状态表与露天缓存。幂等，供 onDisable 与 reload 重建前复用。
     */
    public void shutdown() {
        radiationTask.cancel();
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            corrosionMeter.saveToPlayer(player);
        }
        radiationTask.clearStates();
        sunExposureChecker.invalidateAll();
        dustImmunity.clearAll(); // v1.2：免疫窗口不跨容器保留
    }

    /** 冲刷在线玩家腐蚀到 PDC（不清状态，供 onDisable 分段调用/测试）。 */
    public void flushCorrosionToPdc() {
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            corrosionMeter.saveToPlayer(player);
        }
    }

    public PluginConfig config() {
        return config;
    }

    /**
     * 单行紧凑摘要（启动日志与 /rad status 控制台摘要共用同一口径，避免两处各写一份
     * 导致「日志说 enabled-worlds=[world]，status 说别的」这类漂移）。
     *
     * <p>格式：{@code tick=20 base=1 night×0.5 worlds=[world] dim=NORMAL 腐蚀=on max×2 async=on}</p>
     */
    public String describeFlags() {
        PluginConfig.CorrosionConfig cor = config.corrosion();
        return "tick=" + config.tickInterval()
                + " base=" + trim(config.baseDamage())
                + " night×" + trim(config.nightMultiplier())
                + " worlds=" + config.enabledWorlds()
                + " dim=" + config.dimensions()
                + " 腐蚀=" + (cor.enabled() ? "on" : "off") + " max×" + trim(cor.maxMultiplier())
                + " async=" + (config.asyncExposureScan() ? "on" : "off")
                // v1.2：护甲最强满配减免（永远 < 100%，100% 免疫已移除）
                + " armor≤" + trim(config.armor().maxFullSetReduction() * 100) + "%";
    }

    private static String trim(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    public DayNightProvider dayNightProvider() {
        return dayNightProvider;
    }

    public ArmorCalculator armorCalculator() {
        return armorCalculator;
    }

    public SunExposureChecker sunExposureChecker() {
        return sunExposureChecker;
    }

    public CorrosionMeter corrosionMeter() {
        return corrosionMeter;
    }

    public DamageApplier damageApplier() {
        return damageApplier;
    }

    public RadiationTask radiationTask() {
        return radiationTask;
    }

    /** v1.2：抗尘埃药免疫窗口（结算链 + 命令/API 读口）。 */
    public DustImmunity dustImmunity() {
        return dustImmunity;
    }
}
