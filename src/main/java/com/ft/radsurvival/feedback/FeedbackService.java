package com.ft.radsurvival.feedback;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.api.CorrosionStageChangeEvent;
import com.ft.radsurvival.config.PluginConfig;
import com.ft.radsurvival.core.CorrosionMeter;
import com.ft.radsurvival.core.DamageApplier;
import com.ft.radsurvival.core.PlayerRadState;
import com.ft.radsurvival.core.RadiationServices;
import com.ft.radsurvival.core.RadiationTask;
import com.ft.radsurvival.listener.DamageTagListener;

/**
 * 反馈表现服务（plan.md §2 feedback/FeedbackService.java、§1.5 双条 HUD / §1.6 阶段播报，M3）。
 *
 * <p><b>组成</b>（全部 Paper 原生主线，§5.2「核心玩法不依赖任何插件型软依赖」）：</p>
 * <ul>
 *   <li>actionbar 双条：独立 <b>1s 周期任务</b>（{@code runTaskTimer(plugin, 20, 20)}，
 *       与 RadiationTask 结算任务完全解耦）；辐射等级条 + 腐蚀条渲染收敛在纯函数
 *       {@link HudRenderer}；{@code feedback.bossbar-corrosion=true} 时腐蚀条改 BossBar
 *       常驻（每人一条：addPlayer/setTitle/setProgress/setColor），actionbar 仅等级条；</li>
 *   <li>阶段跳变播报：作为 {@link CorrosionMeter} 内存阶段回调的桥广播
 *       {@link CorrosionStageChangeEvent}，本类以 <b>MONITOR + 显式 isCancelled 检查</b>消费——
 *       取消 = 仅抑制播报（title/音效），不改任何腐蚀数值（§1.6 语义钉死）；
 *       warn/danger 升档 title+音效、首次到 warn 附一句规则说明
 *       （{@link PlayerRadState#isFirstWarnAnnounced()} 一次性标记）、回档播「辐射腐蚀减轻」；
 *       播报跟随 {@code feedback.sound} 开关（title+音效一体）；</li>
 *   <li>受击反馈：经 {@link DamageApplier.DamageFeedbackHook} 挂进真实掉血路径
 *       （粒子受 {@code feedback.particles} 控制、音效受 {@code feedback.sound} 控制）；
 *       <b>掉血归因记录（死亡文案时间窗判据）无条件写入</b>——否则 particles/sound 关闭时
 *       {@code feedback.death-message} 会失去归因线索；</li>
 *   <li>ProtocolLib 增强（可选路径）：softdepend 检测 + try/catch 包裹，仅做
 *       「抑制原版受伤红闪」；实现失败/类缺失一律留桩降级（§5.2），包级 HUD 全量替换
 *       为 §4 扩展（本期不做）。</li>
 * </ul>
 *
 * <p><b>异常隔离</b>（M2b 单玩家 try/catch 原则同款）：本类对玩家状态<b>只读</b>；
 * 任何一环异常只记日志，绝不外抛、绝不中断结算循环。reload 后旧实例的 Bukkit
 * 事件处理器仍在 HandlerList（监听注册不可逆），故全部事件处理先做
 * {@code plugin.feedback() != this} 陈旧实例守卫。</p>
 */
public final class FeedbackService implements Listener, Runnable, DamageApplier.DamageFeedbackHook {

    /** HUD 独立周期：20 tick = 1s（§1.5「实时显示」与结算任务解耦）。 */
    public static final long HUD_PERIOD_TICKS = 20L;

    /** 阶段播报 title 时长（tick）：淡入/停留/淡出。 */
    private static final int TITLE_FADE_IN = 5;
    private static final int TITLE_STAY = 40;
    private static final int TITLE_FADE_OUT = 10;

