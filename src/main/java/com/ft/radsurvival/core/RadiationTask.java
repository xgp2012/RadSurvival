package com.ft.radsurvival.core;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.config.PluginConfig;

/**
 * 主循环：辐射结算全局任务（plan.md §2 core/RadiationTask.java、§1.1~§1.6 全链路口径）。
 *
 * <p><b>线程模型</b>：本期主线程单任务（§2「玩家数 &lt;200 完全够用」已拍板），周期
 * {@code config.tick-interval}（M1 校验强制 ≥20 = 原版无敌帧下限，§1.3）。</p>
 *
 * <p><b>每轮每玩家过滤链</b>（顺序固定，任何一环命中即整轮跳过该玩家——不结算、
 * 不累积、不恢复，「冻结」口径）：</p>
 * <ol>
 *   <li>启用世界白名单（enabled-worlds，大小写不敏感世界名）。维度交集由
 *       {@link SunExposureChecker} 在露天判定处生效：豁免维度（不在 dimensions 配置内，
 *       默认下界/末地）判不出露天 → exposed=false → 自然走 §1.6「豁免维度只降不升」恢复分支，
 *       而伤害分支（要求 exposed=true）永不触发；</li>
 *   <li>游戏模式：CREATIVE/SPECTATOR 无条件跳过；其余模式仅当 ∈ damage-game-modes 才结算（§1.3）；</li>
 *   <li>豁免权限 {@code radsurvival.exempt}：持有者不结算、不累积（§3 权限）；</li>
 *   <li>require-on-ground=true 时仅 {@code player.isOnGround()} 参与（§1.3 字面口径）。</li>
 * </ol>
 *
 * <p><b>结算顺序（与 M2a 预算回归测试同序：先腐蚀累积、后伤害结算）</b>：
 * 露天判定（§1.1）→ DayNightProvider 取 timeFactor（§1.2，硬切换口径以 12000/23000 为界）
 * → ArmorCalculator 取减免（§1.4）→ {@link CorrosionMeter#settle}（elapsed=interval/20 秒，
 * 露天累积按 {@link RadiationMath#effectiveAccumRatePerSec} 的 M2a 内核口径 =
 * dayAccum×timeFactor×(armorAffectsAccum? 1−red : 1)、遮蔽/豁免维度按 regen 恢复）
 * → 腐蚀倍率用<b>步进后的新值</b> → finalDamage = {@link RadiationMath#finalDamage}，
 * &gt;0 时经 {@link DamageApplier} 广播 {@code RadiationExposureEvent} 并真实掉血。</p>
 *
 * <p><b>不存在「减免 ≥ 1.0 → 完全免疫」的短路分支</b>（v1.2 起）：配置校验拒绝任何
 * ≥ 1.0 的材质系数，{@link RadiationMath#clampUnit} 也把比例钳在 [0,1]，因此哪怕穿满
 * 最强材质（下界合金满配 72%）仍会吃到 {@code armor.min-damage-fraction} 的保底伤害。
 * 唯一的真免疫来源是抗尘埃药窗口（{@code DustImmunity}），它在露天判定阶段就把本轮回落到
 * 「零伤害、零累积」。旧注释描述的「全下界合金零伤害零累积」是 v1.1 语义，已不成立。</p>
 *
 * <p>玩家状态 {@code Map<UUID, PlayerRadState>} 由本类持有；生命周期由
 * listener/PlayerSessionListener 经 {@link #registerPlayer}/{@link #unregisterPlayer} 维护
 * （join 建、quit 落盘后移除 → 离线冻结，§2/§1.6）。</p>
 */
public final class RadiationTask implements Runnable {

    /** 豁免权限节点（§3：不受辐射伤害也不累积腐蚀；plugin.yml 已声明）。 */
    public static final String PERMISSION_EXEMPT = "radsurvival.exempt";

