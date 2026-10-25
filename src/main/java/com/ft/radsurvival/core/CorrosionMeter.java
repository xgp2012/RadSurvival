package com.ft.radsurvival.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import com.ft.radsurvival.config.PluginConfig;

/**
 * 腐蚀条系统（plan.md §1.6）：内存态 {@code Map<UUID, Double>}（0~100，内部浮点）
 * + 纯计算内核 + 薄 Bukkit/PDC 适配层。
 *
 * <p>纯内核收敛在 {@link RadiationMath}（step / effectiveAccumRatePerSec /
 * corrosionMultiplier / stageOf / applyDeath），本类只负责按玩家保管数值、
 * 阶段迁移与回调分发——全部方法可直接 new 出来做无服务器单测。</p>
 *
 * <p>口径：</p>
 * <ul>
 *   <li>累积（仅露天）：{@code day-accum-per-sec × timeFactor}（夜晚/晨昏与 §1.3 伤害因子同口径
 *       插值，§1.6），{@code armor-affects-accum=true} 时再 ×(1−armorReduction)；</li>
 *   <li>恢复（遮蔽或豁免维度）：{@code − regen-per-sec}，固定速率，不乘 timeFactor、不受盔甲；</li>
 *   <li>结算只在调用发生时推进（{@code elapsedSeconds} 由 M2b 主循环传入），
 *       离线冻结由 M2b 的状态生命周期（quit 时 saveToPlayer + forget、join 时 loadFromPlayer）保证；</li>
 *   <li>阶段跳变仅走内存回调接口 {@link CorrosionStageListener}，<b>不创建 Bukkit Event</b>（M3 做）；</li>
 *   <li>持久化：Player PDC key {@code radsurvival:corrosion}（{@link PersistentDataType#DOUBLE}），
 *       读缺省 0.0（§2「随 player.dat 自动落盘」）。</li>
 * </ul>
 */
public final class CorrosionMeter {

    /** PDC 命名空间（§6：key 带 radsurvival: 前缀，读写收敛本类）。 */
    public static final String PDC_NAMESPACE = "radsurvival";
    /** PDC 键名；完整 key = {@code radsurvival:corrosion}（§1.6 持久化）。 */
    public static final String PDC_CORROSION_KEY = "corrosion";
    /** 完整 PDC key 字符串常量（单测钉死）。 */
    public static final String PDC_KEY_STRING = PDC_NAMESPACE + ":" + PDC_CORROSION_KEY;
    /** NamespacedKey（Bukkit PDC 读写用；纯字符串构造，无需运行中服务器即可引用）。 */
    public static final NamespacedKey CORROSION_PDC_KEY = new NamespacedKey(PDC_NAMESPACE, PDC_CORROSION_KEY);

    /** 阶段：正常（§1.6 stages 0=normal）。 */
    public static final int STAGE_NORMAL = 0;
    /** 阶段：警告（越过 stages.warn=50）。 */
    public static final int STAGE_WARN = 1;
    /** 阶段：危险（越过 stages.danger=75）。 */
    public static final int STAGE_DANGER = 2;

    /**
     * 阶段跳变监听（仅内存回调接口，非 Bukkit Event；M3 再包一层
     * api/CorrosionStageChangeEvent，§2/§4.8）。回调参数：玩家 UUID、旧阶段、新阶段。
     */
    @FunctionalInterface
    public interface CorrosionStageListener {
        void onStageChange(UUID playerId, int oldStage, int newStage);
    }

    /** 一次结算/写入的结果：新值、当前阶段、是否「首次到达 warn」（一次性播报标记，§1.6）。 */
    public record SettleResult(double value, int stage, boolean firstWarnNotice) {
    }

    private final PluginConfig.CorrosionConfig config;
    /** 权威内存态：玩家腐蚀值 [0,100]（§1.6）。 */
    private final Map<UUID, Double> values = new ConcurrentHashMap<>();
    /** 上一阶段（跨阈检测基线）。 */
    private final Map<UUID, Integer> lastStages = new ConcurrentHashMap<>();
    /** 首次 warn 一次性播报标记（内存态；forget 不清除——规则说明每“人次”只播一次，M3 消费）。 */
    private final Set<UUID> firstWarnSeen = ConcurrentHashMap.newKeySet();
    private final List<CorrosionStageListener> listeners = new CopyOnWriteArrayList<>();