    /**
     * 受击粒子解析链（§1.5「轻微 WIHN 粒子」）：1.21.11 的 org.bukkit.Particle 枚举
     * <b>无 WIHN/WINK 常量</b>（jar 核实），按口径降级为最接近的烟雾类
     * WHITE_SMOKE → SMOKE → CLOUD → POOF；全部缺失则粒子反馈停用（返回 null）。
     */
    private static final List<String> HIT_PARTICLE_CANDIDATES =
            List.of("WIHN", "WINK", "WHITE_SMOKE", "SMOKE", "CLOUD", "POOF");

    private final RadSurvivalPlugin plugin;
    private final Particle hitParticle; // null = 粒子类全部缺失（§1.5 降级为无粒子）

    /**
     * 桥接登记（reload 解绑用，判定链路强化）：记录本实例挂进 core 的回调引用，
     * shutdown 时精确摘除——旧实现只 shutdown 不摘除，旧实例残留在
     * {@code CorrosionMeter} 的监听表与 {@code DamageApplier} 的挂点上继续被调用（reload 泄漏）。
     */
    private final DamageApplier.DamageFeedbackHook hookRef = this::onRadiationDamage;
    private final CorrosionMeter.CorrosionStageListener stageRef = this::broadcastStageChange;
    /** 伤害通道/归因门控监听（可选；由 onEnable 注入，onQuit 同步清理其标记）。 */
    private com.ft.radsurvival.listener.SunRadiationListener sunRadiationListener;

    private BukkitTask hudTask;
    private volatile boolean running;

    /** per-玩家 BossBar（feedback.bossbar-corrosion；§1.5 常驻腐蚀条）。 */
    private final Map<UUID, BossBar> bossbars = new ConcurrentHashMap<>();
    /** 最近一次辐射真实掉血记录（死亡文案时间窗归因，§1.5/M3；反馈全关也记录）。 */
    private final Map<UUID, DamageTagListener.RadiationHit> lastRadiationHits = new ConcurrentHashMap<>();

    /** ProtocolLib 增强体；null = 未启用（原生降级，§5.2）。 */
    private HurtFlashEnhancer enhancer;

    // ------------------------------------------------ 可观测计数器（单测/排障用）
    private final AtomicLong hudTicks = new AtomicLong();
    private final AtomicLong actionbarSends = new AtomicLong();
    private final AtomicLong bossbarUpdates = new AtomicLong();
    private final AtomicLong announcementsPlayed = new AtomicLong();
    private final AtomicLong announcementsSuppressed = new AtomicLong();
    private final AtomicLong feedbackErrors = new AtomicLong();

    public FeedbackService(RadSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.hitParticle = resolveHitParticle(plugin.getLogger());
        // —— 桥接 core 服务：内存阶段回调 → Bukkit 事件；真实掉血路径 → 受击反馈
        RadiationServices services = plugin.services();
        if (services != null) {
            services.corrosionMeter().addStageListener(stageRef);
            services.damageApplier().setDamageFeedbackHook(hookRef);
        }
        attachEnhancerIfRequested();
    }

    /**
     * 挂入伤害通道门控监听（{@code listener/SunRadiationListener}，判定链路强化）：
     * 该监听器在 {@code damage()} 之后登记 SUN 标记，退服时需同步清理，故由本服务持有引用。
     * 传 null = 不接管（测试/未启用）。
     */
    public void attachSunRadiationListener(com.ft.radsurvival.listener.SunRadiationListener listener) {
        this.sunRadiationListener = listener;
    }

    // ------------------------------------------------------------------ 生命周期

    /** 启动 HUD 独立周期任务（actionbar 与 bossbar 全关时不建任务，§1.5 开关族）。 */
    public void start() {
        running = true;
        PluginConfig cfg = plugin.config();
        if (cfg == null || (!cfg.feedback().actionbar() && !cfg.feedback().bossbarCorrosion())) {
            return;
        }
        if (hudTask == null) {
            hudTask = plugin.getServer().getScheduler()
                    .runTaskTimer(plugin, this, HUD_PERIOD_TICKS, HUD_PERIOD_TICKS);
        }
    }