    /**
     * 露天判定注入缝（测试点）：生产默认绑定 {@link SunExposureChecker#isExposed(World,
     * org.bukkit.Location)}；MockBukkit 的 {@code BlockData#isOccluding} 未实现（4.116.3 实测
     * UnimplementedOperationException），集成测试注入假判定器——仅换「头顶是否有遮挡」的
     * 观测量，过滤链/腐蚀步进/免疫短路/事件/伤害各环仍走同一生产代码。
     */
    @FunctionalInterface
    public interface ExposureResolver {
        boolean isExposed(Player player);
    }

    /** 生产默认解析器：真 SunExposureChecker + 玩家眼睛坐标（§1.1，缓存/维度短路在内核内）。 */
    public static ExposureResolver defaultResolver(SunExposureChecker checker) {
        return player -> checker.isExposed(player.getWorld(), player.getEyeLocation());
    }

    /** tick→秒换算（20 tick = 1s，MC 固定口径；elapsed = tick-interval / 20）。 */
    private static final double TICKS_PER_SECOND = 20.0;

    /** 单轮结算结论（顺序即过滤链命中项；用于 /rad force-check 与测试断言）。 */
    public enum RoundResult {
        /** 世界不在 enabled-worlds → 整轮冻结。 */
        SKIPPED_DISABLED_WORLD,
        /** 创造/旁观 → 无条件跳过（§1.3）。 */
        SKIPPED_CREATIVE_SPECTATOR,
        /** 模式不在 damage-game-modes → 跳过（§1.3）。 */
        SKIPPED_GAME_MODE,
        /** 持有 radsurvival.exempt → 不结算不累积（§3）。 */
        SKIPPED_EXEMPT,
        /** require-on-ground=true 且玩家不在地上 → 跳过（§1.3）。 */
        SKIPPED_NOT_ON_GROUND,
        /** 抗尘埃药免疫窗口内（v1.2；旧口径「盔甲减免 ≥1.0 完全免疫」已随护甲削弱移除）。 */
        IMMUNE,
        /** 非露天（遮蔽或豁免维度）：本轮腐蚀恢复/不累积，零伤害（§1.1/§1.6）。 */
        COVERED,
        /** 露天且可打但公式伤害 ≤ 0（如 night-multiplier=0 的深夜）：不发事件。 */
        ZERO_DAMAGE,
        /** 事件被监听器取消（或被覆写为 ≤0）：本轮不掉血（§4.8）。 */
        CANCELLED_BY_EVENT,
        /** 本轮真实（或预计，见 {@link RoundOutcome#damageApplied}）结算伤害。 */
        DAMAGED;

        /** 面向命令输出的中文说明。 */
        public String describe() {
            return switch (this) {
                case SKIPPED_DISABLED_WORLD -> "世界不在 enabled-worlds 白名单（整轮冻结）";
                case SKIPPED_CREATIVE_SPECTATOR -> "创造/旁观模式无条件免疫（§1.3）";
                case SKIPPED_GAME_MODE -> "游戏模式不在 damage-game-modes，不结算（§1.3）";
                case SKIPPED_EXEMPT -> "持有 radsurvival.exempt 豁免：不结算、不累积";
                case SKIPPED_NOT_ON_GROUND -> "require-on-ground=true 且玩家未着地：跳过（§1.3）";
                case IMMUNE -> "抗尘埃药免疫窗口内：不掉血、不发事件（v1.2）";
                case COVERED -> "非露天（遮蔽或豁免维度）：不受伤，腐蚀走恢复分支";
                case ZERO_DAMAGE -> "公式伤害 ≤ 0：不结算";
                case CANCELLED_BY_EVENT -> "RadiationExposureEvent 被取消/覆写为 ≤0：本轮不掉血";
                case DAMAGED -> "本轮结算伤害";
            };
        }
    }

