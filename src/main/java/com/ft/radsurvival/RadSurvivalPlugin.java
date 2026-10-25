package com.ft.radsurvival;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.ft.radsurvival.api.RadSurvivalApi;
import com.ft.radsurvival.command.RadCommand;
import com.ft.radsurvival.config.PluginConfig;
import com.ft.radsurvival.core.DamageApplier;
import com.ft.radsurvival.core.RadiationServices;
import com.ft.radsurvival.feedback.FeedbackService;
import com.ft.radsurvival.listener.DamageTagListener;
import com.ft.radsurvival.listener.PlayerSessionListener;
import com.ft.radsurvival.listener.RadiationMarkListener;
import com.ft.radsurvival.listener.SunRadiationListener;
import com.ft.radsurvival.placeholder.PlaceholderBridge;

/**
 * 插件入口（plan.md §2：onEnable/onDisable、服务装配、命令注册）。
 *
 * <p>Bukkit 侧只做薄胶水：config.yml 的加载与全部校验规则放在纯 Java 的
 * {@link PluginConfig}，判定/结算内核收敛在 core 包（M2a 纯内核 + M2b 接线）。</p>
 *
 * <p><b>M2b 装配</b>：onEnable 创建 {@link RadiationServices} 服务容器（昼夜/盔甲/露天/腐蚀/
 * 结算器/主循环六单例，注入 PluginConfig），注册 {@link PlayerSessionListener}（会话生命周期）
 * 与 {@link RadiationMarkListener}（事件 PDC 伤害来源标记，LOWEST），并以
 * {@code config.tick-interval}（≥20，无敌帧下限由 M1 校验保证）启动全局重复任务。
 * {@code /rad reload} 成功后整体重建服务容器（旧任务先取消、在线玩家腐蚀先冲刷到 PDC，
 * 新容器再按 join 口径从 PDC 恢复 → 权威值无缝续接）。监听器与命令一律动态取
 * {@code plugin.services()}，不做长期引用缓存。</p>
 *
 * <p><b>M3 装配</b>：核心服务之上追加反馈层——{@link FeedbackService}（actionbar 双条
 * 独立 1s 任务、{@code CorrosionMeter} 阶段回调 → {@code CorrosionStageChangeEvent} 事件桥
 * 与播报消费、DamageApplier 受击挂点、可选 BossBar/ProtocolLib 红闪抑制）与
 * {@link DamageTagListener}（死亡文案，LOWEST 先于腐蚀清零取死亡瞬间值）注册进
 * {@code onEnable}；{@link PlaceholderBridge} 检测 PlaceholderAPI 才注册 %radsurvival_*%
 * 占位符。{@code /rad reload} 时旧反馈层先 shutdown（取消任务/移除全部玩家 BossBar/
 * 清 actionbar）再整体重建；{@code onDisable} 先停反馈层再停核心。</p>
 *
 * <p>对外门面：{@link #api()}（{@link RadSurvivalApi}，M3 反馈层与外部插件接线点）。</p>
 *
 * <p>注：类不加 final——MockBukkit 4.x 以 byte-buddy 子类化插件实例做装载钩子，
 * final 会被拒（实测报错 Cannot subclass ... final types）；Paper 加载不受影响。</p>
 */
public class RadSurvivalPlugin extends JavaPlugin {

    /** 当前生效的类型安全配置；null = 启动校验失败 / 已停用。 */
    private volatile PluginConfig config;

    /** 当前 core 服务容器；null = 未启用/已停用（reload 时原子替换）。 */
    private volatile RadiationServices services;

    /**
     * M3 反馈层（HUD 独立 1s 任务 / 阶段播报桥 / BossBar / 受击反馈挂点）；
     * reload 时随服务容器一起整体重建（旧实例先 shutdown：取消任务、清 BossBar/actionbar）。
     */
    private volatile FeedbackService feedback;

    /** 对外 API 门面（轻量包装，随取随用 services()）。 */
    private final RadSurvivalApi api = new RadSurvivalApi(this);