    public CorrosionMeter(PluginConfig.CorrosionConfig config) {
        this.config = config;
    }

    /**
     * 显式清空内存中「本容器负责的玩家」权威值（reload / onDisable 交接用）。
     *
     * <p>{@link #forget} 是会话级（quit）；本方法是容器级：只清登录玩家，不影响同 JVM
     * 内其他容器实例（不同测试/重载实例互不干扰）。</p>
     *
     * @param playerIds 需要清空的玩家
     */
    public void forgetAll(java.util.Collection<UUID> playerIds) {
        for (UUID id : playerIds) {
            forget(id);
        }
    }

    public PluginConfig.CorrosionConfig config() {
        return config;
    }

    // ---------------------------------------------------------------- 监听器注册

    public void addStageListener(CorrosionStageListener listener) {
        listeners.add(java.util.Objects.requireNonNull(listener, "listener"));
    }

    /**
     * 摘除单个监听器（reload 重建反馈层时必须调用）：{@code /rad reload} 会整体重建服务容器，
     * 旧 {@code FeedbackService} 若是本容器唯一的监听者则整类丢弃；但同容器内若存在多个
     * 订阅者（或未来扩展），只 shutdown 不摘除会让旧实例继续收到阶段回调 → 旧实例的
     * Bukkit 事件处理器被重复广播、旧 HUD 计数继续增长（reload 泄漏）。
     */
    public void removeStageListener(CorrosionStageListener listener) {
        listeners.remove(listener);
    }

    public void clearStageListeners() {
        listeners.clear();
    }

    // ---------------------------------------------------------------- 内存态读写

    /** 当前腐蚀值；未知玩家缺省 0.0（§1.6，内部浮点，显示取整由 M3 反馈层处理）。 */
    public double get(UUID playerId) {
        return values.getOrDefault(playerId, RadiationMath.CORROSION_MIN);
    }

    /**
     * 内存是否已有该玩家的权威记录（M2b 追加，不改动已钉死口径）：
     * 用于 {@code RadSurvivalApi}/{@code RadiationTask} 区分「记录为 0」与「尚未登记（应回读 PDC）」。
     */
    public boolean hasRecorded(UUID playerId) {
        return values.containsKey(playerId);
    }

    /** 当前阶段（内存基线；未知玩家按 0/normal）。 */
    public int currentStage(UUID playerId) {
        return lastStages.getOrDefault(playerId, STAGE_NORMAL);
    }

    /** 直接设置腐蚀值（钳 [0,100]；admin `/rad corrosion set`，§1.6/§2 命令走本入口）。 */
    public SettleResult setValue(UUID playerId, double rawValue) {
        return apply(playerId, RadiationMath.clampCorrosion(rawValue));
    }

    /** 增量修改（钳 [0,100]；预留 §4.3「抗辐宁 = add(-n)」与命令 `add`）。 */
    public SettleResult addValue(UUID playerId, double delta) {
        return apply(playerId, RadiationMath.clampCorrosion(get(playerId) + delta));
    }

    /**
     * 主结算入口（M2b 主循环每 tick-interval 调用；elapsedSeconds 为距上次结算秒数）：
     * 露天 → 按 {@code dayAccum×timeFactor×(armorAffects? 1−reduction : 1)} 累积；
     * 遮蔽/豁免维度 → 按 {@code regen-per-sec} 恢复。跨阈检测与回调在 {@link #apply} 内完成。
     *
     * @param exposed        露天判定结果（§1.1；豁免维度由 M2b 传 false）
     * @param timeFactor     当前昼夜因子（§1.2 DayNightProvider 输出）
     * @param armorReduction 盔甲减免 [0,1]（§1.4 ArmorCalculator 输出；armor-affects-accum=false 时不参与累积）
     */
    public SettleResult settle(UUID playerId, boolean exposed, double timeFactor,
                               double armorReduction, double elapsedSeconds) {
        double accumRate = RadiationMath.effectiveAccumRatePerSec(
                config.dayAccumPerSec(), timeFactor, armorReduction, config.armorAffectsAccum());
        return apply(playerId, RadiationMath.step(get(playerId), elapsedSeconds,
                accumRate, config.regenPerSec(), exposed));
    }