    /**
     * 单轮结算明细。预览（{@link #previewRound}）与真实结算（{@link #settleRound}）共用本结构：
     * 预览时 {@code damageApplied=false}，{@code damage} 为按当前腐蚀值推算的本轮伤害，
     * {@code corrosionAfter==corrosionBefore}（未步进）。
     */
    public record RoundOutcome(RoundResult result, boolean exposed, DayNightProvider.Phase phase,
                               double timeFactor, double armorReduction,
                               double corrosionBefore, double corrosionAfter,
                               double damage, boolean damageApplied) {

        static RoundOutcome skip(RoundResult reason, DayNightProvider.Phase phase,
                                double timeFactor, double corrosion) {
            return new RoundOutcome(reason, false, phase, timeFactor, 0.0, corrosion, corrosion, 0.0, false);
        }
    }

    private final RadSurvivalPlugin plugin;
    private final PluginConfig config;
    private final SunExposureChecker sunExposureChecker;
    private final ExposureResolver exposureResolver;
    private final DayNightProvider dayNightProvider;
    private final ArmorCalculator armorCalculator;
    private final CorrosionMeter corrosionMeter;
    private final DamageApplier damageApplier;
    private final DustImmunity dustImmunity;

    /** 玩家会话状态表（§2；主线程读写，ConcurrentHashMap 仅为测试可见性兜底）。 */
    private final Map<UUID, PlayerRadState> states = new ConcurrentHashMap<>();

    /** 露天判定环境异常 fallback 计数（{@link #safeExposed}）。 */
    private final java.util.concurrent.atomic.AtomicLong exposureFallbacks =
            new java.util.concurrent.atomic.AtomicLong();

    private BukkitTask bukkitTask;

    public RadiationTask(RadSurvivalPlugin plugin, PluginConfig config,
                         SunExposureChecker sunExposureChecker, DayNightProvider dayNightProvider,
                         ArmorCalculator armorCalculator, CorrosionMeter corrosionMeter,
                         DamageApplier damageApplier) {
        this(plugin, config, sunExposureChecker, defaultResolver(sunExposureChecker),
                dayNightProvider, armorCalculator, corrosionMeter, damageApplier, new DustImmunity());
    }

    /** 测试装配构造：露天判定经 {@link ExposureResolver} 注入（其余链路不变）。 */
    public RadiationTask(RadSurvivalPlugin plugin, PluginConfig config,
                         SunExposureChecker sunExposureChecker, ExposureResolver exposureResolver,
                         DayNightProvider dayNightProvider,
                         ArmorCalculator armorCalculator, CorrosionMeter corrosionMeter,
                         DamageApplier damageApplier) {
        this(plugin, config, sunExposureChecker, exposureResolver, dayNightProvider,
                armorCalculator, corrosionMeter, damageApplier, new DustImmunity());
    }

    /** 全参数构造（含 v1.2 抗尘埃药免疫窗口）。 */
    public RadiationTask(RadSurvivalPlugin plugin, PluginConfig config,
                         SunExposureChecker sunExposureChecker, ExposureResolver exposureResolver,
                         DayNightProvider dayNightProvider, ArmorCalculator armorCalculator,
                         CorrosionMeter corrosionMeter, DamageApplier damageApplier,
                         DustImmunity dustImmunity) {
        this.plugin = plugin;
        this.config = config;
        this.sunExposureChecker = sunExposureChecker;
        this.exposureResolver = exposureResolver;
        this.dayNightProvider = dayNightProvider;
        this.armorCalculator = armorCalculator;
        this.corrosionMeter = corrosionMeter;
        this.damageApplier = damageApplier;
        this.dustImmunity = dustImmunity;
    }

    /** v1.2：抗尘埃药免疫窗口（结算链判断 + 命令/API 读取）。 */
    public DustImmunity dustImmunity() {
        return dustImmunity;
    }

    // -------------------------------------------------------------- 生命周期/调度