    @Override
    public void onEnable() {
        // 首次运行：把 jar 内默认 config.yml 释放到插件数据文件夹
        saveDefaultConfig();

        PluginConfig.LoadResult result = PluginConfig.loadFromFile(configFile());
        if (result.hasErrors()) {
            // 非法配置：记录 SEVERE 错误明细并 disablePlugin，拒服务（plan.md §3/M1 要求）
            Logger log = getLogger();
            log.severe("config.yml 校验失败，拒绝启动（禁用插件，不提供辐射服务）：");
            result.errors().forEach(error -> log.severe("  - " + error));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.config = result.config();

        PluginCommand rad = getCommand("rad");
        if (rad == null) {
            getLogger().severe("plugin.yml 中未找到 rad 命令定义，禁用插件。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        RadCommand handler = new RadCommand(this);
        rad.setExecutor(handler);
        rad.setTabCompleter(handler);

        // —— M2b 服务装配：core 六单例 → 监听器 → 在线玩家状态预填 → 主循环任务
        RadiationServices newServices = new RadiationServices(this, this.config);
        this.services = newServices;
        getServer().getPluginManager().registerEvents(new PlayerSessionListener(this), this);
        getServer().getPluginManager().registerEvents(new RadiationMarkListener(), this);
        // —— 判定链路强化：伤害通道门控（外部伤害还原原版防御，仅 SUN 通道走自定义模型）
        SunRadiationListener sunRadiation = new SunRadiationListener(this);
        getServer().getPluginManager().registerEvents(sunRadiation, this);
        for (Player player : getServer().getOnlinePlayers()) {
            newServices.radiationTask().registerPlayer(player); // 覆盖「服务重建时已在服」玩家
        }
        newServices.start();

        // —— M3 反馈层装配（§1.5/§1.6）：HUD 独立 1s 任务 + 阶段事件桥 + 受击挂点 +
        //    阶段播报/死亡文案监听 +（可选）ProtocolLib 红闪抑制 +（可选）PAPI 占位符
        FeedbackService newFeedback = new FeedbackService(this);
        this.feedback = newFeedback;
        newFeedback.attachSunRadiationListener(sunRadiation);
        getServer().getPluginManager().registerEvents(newFeedback, this);
        getServer().getPluginManager().registerEvents(new DamageTagListener(this), this);
        newFeedback.start();
        PlaceholderBridge.registerIfAvailable(this);

        PluginConfig cfg = this.config;
        // 启动日志：3 行紧凑输出（装配 / 口径 / 增强），与 /rad status 控制台摘要同源（describeFlags）。
        // 旧版把维度/粒子/协议等键平铺成两行超长段落，管理员实际只关心「是否生效 + 关键旋钮」。
        getLogger().info("已启用 · " + newServices.describeFlags());
        getLogger().info("反馈层 · HUD=" + (cfg.feedback().actionbar() ? "actionbar" : "off")
                + (cfg.feedback().bossbarCorrosion() ? "+bossbar" : "")
                + " 粒子=" + (newFeedback.hitParticle() == null ? "off" : newFeedback.hitParticle().name())
                + " 音效=" + (cfg.feedback().sound() ? "on" : "off")
                + " 死亡文案=" + (cfg.feedback().deathMessage() ? "on" : "off")
                + " 红闪抑制=" + (newFeedback.enhancer() == null
                        ? "off（纯 Paper 原生）" : newFeedback.enhancer().label()));
        getLogger().info("接线 · 监听器 5 个（会话/标记/伤害门控/反馈/死亡文案），在线玩家 "
                + getServer().getOnlinePlayers().size() + " 名已预填，命令 /rad help 查看用法。");
    }

    @Override
    public void onDisable() {
        // 占位符先注销（PAPI 侧不得残留持有旧插件引用的 expansion）
        PlaceholderBridge.unregister();
        // M3：先停反馈层（取消 HUD 任务、移除全部玩家 BossBar、清 actionbar），再停核心
        FeedbackService currentFeedback = this.feedback;
        if (currentFeedback != null) {
            currentFeedback.shutdown(); // 幂等
        }
        this.feedback = null;
        // 判定链路强化兜底：异常路径下仍处于「摘下」状态的盔甲放回玩家身上
        DamageApplier.restoreSuspendedArmorAll();
        // 冲刷在线玩家腐蚀到 PDC、取消任务、清状态与露天缓存（§1.6 持久化 / §2 状态生命周期）
        RadiationServices current = this.services;
        if (current != null) {
            current.shutdown();
            this.services = null;
        }
        this.config = null;
        getLogger().info("RadSurvival 已停用（反馈层任务/条已清理，核心任务已取消，在线玩家腐蚀已冲刷到 PDC，缓存已清空）。");
    }

    // ------------------------------------------------------------------ 访问器

    /** 类型安全配置访问器；启动校验失败或 onDisable 后为 null。 */
    public PluginConfig config() {
        return config;
    }

    /** 当前 core 服务容器（监听器/命令动态取用；未启用时为 null）。 */
    public RadiationServices services() {
        return services;
    }

    /** 主循环任务（便捷入口；未启用时为 null）。 */
    public com.ft.radsurvival.core.RadiationTask radiationTask() {
        RadiationServices current = services;
        return current == null ? null : current.radiationTask();
    }

    /**
     * M3 反馈层（HUD 任务/阶段播报桥/受击挂点/死亡归因记录；未启用或已停用为 null）。
     * 事件处理一律以 {@code plugin.feedback() == this} 作陈旧实例守卫。
     */
    public FeedbackService feedback() {
        return feedback;
    }

    /** 对外 API 门面（M3/外部插件接线点；插件未启用时各方法安全降级）。 */
    public RadSurvivalApi api() {
        return api;
    }

    /**
     * 服务容器替换钩子（MockBukkit 集成测试注入假露天解析器/假伤害执行器的装配入口，
     * §5.2 口径；生产代码不调用）。旧容器只取消任务，不负责冲刷——调用方自理。
     */
    public void replaceServices(RadiationServices next) {
        RadiationServices old = this.services;
        if (old != null) {
            old.radiationTask().cancel();
        }
        this.services = next;
    }

    /** 受管配置文件路径（由 Configurate 直接读取，不走 Bukkit YamlConfiguration）。 */
    public Path configFile() {
        return getDataFolder().toPath().resolve("config.yml");
    }

    /**
     * /rad reload 后端：从磁盘重读并校验 config.yml；成功后<b>整体重建服务容器</b>。
     *
     * <p>重建序列：旧任务取消 → 旧容器把在线玩家腐蚀冲刷到 PDC → 清状态/缓存 →
     * 新容器装配 → 在线玩家逐个 registerPlayer（从 PDC 恢复腐蚀 + 重建状态）→ 新任务启动。
     * 非法 reload 保留旧配置与旧容器（M1 语义不变）。</p>
     *
     * @return 错误明细列表；空列表 = 成功并已原子替换配置与服务。
     */
    public List<String> reloadConfigFromDisk() {
        PluginConfig.LoadResult result = PluginConfig.loadFromFile(configFile());
        if (result.hasErrors()) {
            getLogger().warning("config.yml reload 失败（已保留旧配置）: " + String.join(" | ", result.errors()));
            return result.errors();
        }
        this.config = result.config();

        // —— M3：先拆旧反馈层（取消 HUD 任务、移除全部玩家 BossBar、清 actionbar——
        //    reload 不泄漏的口径；旧实例残留在 HandlerList 的事件处理由
        //    {@code plugin.feedback() != this} 守卫失效，监听注册本身不重复）
        FeedbackService oldFeedback = this.feedback;
        this.feedback = null;
        if (oldFeedback != null) {
            oldFeedback.shutdown();
        }

        RadiationServices old = this.services;
        if (old != null) {
            old.shutdown(); // 含冲刷在线玩家腐蚀到 PDC（新容器从 PDC 恢复，权威值不丢）
        }
        RadiationServices fresh = new RadiationServices(this, this.config);
        this.services = fresh;
        for (Player player : getServer().getOnlinePlayers()) {
            fresh.radiationTask().registerPlayer(player);
        }
        fresh.start();

        // 重建反馈层：新 config 开关 + 新容器阶段回调桥 + 新 DamageApplier 挂点
        FeedbackService newFeedback = new FeedbackService(this);
        this.feedback = newFeedback;
        // 新实例的 Bukkit 事件处理需再注册（旧实例残留的处理器由 plugin.feedback()!=this 守卫失效）
        getServer().getPluginManager().registerEvents(newFeedback, this);
        newFeedback.start();

        // 占位符重注册（判定链路强化修复）：旧 expansion 持有旧插件引用且 persist()=true，
        // 不重注册会让 %radsurvival_*% 在 reload 后继续按旧装配/旧版本号应答。
        PlaceholderBridge.registerIfAvailable(this);

        getLogger().info("config.yml reload 成功 · " + fresh.describeFlags()
                + " · 反馈层 HUD=" + (this.config.feedback().actionbar() ? "on" : "off")
                + "/bossbar=" + (this.config.feedback().bossbarCorrosion() ? "on" : "off")
                + "/protocol-lib=" + this.config.feedback().protocolLib());
        return List.of();
    }
}