    /**
     * 关停（reload 重建 / onDisable）：取消 HUD 任务 → 移除所有玩家 BossBar
     * （removePlayer + setVisible(false)，§1.5 常驻条正确清理）→ 清 actionbar
     * → 摘除 ProtocolLib 增强。幂等。
     */
    public void shutdown() {
        running = false;
        // —— 先解绑 core 回调（判定链路强化）：旧实例不得再收到阶段跳变/受击事件，
        //    否则 reload 后旧 HUD 计数继续增长、旧事件处理器被重复广播
        RadiationServices services = plugin.services();
        if (services != null) {
            try {
                services.corrosionMeter().removeStageListener(stageRef);
            } catch (RuntimeException ex) {
                recordError("阶段回调解绑", ex);
            }
            try {
                services.damageApplier().setDamageFeedbackHook(null);
            } catch (RuntimeException ex) {
                recordError("受击挂点解绑", ex);
            }
        }
        if (hudTask != null) {
            hudTask.cancel();
            hudTask = null;
        }
        for (Map.Entry<UUID, BossBar> entry : bossbars.entrySet()) {
            try {
                // bug hunt P2-1：不能用 Bukkit.getPlayer(uuid) 做前置条件——
                // reload 前刚断线的玩家此刻取不到 Player，旧实现就只 setVisible(false)、
                // 不 removePlayer，条子会在客户端残留到重进为止。
                // BossBar#removeAll() 不需要 Player 实例，是彻底清空的正确姿势。
                entry.getValue().removeAll();
                entry.getValue().setVisible(false);
            } catch (RuntimeException ex) {
                recordError("BossBar 清理", ex);
            }
        }
        bossbars.clear();
        lastRadiationHits.clear();
        clearActionBarForAll();
        if (enhancer != null) {
            try {
                enhancer.detach();
            } catch (RuntimeException | Error ex) {
                recordError("ProtocolLib 增强摘除", ex);
            }
            enhancer = null;
        }
        // bug hunt P2-3：把本实例真正移出 HandlerList。
        // 旧实现只依赖 plugin.feedback() != this 守卫「让它失效」，实例本身仍常驻
        // HandlerList —— 每次 reload 都多一份失效对象。显式注销后，泄漏从
        // 「靠守卫兜底」变成「实例真的离场」（守卫保留作双保险，onDisable 也有同类先例）。
        try {
            org.bukkit.event.HandlerList.unregisterAll(this);
        } catch (RuntimeException ex) {
            recordError("HandlerList 注销", ex);
        }
    }

    public boolean isHudScheduled() {
        return hudTask != null;
    }

    // ------------------------------------------------------------------ HUD 主渲染