    /** 注册全局重复任务（onEnable/reload 装配时调用；首次延迟 = 一个周期）。 */
    public void start() {
        if (bukkitTask != null) {
            throw new IllegalStateException("RadiationTask 已处于运行状态");
        }
        long interval = config.tickInterval();
        bukkitTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this, interval, interval);
    }

    /** 取消任务（幂等）。 */
    public void cancel() {
        if (bukkitTask != null) {
            bukkitTask.cancel();
            bukkitTask = null;
        }
    }

    public boolean isScheduled() {
        return bukkitTask != null;
    }

    /** 主循环入口（§2 全局任务）。单个玩家异常只记日志，不中断整轮。 */
    @Override
    public void run() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            try {
                settleRound(player);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("玩家 " + player.getName() + " 本轮辐射结算异常（已跳过该玩家）: " + ex);
            }
        }
    }

    // ------------------------------------------------------------------ 状态表

    /** join 登记：PDC → 内存腐蚀恢复（缺省 0）+ 建 PlayerRadState（§1.6/§2）。 */
    public PlayerRadState registerPlayer(Player player) {
        double corrosion = corrosionMeter.loadFromPlayer(player);
        PlayerRadState state = new PlayerRadState();
        state.setCorrosion(corrosion);
        state.setLastSettleStamp(System.currentTimeMillis());
        states.put(player.getUniqueId(), state);
        return state;
    }

    /** 取会话状态；可能为 null（如 reload 前从未 join 的在线玩家——settle 时懒建）。 */
    public PlayerRadState stateOf(UUID playerId) {
        return states.get(playerId);
    }

    /** quit 清理（调用方须先 saveToPlayer 落盘；§2「移除内存态 → 离线冻结」）。 */
    public void unregisterPlayer(UUID playerId) {
        states.remove(playerId);
    }

    /** reload/onDisable 时清空全部内存态（腐蚀权威值先由调用方冲刷到 PDC，§1.6）。 */
    public void clearStates() {
        states.clear();
    }

    public Map<UUID, PlayerRadState> statesView() {
        return Map.copyOf(states);
    }

    // ------------------------------------------------------------------ 结算主法

    /** 真实结算一轮（主循环与 /rad force-check 共用同一路径，含事件广播与掉血）。 */
    public RoundOutcome settleRound(Player player) {
        return evaluate(player, true);
    }

    /** 只读预览一轮（/rad status 用：不步进腐蚀、不发事件、不掉血）。 */
    public RoundOutcome previewRound(Player player) {
        return evaluate(player, false);
    }

    /**
     * 单轮完整流程（顺序与口径见类 javadoc）。
     *
     * @param apply true=真实结算（腐蚀步进 + DamageApplier），false=纯预览
     */
    private RoundOutcome evaluate(Player player, boolean apply) {
        World world = player.getWorld();
        UUID id = player.getUniqueId();
        // —— 阶段/timeFactor：先取，保证被过滤的轮次也带展示信息（统一走 DayNightProvider，
        //    12000/23000 硬切换口径在 smooth-transition=false 时由内核保证，M2b 已删命令内联旧口径）
        DayNightProvider.TimeInfo time = dayNightProvider.forWorld(world);
        double timeFactor = time.timeFactor();

        // —— 过滤链（命中 → 整轮冻结，不做任何腐蚀/伤害副作用）
        RoundResult filtered = firstFilterHit(player, world);
        if (filtered != null) {
            return RoundOutcome.skip(filtered, time.phase(), timeFactor, currentCorrosion(player, id, apply));
        }

        // —— 露天判定（§1.1；豁免维度/遮蔽 → false；生产默认解析器见 ExposureResolver；
        //    环境读取异常时退回上一判定，见 safeExposed）
        boolean exposed = safeExposed(player, id);
        double armorReduction = armorCalculator.reductionOf(player);

        // —— ① 先腐蚀步进（elapsed = interval/20 秒；恢复分支同样走 step，§1.6）
        //
        //  v1.2 抗尘埃药免疫：免疫窗口内**必须真正冻结累积**，而不只是跳过伤害。
        //  旧实现把免疫判定放在步进之后、只做提前 return —— 腐蚀照常上涨并落盘，
        //  与「免疫」二字及 HUD 上那一段 ◆抗尘 直接矛盾（玩家看得见腐蚀条还在动）。
        //  这里的做法：把「免疫」翻译成「本轮视作处于遮蔽」传给结算内核——
        //  step() 在 exposed=false 时走的是**恢复**分支（−regen/s），于是免疫期间
        //  腐蚀不涨反跌，与「躲进阴影」的既有语义完全一致，也不需要给内核加新参数。
        //  注意顺序未被破坏：步进依旧发生在伤害计算之前（先累后打不变量仍然成立），
        //  变的只是「本轮累积速率取什么值」。
        double corrosionBefore = currentCorrosion(player, id, apply);
        double corrosionAfter = corrosionBefore;
        PluginConfig.CorrosionConfig corrosionCfg = config.corrosion();
        boolean dustImmune = dustImmunity.isImmune(player);
        if (apply && corrosionCfg.enabled()) {
            double elapsedSeconds = config.tickInterval() / TICKS_PER_SECOND;
            corrosionAfter = corrosionMeter
                    .settle(id, exposed && !dustImmune, timeFactor, armorReduction, elapsedSeconds)
                    .value();
            // §2 持久化口径：值有变化即同步 Player PDC（内存写、随 player.dat 落盘）——
            // 服务器崩溃/强杀不再丢最近结算的腐蚀值（旧实现只在 quit/death/reload 落盘）
            if (Double.compare(corrosionAfter, corrosionBefore) != 0) {
                corrosionMeter.saveToPlayer(player);
            }
        }

        // —— 状态镜像（§2；供 M3 HUD / status 只读消费）
        PlayerRadState state = states.get(id);
        if (apply && state != null) {
            state.setExposed(exposed);
            state.setTimeFactor(timeFactor);
            state.setArmorReduction(armorReduction);
            state.setCorrosion(corrosionAfter);
            state.setLastSettleStamp(System.currentTimeMillis());
        }

        // —— ② 抗尘埃药免疫窗口（v1.2）：免疫期间零伤害、不发事件。
        //    腐蚀已在 ① 以「不累积、按 regen 回落」的方式处理好（见该处注释）。
        //    与旧的「盔甲减免 ≥1.0 完全免疫」是**不同**机制：后者已随 v1.2 护甲削弱被删除，
        //    现在唯一能获得辐射免疫的途径就是服药，且有时限。
        if (dustImmune) {
            if (apply && state != null) {
                state.setLastDamage(0.0);
            }
            return new RoundOutcome(RoundResult.IMMUNE, exposed, time.phase(), timeFactor,
                    armorReduction, corrosionBefore, corrosionAfter, 0.0, false);
        }

        // —— 遮蔽（含豁免维度）：零伤害、不发事件；腐蚀恢复已在 ① 完成
        if (!exposed) {
            if (apply && state != null) {
                state.setLastDamage(0.0);
            }
            return new RoundOutcome(RoundResult.COVERED, false, time.phase(), timeFactor,
                    armorReduction, corrosionBefore, corrosionAfter, 0.0, false);
        }

        // —— ③ §1.3 公式（corrosionMult 用步进后的新腐蚀值——先累后打，M2a 同序）
        double mult = corrosionCfg.enabled()
                ? RadiationMath.corrosionMultiplier(corrosionAfter, corrosionCfg.maxMultiplier(), corrosionCfg.curve())
                : 1.0;
        // 基线 = 尚未乘 (1−减免) 的部分；v1.2 最低杀伤保底以它为参照（见 ArmorCalculator#applyFloor）
        double baseline = config.baseDamage() * timeFactor * mult;
        double damage = armorCalculator.floor(
                RadiationMath.finalDamage(config.baseDamage(), timeFactor, mult, armorReduction), baseline);
        if (damage <= 0.0) {
            if (apply && state != null) {
                state.setLastDamage(0.0);
            }
            return new RoundOutcome(RoundResult.ZERO_DAMAGE, true, time.phase(), timeFactor,
                    armorReduction, corrosionBefore, corrosionAfter, 0.0, false);
        }

        // —— 预览：算而不打（damage = 预计本轮伤害）
        if (!apply) {
            return new RoundOutcome(RoundResult.DAMAGED, true, time.phase(), timeFactor,
                    armorReduction, corrosionBefore, corrosionAfter, damage, false);
        }

        // —— ④ 事件广播 + 真实结算（同一 DamageApplier 路径，§1.3）
        //    事件携带步进后的腐蚀值：与本轮 finalDamage 的倍率基数严格一致（判定链路强化，
        //    外部监听器改伤时看到的即是参与计算的数值，不再是滞后的结算前值）
        double applied = damageApplier.apply(player, true, timeFactor, armorReduction,
                corrosionAfter, damage);
        if (state != null) {
            state.setLastDamage(applied);
        }
        return new RoundOutcome(applied > 0.0 ? RoundResult.DAMAGED : RoundResult.CANCELLED_BY_EVENT,
                true, time.phase(), timeFactor, armorReduction, corrosionBefore, corrosionAfter,
                applied, applied > 0.0);
    }

    /**
     * 露天判定容错（判定链路强化）：环境读取抛异常（区块未加载、API 意外缺失等）不得击穿
     * 结算/HUD 渲染主线（§6「误判遮蔽=安全」同向的 fail-safe 取向）——退回该玩家上一已知判定
     * （无记录按遮蔽=安全，宁可漏一轮累积、不可错一轮伤害）；fallback 计数可观测，
     * 警示节流防刷屏。
     */
    private boolean safeExposed(Player player, UUID id) {
        try {
            return exposureResolver.isExposed(player);
        } catch (RuntimeException ex) {
            long n = exposureFallbacks.incrementAndGet();
            if (n == 1 || n % 60 == 0) {
                plugin.getLogger().warning("玩家 " + player.getName() + " 露天判定异常，"
                        + "本轮退回上一判定（累计第 " + n + " 次）: " + ex);
            }
            PlayerRadState previous = states.get(id);
            return previous != null && previous.isExposed();
        }
    }

    /** 露天判定 fallback 累计次数（排障/测试读口）。 */
    public long exposureFallbacks() {
        return exposureFallbacks.get();
    }

    /** 过滤链按序求第一命中；全部通过返回 null。 */
    private RoundResult firstFilterHit(Player player, World world) {
        // ① 启用世界白名单（dimensions 交集在露天判定处生效，见类 javadoc）
        if (!config.enabledWorlds().contains(world.getName().toLowerCase(Locale.ROOT))) {
            return RoundResult.SKIPPED_DISABLED_WORLD;
        }
        // ② 游戏模式（§1.3：创造/旁观无条件跳过）
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return RoundResult.SKIPPED_CREATIVE_SPECTATOR;
        }
        if (!config.damageGameModes().contains(mode)) {
            return RoundResult.SKIPPED_GAME_MODE;
        }
        // ③ 豁免玩家（§3 权限）
        if (player.hasPermission(PERMISSION_EXEMPT)) {
            return RoundResult.SKIPPED_EXEMPT;
        }
        // ④ 着地口径（§1.3）
        if (config.requireOnGround() && !player.isOnGround()) {
            return RoundResult.SKIPPED_NOT_ON_GROUND;
        }
        return null;
    }

    /**
     * 当前腐蚀值：内存权威（{@link CorrosionMeter}）。真实结算时若玩家尚无状态
     * （reload 竞态/异常残留），就地 {@link #registerPlayer} 从 PDC 重建，保证口径闭环（§1.6）；
     * 预览保持纯只读，不写内存。
     */
    private double currentCorrosion(Player player, UUID id, boolean apply) {
        if (apply && !states.containsKey(id)) {
            registerPlayer(player);
        }
        return corrosionMeter.get(id);
    }
}