    /**
     * 死亡处理（§1.6）：reset-on-death=true → 清零；否则 decay-on-death k → c×k
     * （互斥已由 M1 校验）。内存中无记录的玩家忽略。降级会触发阶段回调（如 danger→normal）。
     */
    public void onDeath(UUID playerId) {
        if (!values.containsKey(playerId)) {
            return;
        }
        apply(playerId, RadiationMath.applyDeath(get(playerId), config.resetOnDeath(), config.decayOnDeath()));
    }

    /**
     * 清理在线会话内存（M2b quit 时先 saveToPlayer 落盘、再 forget；PDC 值不丢，
     * 重进服由 {@link #loadFromPlayer} 恢复 → “离线冻结”口径）。
     * firstWarnSeen 保留（规则说明每人次仅播一次，直到插件重启）。
     */
    public void forget(UUID playerId) {
        values.remove(playerId);
        lastStages.remove(playerId);
    }

    // ---------------------------------------------------------------- 内核分发

    /** 写入新值并做阶段迁移检测：跨阈 → 回调全部监听器；首次到达 warn → SettleResult.firstWarnNotice。 */
    private SettleResult apply(UUID playerId, double newValue) {
        double clamped = RadiationMath.clampCorrosion(newValue);
        values.put(playerId, clamped);

        int oldStage = lastStages.getOrDefault(playerId, STAGE_NORMAL);
        int newStage = RadiationMath.stageOf(clamped, config.stageWarn(), config.stageDanger());
        lastStages.put(playerId, newStage);

        boolean firstWarn = false;
        if (newStage >= STAGE_WARN && firstWarnSeen.add(playerId)) {
            firstWarn = true; // add 返回 true = 首次，之后不再触发（一次性标记为内存态，§1.6）
        }
        if (oldStage != newStage) {
            for (CorrosionStageListener listener : listeners) {
                listener.onStageChange(playerId, oldStage, newStage);
            }
        }
        return new SettleResult(clamped, newStage, firstWarn);
    }

    // ------------------------------------------------------------ Bukkit/PDC 适配

    /**
     * 薄适配：从 Player PDC 读腐蚀值并初始化内存态（缺省 0.0；join 时调用）。
     * 若读回值已处于 warn 以上，顺手标记 firstWarnSeen（重进服不重复播规则说明）。
     * 返回恢复后的值。不做写入——落盘走 {@link #saveToPlayer}。
     */
    public double loadFromPlayer(Player player) {
        double stored = player.getPersistentDataContainer()
                .getOrDefault(CORROSION_PDC_KEY, PersistentDataType.DOUBLE, RadiationMath.CORROSION_MIN);
        double restored = RadiationMath.clampCorrosion(stored);
        UUID id = player.getUniqueId();
        values.put(id, restored);
        int stage = RadiationMath.stageOf(restored, config.stageWarn(), config.stageDanger());
        lastStages.put(id, stage);
        if (stage >= STAGE_WARN) {
            firstWarnSeen.add(id);
        }
        return restored;
    }

    /**
     * 薄适配：把内存值写入 Player PDC（key {@code radsurvival:corrosion}，DOUBLE；
     * §2：每秒结算时 setDouble 为内存写、随 player.dat 落盘，无 IO 压力）。
     * 内存无记录（尚未 join/已 forget）时不写，避免覆盖出假 0。
     */
    public void saveToPlayer(Player player) {
        Double value = values.get(player.getUniqueId());
        if (value != null) {
            player.getPersistentDataContainer().set(CORROSION_PDC_KEY, PersistentDataType.DOUBLE, value);
        }
    }
}