    @Override
    public void run() {
        if (!running) {
            return;
        }
        RadiationServices services = plugin.services();
        PluginConfig cfg = plugin.config();
        if (services == null || cfg == null) {
            return;
        }
        PluginConfig.FeedbackConfig fb = cfg.feedback();
        if (!fb.actionbar() && !fb.bossbarCorrosion()) {
            return;
        }
        hudTicks.incrementAndGet();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            try {
                renderOne(player, services, cfg, fb);
            } catch (RuntimeException ex) {
                // 单玩家隔离：反馈异常绝不中断其余玩家渲染与任何结算（M2b 原则）
                recordError("玩家 " + player.getName() + " HUD 渲染", ex);
            }
        }
    }

    /**
     * 单玩家渲染：等级条/腐蚀条（actionbar）+ 腐蚀 BossBar（常驻）——全部只读。
     *
     * <p>口径（判定链路强化）：露天/timeFactor/减免一律取
     * {@link RadiationTask#previewRound} 的<b>实时判定快照</b>，不再读结算镜像
     * （{@link PlayerRadState}）——旧实现下被过滤链跳过的轮次（切创造/旁观、豁免权限、
     * 停用世界、require-on-ground 未着地）不刷新镜像，HUD 会持续显示上一轮的过期观测量
     * （如切到创造仍显 ▲▲▲▲▲）。previewRound 对被过滤玩家返回 exposed=false，
     * 天然渲染为「●安全」，与结算语义严格一致；露天判定走 Caffeine 段缓存、逐秒重算开销可忽略。</p>
     */
    private void renderOne(Player player, RadiationServices services,
                           PluginConfig cfg, PluginConfig.FeedbackConfig fb) {
        UUID id = player.getUniqueId();
        RadiationTask.RoundOutcome live = services.radiationTask().previewRound(player);
        double corrosion = live.corrosionAfter();
        boolean exposed = live.exposed();
        double timeFactor = live.timeFactor();
        // v1.2：免疫只来自抗尘埃药窗口（护甲已无法免疫），直接从免疫服务取
        boolean dustImmune = services.dustImmunity().isImmune(id);

        if (fb.actionbar()) {
            String line = HudRenderer.renderActionBar(exposed, timeFactor, dustImmune, corrosion,
                    cfg.corrosion().stageWarn(), cfg.corrosion().stageDanger(), fb.bossbarCorrosion());
            player.sendActionBar(line);
            actionbarSends.incrementAndGet();
        }
        if (fb.bossbarCorrosion()) {
            // bug hunt P2-2：阶段色必须按**显示口径**（取整后的百分位）来算，
            // 否则 44.7% + 阈值 45 时标题写着「45%」、条子却是正常档的蓝色，
            // 玩家看到的是自相矛盾的两条信息。CommandOutput 的命令行读数已是此口径。
            int displayPct = HudRenderer.corrosionDisplayPercent(corrosion);
            int stage = com.ft.radsurvival.core.RadiationMath
                    .stageOf(displayPct, cfg.corrosion().stageWarn(), cfg.corrosion().stageDanger());
            BossBar bar = bossbars.get(id);
            if (bar == null) {
                bar = Bukkit.createBossBar(
                        HudRenderer.renderBossBarTitle(corrosion, cfg.corrosion().stageWarn(), cfg.corrosion().stageDanger()),
                        stageBarColor(stage), BarStyle.SOLID);
                bar.setProgress(displayPct / 100.0);
                bar.setVisible(true);
                bossbars.put(id, bar);
            } else {
                bar.setTitle(HudRenderer.renderBossBarTitle(
                        corrosion, cfg.corrosion().stageWarn(), cfg.corrosion().stageDanger()));
                bar.setColor(stageBarColor(stage));
                bar.setProgress(displayPct / 100.0);
            }
            // bug hunt P1-8：无条件 addPlayer。
            // 旧写法 `if (!bar.getPlayers().contains(player))` 有个致命盲点：死亡、跨维度、
            // 传送会让**客户端**丢掉这条 BossBar，而 Bukkit 侧的 getPlayers() 仍包含该玩家，
            // 于是永远不再补发 → 腐蚀条永久消失，直到 reload/重启。
            // addPlayer 对同一玩家重复调用是幂等 no-op，开销可忽略。
            bar.addPlayer(player);
            bossbarUpdates.incrementAndGet();
        }
    }

    /** BossBar 阶段配色：normal 蓝（中性）、warn 黄、danger 红（§1.5 阶段色口径同 HUD）。 */
    static BarColor stageBarColor(int stage) {
        return switch (stage) {
            case CorrosionMeter.STAGE_WARN -> BarColor.YELLOW;
            case CorrosionMeter.STAGE_DANGER -> BarColor.RED;
            default -> BarColor.BLUE;
        };
    }

    // ------------------------------------------------------------ 阶段事件桥与播报

    /**
     * {@link CorrosionMeter} 内存阶段回调桥（主线程触发）：广播
     * {@link CorrosionStageChangeEvent}；异常吞掉只记日志——播报链路失败不得反噬结算。
     */
    private void broadcastStageChange(UUID playerId, int oldStage, int newStage) {
        try {
            PluginConfig cfg = plugin.config();
            RadiationServices services = plugin.services();
            if (cfg == null || services == null) {
                return;
            }
            Player player = Bukkit.getPlayer(playerId); // 离线=null；事件仍广播（§1.6 阶段接口）
            CorrosionStageChangeEvent event = new CorrosionStageChangeEvent(
                    playerId, player,
                    services.corrosionMeter().get(playerId),
                    oldStage, newStage,
                    cfg.corrosion().stageWarn(), cfg.corrosion().stageDanger());
            plugin.getServer().getPluginManager().callEvent(event);
        } catch (RuntimeException ex) {
            recordError("腐蚀阶段事件广播", ex);
        }
    }

    /**
     * 阶段播报消费端：<b>MONITOR</b> + 显式 {@code isCancelled} 检查——取消 = 仅抑制本播报
     * （title/音效），腐蚀数值早已由 {@link CorrosionMeter} 写入、不受影响（§1.6 Cancellable 语义）。
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = false)
    public void onStageChange(CorrosionStageChangeEvent event) {
        if (plugin.feedback() != this) {
            return; // reload 后旧实例的处理器残留守卫
        }
        if (event.isCancelled()) {
            announcementsSuppressed.incrementAndGet();
            return;
        }
        announceStage(event);
    }

    /** title/音效播报（跟随 feedback.sound 开关；全部 try/catch 隔离）。 */
    private void announceStage(CorrosionStageChangeEvent event) {
        PluginConfig cfg = plugin.config();
        if (cfg == null || !cfg.feedback().sound()) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return; // 离线：只广播事件，不播报
        }
        announcementsPlayed.incrementAndGet();
        try {
            int pct = HudRenderer.corrosionDisplayPercent(event.getCorrosion());
            RadiationServices services = plugin.services();
            PlayerRadState state = services == null
                    ? null : services.radiationTask().stateOf(event.getPlayerId());
            if (event.isUpward()) {
                String title;
                String subtitle;
                Sound sound;
                float volume;
                float pitch;
                if (event.getNewStage() == CorrosionMeter.STAGE_DANGER) {
                    title = "§c辐射腐蚀危险！";
                    subtitle = "§7腐蚀已达 §c" + pct + "%§7（阈值 " + fmt(event.getDangerThreshold())
                            + "%），辐射伤害大幅放大，尽快寻找遮蔽！";
                    sound = Sound.BLOCK_ANVIL_LAND;
                    volume = 0.4f;
                    pitch = 0.7f;
                } else {
                    title = "§e辐射腐蚀加重！";
                    subtitle = "§7腐蚀已达 §e" + pct + "%§7（阈值 " + fmt(event.getWarnThreshold())
                            + "%），辐射伤害开始放大。";
                    sound = Sound.BLOCK_NOTE_BLOCK_BASS;
                    volume = 0.8f;
                    pitch = 0.7f;
                }
                // 首次到达 warn（及以上）附一句规则说明——解决「莫名掉血」（§1.6）；
                // PlayerRadState 一次性标记（M2a 已建），播报后置位防重复
                if (state != null && !state.isFirstWarnAnnounced()) {
                    subtitle += " §6规则：露天（尤其白天）持续受伤且越久越痛；遮蔽后腐蚀回落，盔甲可减免。";
                    state.setFirstWarnAnnounced(true);
                }
                player.sendTitle(title, subtitle, TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT);
                player.playSound(player.getLocation(), sound, volume, pitch);
            } else {
                // 降级回档（§1.6 播报「辐射腐蚀减轻」）
                player.sendTitle("§a辐射腐蚀减轻",
                        "§7腐蚀降至 §a" + pct + "%§7（阶段：§e"
                                + CorrosionStageChangeEvent.stageName(event.getOldStage())
                                + "§7→§a" + CorrosionStageChangeEvent.stageName(event.getNewStage()) + "§7）",
                        TITLE_FADE_IN, 30, TITLE_FADE_OUT);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.4f);
            }
        } catch (RuntimeException ex) {
            recordError("阶段播报发送", ex);
        }
    }

    // ------------------------------------------------------------------ 受击反馈

    /**
     * {@link DamageApplier.DamageFeedbackHook}：仅真实掉血路径回调（免疫/取消/覆写≤0 不到这）。
     * 归因记录无条件写入；粒子/音效分别跟随 particles/sound 开关；任何异常只记日志。
     */
    @Override
    public void onRadiationDamage(Player player, double amount) {
        lastRadiationHits.put(player.getUniqueId(),
                new DamageTagListener.RadiationHit(System.currentTimeMillis(), amount));
        PluginConfig cfg = plugin.config();
        if (cfg == null) {
            return;
        }
        if (cfg.feedback().particles() && hitParticle != null) {
            try {
                // §1.5 轻微粒子：受击点上方少量烟雾（1.21.11 无 WIHN 枚举行，降级链见常量注释）
                player.spawnParticle(hitParticle, player.getEyeLocation(), 6, 0.25, 0.25, 0.25);
            } catch (RuntimeException ex) {
                recordError("受击粒子", ex);
            }
        }
        if (cfg.feedback().sound()) {
            try {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.15f, 0.9f);
            } catch (RuntimeException ex) {
                recordError("受击音效", ex);
            }
        }
    }

    // ------------------------------------------------------------------ 死亡归因读口

    /** 玩家最近一次辐射真实掉血记录（{@link DamageTagListener} 消费；无记录 null）。 */
    public DamageTagListener.RadiationHit lastRadiationHit(UUID playerId) {
        return lastRadiationHits.get(playerId);
    }

    /**
     * 死亡归因时间窗（毫秒）= tick-interval×50ms + {@link DamageTagListener#HIT_WINDOW_SLICE_MS}
     * 宽限——玩家死亡必须发生在「距最近一轮辐射结算 ≤1 个周期+宽限」内，
     * 超窗即视为其他死因（§1.5 不误伤原则）。
     */
    public long radiationWindowMillis() {
        PluginConfig cfg = plugin.config();
        long intervalMs = (cfg == null ? PluginConfig.MIN_TICK_INTERVAL : cfg.tickInterval()) * 50L;
        return intervalMs + DamageTagListener.HIT_WINDOW_SLICE_MS;
    }

    // ------------------------------------------------------------------ 会话清理

    @EventHandler(ignoreCancelled = false)
    public void onQuit(PlayerQuitEvent event) {
        if (plugin.feedback() != this) {
            return;
        }
        UUID id = event.getPlayer().getUniqueId();
        BossBar bar = bossbars.remove(id);
        if (bar != null) {
            try {
                bar.removePlayer(event.getPlayer());
                bar.setVisible(false);
            } catch (RuntimeException ex) {
                recordError("退服 BossBar 清理", ex);
            }
        }
        lastRadiationHits.remove(id);
        if (sunRadiationListener != null) {
            sunRadiationListener.forget(id); // SUN 标记同步清理（防 UUID 复用窗口误判）
        }
    }

    /** 清所有在线玩家 actionbar（onDisable/reload 交接；§2 装配 onDisable 要求）。 */
    private void clearActionBarForAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            try {
                player.sendActionBar("");
            } catch (RuntimeException ex) {
                recordError("actionbar 清理", ex);
            }
        }
    }

    // ------------------------------------------------------------------ ProtocolLib 增强

    /**
     * 仅做一件低风险事：抑制发给自己的原版受伤红闪（取消 client-bound ENTITY_STATUS(HURT) 包）。
     * AUTO 未检测到 ProtocolLib / ON 缺失 / 挂载异常 → 一律留桩降级，核心功能零影响（§5.2 对策）。
     */
    private void attachEnhancerIfRequested() {
        PluginConfig cfg = plugin.config();
        if (cfg == null) {
            return;
        }
        PluginConfig.ProtocolLibMode mode = cfg.feedback().protocolLib();
        if (mode == PluginConfig.ProtocolLibMode.OFF) {
            return;
        }
        Plugin protocolLib = plugin.getServer().getPluginManager().getPlugin("ProtocolLib");
        boolean present = protocolLib != null && protocolLib.isEnabled();
        if (mode == PluginConfig.ProtocolLibMode.AUTO && !present) {
            return; // 原生降级：actionbar/BossBar 即权威反馈通道（§1.5）
        }
        if (!present) {
            plugin.getLogger().warning("feedback.protocol-lib=true 但未检测到已启用的 ProtocolLib："
                    + "红闪抑制未启用（核心功能与反馈主线不受影响）");
            return;
        }
        try {
            HurtFlashEnhancer candidate = new ProtocolLibHurtFlashEnhancer();
            candidate.attach(plugin);
            this.enhancer = candidate;
            plugin.getLogger().info("ProtocolLib 已接入 · 受伤红闪抑制已启用（包级 HUD 替换属 §4 扩展，未启用）");
        } catch (Throwable t) { // NoClassDefFoundError/API 漂移均留桩（不影响任何核心功能）
            plugin.getLogger().warning("ProtocolLib 已检测，红闪抑制未启用（留桩降级）: " + t);
        }
    }

    /** 当前增强体（null=未启用；测试/排障读口）。 */
    public HurtFlashEnhancer enhancer() {
        return enhancer;
    }

    // ------------------------------------------------------------------ 纯辅助

    /** 候选链解析受击粒子（缺失项跳过；全缺返回 null）。包内可见供单测钉死。 */
    static Particle resolveHitParticle(Logger log) {
        Particle fallback = null;
        for (String name : HIT_PARTICLE_CANDIDATES) {
            try {
                fallback = Particle.valueOf(name);
                break;
            } catch (IllegalArgumentException ignored) {
                // 该版本枚举无此常量，继续下一个候选
            }
        }
        if (fallback != null && !"WIHN".equals(fallback.name()) && log != null
                && Boolean.getBoolean("radsurvival.verbose")) {
            // 常规输出降噪：降级事实已由启动日志「粒子=WHITE_SMOKE」体现，细节只在
            // -Dradsurvival.verbose=true 时展开（§1.5 原案 WIHN 在 1.21.11 不存在）
            log.info("受击粒子降级链：Particle 枚举无 WIHN → " + fallback + "（最接近的烟雾类）");
        }
        return fallback;
    }

    private void recordError(String what, Throwable ex) {
        feedbackErrors.incrementAndGet();
        plugin.getLogger().warning("反馈层「" + what + "」异常（已隔离，不影响结算）: " + ex);
    }

    /** 去掉多余小数尾零（与 PluginConfig/RadCommand 同口径）。 */
    private static String fmt(double v) {
        return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------------ 观测读口（测试/排障）

    public long hudTicks() {
        return hudTicks.get();
    }

    public long actionbarSends() {
        return actionbarSends.get();
    }

    public long bossbarUpdates() {
        return bossbarUpdates.get();
    }

    public long announcementsPlayed() {
        return announcementsPlayed.get();
    }

    public long announcementsSuppressed() {
        return announcementsSuppressed.get();
    }

    public long feedbackErrors() {
        return feedbackErrors.get();
    }

    /** 当前挂载 BossBar 的玩家集合（reload 泄漏断言用）。 */
    public Map<UUID, BossBar> bossbarsView() {
        return Map.copyOf(bossbars);
    }

    /** HUD 粒子解析结果（M4 实机核对用）。 */
    public Particle hitParticle() {
        return hitParticle;
    }
}
