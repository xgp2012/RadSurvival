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
import com.ft.radsurvival.core.ServerBrand;
import com.ft.radsurvival.feedback.FeedbackService;
import com.ft.radsurvival.item.DustCraftGui;
import com.ft.radsurvival.item.DustItemFactory;
import com.ft.radsurvival.listener.AntiDustListener;
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

    /** v1.2 抗尘埃药物品工厂（随配置重建；未启用/开关关闭时为 null）。 */
    private volatile DustItemFactory dustItems;

    /** v1.2 抗尘埃药合成界面（首次装配后复用同一实例，靠动态取配置跟随 reload）。 */
    private volatile DustCraftGui dustGui;

    /** v1.2 抗尘埃药服用结算监听器（同上）。 */
    private volatile AntiDustListener antiDust;

    /**
     * 伤害通道门控监听（判定链路强化；每次 reload 新建）。
     *
     * <p><b>bug hunt P1-2 修复</b>：本实例此前是 onEnable 的局部变量、只在首次装配时
     * `attachSunRadiationListener`，而 reload 新建的 {@code FeedbackService} 没有拿到引用
     * （字段恒 null）→ 新反馈层的 quit 清理里 `forget(id)` 被空判跳过 → {@code marks}
     * 只增不减。UUID 复用时旧标记可能把别人的 GENERIC 伤害认成太阳伤害。
     * 现在保存为字段，并在 reload 的装配序列里重新绑定。</p>
     */
    private volatile SunRadiationListener sunRadiation;

    /**
     * v1.4：服务端品牌识别结论（plan.md §7）。onEnable 最先写入，供启动日志与
     * {@code /rad status} 展示来源；识别失败或未启用时为 null。
     */
    private volatile ServerBrand.Detection brand;

    @Override
    public void onEnable() {
        // —— plan.md §7 服务端品牌自适应 Profile（v1.4）：识别 + 防御，不做按品牌调参。
        //     必须放在最前面：Spigot/Folia 上的失败是「一坨 NoClassDefFoundError 堆栈」或
        //     「调度器抛 UnsupportedOperationException」，放在后面只会让管理员看不出真正原因。
        if (!guardServerBrand()) {
            return;
        }

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
        this.sunRadiation = sunRadiation;
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

        // —— v1.2 抗尘埃药：合成台（发射器 UI + 木棍右键铁砧）与服用结算
        installAntiDust(newServices);

        PluginConfig cfg = this.config;
        int listenerCount = cfg.antiDust().enabled() ? 7 : 5;
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
        getLogger().info("接线 · 监听器 " + listenerCount + " 个（会话/标记/伤害门控/反馈/死亡文案"
                + (cfg.antiDust().enabled() ? "/抗尘埃药" : "") + "），在线玩家 "
                + getServer().getOnlinePlayers().size() + " 名已预填，命令 /rad help 查看用法。");
        // v1.4 §7：品牌只做展示与兜底提示，不参与任何数值决策（无 profiles 预设）。
        ServerBrand.Detection det = this.brand;
        if (det != null) {
            getLogger().info("平台 · " + det.describe()
                    + (det.brand() == ServerBrand.Brand.UNKNOWN
                            ? "（未实测分支，按 Paper 同面 API 运行；如遇问题请附本行）" : ""));
        }
    }

    /**
     * plan.md §7「服务端品牌自适应 Profile」的<b>防御分支</b>（v1.4）。
     *
     * <p>本期只做「识别 + 防御」，<b>不做按品牌调参</b>：§7.2 的边界表里，唯一随品牌变化的
     * 维度是主线程压力特征，而 M2 后主线程稳态已近零开销（露天补扫走异步池），按品牌去改
     * {@code tick-interval} / 缓存 TTL 等于拿实测数据换猜测。{@code profiles.<brand>} 与三层
     * 优先级因此整体缓建，等确有服务端侧的实测差异再引入。</p>
     *
     * <p><b>为什么必须先于一切 Paper API 调用</b>：在纯 Spigot 上，本类第一个触达
     * {@code DamageSource}/{@code Component} 的语句（例如 {@code PluginConfig} 的日志格式化、
     * 甚至 {@code Component} 相关常量）就会抛 {@code NoClassDefFoundError}，且抛出点往往在
     * 类初始化期间，栈里根本看不出「换服务端」才是解法。此处在最前面用类探测给出确定结论。</p>
     *
     * <p><b>为什么 Folia 不是「WARNING 后继续」</b>：{@code BukkitScheduler} 的全局任务在
     * Folia 上直接抛 {@code UnsupportedOperationException}，而本插件的主循环、HUD 任务、
     * 露天异步补扫全部依赖全局调度——继续加载只会得到「启动成功但完全不工作」的假象，
     * 比拒绝加载更难排查。</p>
     *
     * @return true = 可以继续启用；false = 已调用 disablePlugin，onEnable 应立即返回
     */
    private boolean guardServerBrand() {
        // server.getName() 是纯 Bukkit 方法（Server#getName 自 1.0 起存在），在任何分支上都安全
        String rawName;
        try {
            rawName = getServer().getName();
        } catch (RuntimeException | LinkageError e) {
            rawName = null; // 极个别分支会在此抛；退化为纯类探测
        }
        ServerBrand.Detection detection = ServerBrand.detectCurrent(rawName);
        this.brand = detection;

        if (detection.brand().supported()) {
            return true;
        }
        Logger log = getLogger();
        log.severe("检测到服务端 " + detection.describe() + "，本插件无法在此运行，已自动禁用。");
        log.severe("原因：" + detection.brand().refusalReason() + "。");
        log.severe("请改用 Paper 1.21.11（或 Leaf / Purpur 等 Paper 分支）后重新挂载本插件。");
        getServer().getPluginManager().disablePlugin(this);
        return false;
    }

    /**
     * v1.2 抗尘埃药装配：合成界面（{@link DustCraftGui}）+ 服用结算（{@link AntiDustListener}）。
     *
     * <p>为什么不在 onEnable 里内联写：reload 也要走同一条装配路径（新配置的档位表/冷却
     * 必须换掉旧的监听器实例），抽成方法让「首启」与「reload」共用一份代码，
     * 不会出现「只在首次启动生效」的漂移。</p>
     *
     * <p><b>旧的抗尘埃监听器必须先摘掉</b>：Bukkit 的 HandlerList 不支持按实例注销，
     * 所以旧实例靠自身守卫失效——{@link DustCraftGui} 与 {@link AntiDustListener} 都从
     * {@code plugin.config()} 动态取配置，旧实例在新配置下行为与新实例一致，
     * 唯一副作用是同一事件被处理两次。因此本类的做法是：<b>首次注册后不再重复注册</b>，
     * reload 只替换插件持有的工厂/监听器引用，让注册进来的那一份继续用新配置工作。</p>
     */
    private void installAntiDust(RadiationServices services) {
        PluginConfig cfg = this.config;
        DustItemFactory factory = new DustItemFactory(cfg);
        this.dustItems = factory;
        if (!cfg.antiDust().enabled()) {
            // 关闭时必须把监听器真正摘掉，不能只靠「各自查配置后 return」失效：
            // 那样玩家仍然能把发射器当免费 9 格仓库用（中心格随便放、外围随便堆、
            // 漏斗自由灌入），与「关闭 = 退回原版」的承诺不符。
            unregisterAntiDust();
            this.dustGui = null;
            this.antiDust = null;
            return;
        }
        if (this.dustGui == null) {
            // 首次装配：注册进 HandlerList（此后 reload 复用同一实例，不重复注册）
            this.dustGui = new DustCraftGui(this, factory);
            this.antiDust = new AntiDustListener(this);
            getServer().getPluginManager().registerEvents(this.dustGui, this);
            getServer().getPluginManager().registerEvents(this.antiDust, this);
        } else {
            // reload：同一实例继续服役（两者都动态取 plugin.config()），但要：
            //  ① 清旧冷却（新配置的冷却秒数立即生效）；
            //  ② 把 GUI 持有的 item factory 换成新的——否则 reload 改了档位表之后
            //     「判定用新配置、产出用旧配置」，药水的 Lore/PDC 会与配置不符。
            this.antiDust.clearCooldowns();
            this.dustGui.rebind(factory);
        }
        getLogger().info("抗尘埃药 · " + cfg.antiDust().levels().size() + " 个档位（"
                + cfg.antiDust().levels().stream().map(PluginConfig.DustLevel::id)
                        .collect(java.util.stream.Collectors.joining("/"))
                + "）· 冷却 " + cfg.antiDust().cooldownSeconds() + "s · "
                + "结构 = 铁砧 + 发射器 + 铁块（竖列三格），木棍右键铁砧出品");
    }

    /** 把抗尘埃药的两个监听器从 HandlerList 摘掉（关闭开关时调用，可重复调用）。 */
    private void unregisterAntiDust() {
        if (this.dustGui != null) {
            org.bukkit.event.HandlerList.unregisterAll(this.dustGui);
        }
        if (this.antiDust != null) {
            org.bukkit.event.HandlerList.unregisterAll(this.antiDust);
            this.antiDust.clearCooldowns();
        }
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

    /** v1.2 抗尘埃药物品工厂（构建/识别药水；开关关闭或未启用时为 null）。 */
    public DustItemFactory dustItems() {
        return dustItems;
    }

    /** v1.2 抗尘埃药合成界面监听器（/rad 命令复用其合成入口；开关关闭为 null）。 */
    public DustCraftGui dustGui() {
        return dustGui;
    }

    /** v1.2 抗尘埃药服用结算监听器（命令读冷却用；开关关闭为 null）。 */
    public AntiDustListener antiDustListener() {
        return antiDust;
    }

    /**
     * 伤害通道门控监听（判定链路强化；reload 后指向新实例，旧实例自动失效）。
     *
     * <p>事件处理以 {@code plugin.sunRadiation() == this} 作陈旧实例守卫——与
     * {@link #feedback()} 同一套机制，避免 reload 后旧实例继续往自己的 marks 表里记脏数据。</p>
     */
    public SunRadiationListener sunRadiation() {
        return sunRadiation;
    }

    /**
     * v1.4：服务端品牌识别结论（plan.md §7）。
     *
     * <p>reload 不重新识别——品牌在一次 JVM 生命周期内不会变化，重新探测只会让
     * {@code /rad status} 的来源说明在两次调用间漂移。仅在 onEnable 写入一次。</p>
     *
     * @return 识别结论；未启用/识别前为 null
     */
    public ServerBrand.Detection brand() {
        return brand;
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

        // bug hunt P1-2：门控监听在 reload 时新建（旧实例的 SUN 标记表是脏数据，
        // 且它注册在 HandlerList 里无法按实例注销——旧实例靠 plugin.sunRadiation() != this
        // 守卫失效，与 FeedbackService 同一套陈旧实例机制）。新实例必须注册进 Bukkit，
        // 同时必须绑定给新反馈层，否则 quit 时不再清理 SUN 标记（marks 无界增长）。
        SunRadiationListener sunRadiation = new SunRadiationListener(this);
        this.sunRadiation = sunRadiation;
        getServer().getPluginManager().registerEvents(sunRadiation, this);
        newFeedback.attachSunRadiationListener(sunRadiation);

        // 新实例的 Bukkit 事件处理需再注册（旧实例残留的处理器由 plugin.feedback()!=this 守卫失效）
        getServer().getPluginManager().registerEvents(newFeedback, this);
        newFeedback.start();

        // v1.2：抗尘埃药物品工厂换新配置（监听器实例复用，档位表/冷却经 config() 动态生效）
        installAntiDust(fresh);

        // 占位符重注册（判定链路强化修复）：旧 expansion 持有旧插件引用且 persist()=true，
        // 不重注册会让 %radsurvival_*% 在 reload 后继续按旧装配/旧版本号应答。
        PlaceholderBridge.registerIfAvailable(this);

        getLogger().info("config.yml reload 成功 · " + fresh.describeFlags()
                + " · 反馈层 HUD=" + (this.config.feedback().actionbar() ? "on" : "off")
                + "/bossbar=" + (this.config.feedback().bossbarCorrosion() ? "on" : "off")
                + "/protocol-lib=" + this.config.feedback().protocolLib()
                + " · 抗尘埃药=" + (this.config.antiDust().enabled()
                        ? this.config.antiDust().levels().size() + " 档" : "off"));
        return List.of();
    }
}
