package com.ft.radsurvival.api;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataHolder;
import org.bukkit.persistence.PersistentDataType;

import com.ft.radsurvival.core.CorrosionMeter;
import com.ft.radsurvival.core.DamageApplier;
import com.ft.radsurvival.core.RadiationTask;

/**
 * 辐射暴露结算事件（plan.md §2 api/、§1.3 伤害标记主线、§4.8）。
 *
 * <p>主循环在<b>主线程、腐蚀步进之后、实际掉血之前</b>广播本事件
 * （{@link RadiationTask} → {@link DamageApplier}）。外部插件可以：</p>
 * <ul>
 *   <li>{@link #setCancelled(boolean)} 取消本次辐射结算 → 本轮<b>不掉血</b>
 *       （但腐蚀累积/恢复已发生，口径见 {@link RadiationTask} javadoc）；</li>
 *   <li>{@link #setFinalDamage(double)} 覆写最终伤害（改伤入口，§4.8「可改伤害」）；
 *       事件未被取消且覆写后 {@code finalDamage > 0} 时才走
 *       {@code player.damage(...)} 稳定 API 结算。</li>
 * </ul>
 *
 * <p><b>伤害来源标记</b>（§1.3/§6）：插件自身的 LOWEST 优先级监听器
 * （{@code listener/RadiationMarkListener}）在本事件 PDC 上写
 * {@code radsurvival:source=radiation}，其他插件在 NORMAL 及之后优先级监听本事件即可
 * 识别伤害来源——标记只存在于事件（瞬态），不污染玩家 PDC。
 * Paper {@code CustomDamageMeta}（@UnstableApi）开关本期不做，见 README「CustomDamageMeta 延后」。</p>
 *
 * <p>字段口径：{@code corrosion} 为<b>本轮腐蚀步进后、伤害结算前</b>的值——与
 * {@code finalDamage} 使用的腐蚀倍率基数严格一致（先累后打，§1.3/§1.6；覆写只影响
 * 最终伤害数值）；{@code exposed} 为 §1.1 露天判定（豁免维度恒 false）；
 * {@code timeFactor} 为 §1.2 DayNightProvider 输出；{@code armorReduction} 为
 * §1.4 ArmorCalculator 输出。</p>
 *
 * <p>PDC：本事件不依赖 CraftBukkit 内部实现（API 里无 SimplePersistentDataContainer，
 * 已 jar 核实），自带极简内存实现 {@link EventPersistentDataContainer}——瞬态、不序列化。</p>
 */
public final class RadiationExposureEvent extends Event implements Cancellable, PersistentDataHolder {

    private static final HandlerList HANDLERS = new HandlerList();

    /** PDC 命名空间（与 {@link CorrosionMeter#PDC_NAMESPACE} 同口径，§6）。 */
    public static final String PDC_NAMESPACE = CorrosionMeter.PDC_NAMESPACE;
    /** 伤害来源标记 key：{@code radsurvival:source}（§1.3）。 */
    public static final NamespacedKey SOURCE_PDC_KEY = new NamespacedKey(PDC_NAMESPACE, "source");
    /** 伤害来源标记值：{@code radiation}（§1.3，外部插件按此识别辐射伤害）。 */
    public static final String SOURCE_PDC_VALUE = "radiation";

    private final Player player;
    private final boolean exposed;
    private final double timeFactor;
    private final double armorReduction;
    private final double corrosion;
    private double finalDamage;
    private boolean cancelled;
    private final DamageApplier.DamageChannel damageChannel;
    private final EventPersistentDataContainer pdc = new EventPersistentDataContainer();

    /**
     * 由 {@link DamageApplier} 在主线程构造并广播；不要求公开构造。
     *
     * @param player         受辐射玩家
     * @param exposed        §1.1 露天判定结果
     * @param timeFactor     §1.2 昼夜伤害因子
     * @param armorReduction §1.4 盔甲减免 [0,1]
     * @param corrosion      本轮腐蚀值（腐蚀步进后 [0,100]，§1.6）
     * @param finalDamage    §1.3 公式算出的最终伤害（可被监听器覆写）
     */
    public RadiationExposureEvent(Player player, boolean exposed, double timeFactor,
                                  double armorReduction, double corrosion, double finalDamage) {
        this(player, exposed, timeFactor, armorReduction, corrosion, finalDamage,
                DamageApplier.DamageChannel.SUN);
    }

    /**
     * 带伤害通道的构造（判定链路强化）：{@code damageChannel == SUN} 表示这一笔伤害由本插件的
     * 辐射结算产出；任何其他来源（第三方插件、原版机制直接穿透伤害）在后续
     * {@code EntityDamageEvent} 上是 {@code EXTERNAL}，<b>不套用腐蚀倍率与盔甲节点减免</b>。
     *
     * @param damageChannel 伤害通道（非空；null 按 EXTERNAL 处理，宁可放过不可错杀）
     */
    public RadiationExposureEvent(Player player, boolean exposed, double timeFactor,
                                  double armorReduction, double corrosion, double finalDamage,
                                  DamageApplier.DamageChannel damageChannel) {
        this.player = player;
        this.exposed = exposed;
        this.timeFactor = timeFactor;
        this.armorReduction = armorReduction;
        this.corrosion = corrosion;
        this.finalDamage = finalDamage;
        this.damageChannel = damageChannel == null ? DamageApplier.DamageChannel.EXTERNAL : damageChannel;
    }

    // ------------------------------------------------------------------ 只读字段

    public Player getPlayer() {
        return player;
    }

    /** 露天判定（§1.1；豁免维度恒 false）。 */
    public boolean isExposed() {
        return exposed;
    }

    /** 昼夜伤害因子（§1.2：白天 1.0、夜晚 night-multiplier、晨昏插值/硬切换）。 */
    public double getTimeFactor() {
        return timeFactor;
    }

    /** 盔甲减免 [0,1]（§1.4；达到 1.0 时主循环已短路，不会广播本事件）。 */
    public double getArmorReduction() {
        return armorReduction;
    }

    /** 本轮腐蚀值（腐蚀步进后、伤害结算前，与 finalDamage 倍率基数一致，§1.6）。 */
    public double getCorrosion() {
        return corrosion;
    }

    /**
     * 伤害通道（判定链路强化）：{@link DamageApplier.DamageChannel#SUN} = 本插件结算的太阳辐射
     * 伤害；{@link DamageApplier.DamageChannel#EXTERNAL} = 其他来源。外部插件若要「只对太阳辐射
     * 生效/只在太阳辐射时取消」，应以本字段判定，不要以 {@code DamageType} 判定——本插件与
     * {@code /damage ... generic} 共用 GENERIC 类型，类型无法区分来源。
     */
    public DamageApplier.DamageChannel getDamageChannel() {
        return damageChannel;
    }

    /** 伤害通道的稳定字符串标识（{@code "SUN"} / {@code "EXTERNAL"}，跨插件安全）。 */
    public String getDamageChannelId() {
        return damageChannel.id();
    }

    // ------------------------------------------------------------------ 可写字段

    /** §1.3 公式输出的最终伤害（尚未取整；{@code <= 0} 或事件被取消时不结算）。 */
    public double getFinalDamage() {
        return finalDamage;
    }

    /** 覆写最终伤害（§4.8「可改伤害」入口）。允许设为 0（等效免伤）；负值按 0 处理（不产生回血）。 */
    public void setFinalDamage(double finalDamage) {
        this.finalDamage = finalDamage;
    }

    // ---------------------------------------------------------------- Cancellable

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    // -------------------------------------------------------------------- Handler

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    // ------------------------------------------------------------------------ PDC

    /** 事件瞬态 PDC（伤害来源标记落点，§1.3；不落盘、不序列化）。 */
    @Override
    public PersistentDataContainer getPersistentDataContainer() {
        return pdc;
    }

    /**
     * 极简内存 PDC（事件专用瞬态容器）。
     *
     * <p>paper-api 1.21.11 不含 {@code SimplePersistentDataContainer}（org.bukkit.persistence
     * 包 jar 核实），故自实现 {@link PersistentDataContainer} 全部抽象方法。
     * 存储 = 键 → (类型, 原始值)；{@link #serializeToBytes}/{@link #readFromBytes} 不支持
     * （瞬态容器无落盘语义）；{@link #copyTo} 对 toPrimitive/fromPrimitive 为恒等映射的常用
     * 类型（STRING/DOUBLE/整数系）行为正确，BOOLEAN 等需转换的类型仅尽力而为。</p>
     */
    static final class EventPersistentDataContainer implements PersistentDataContainer {

        private record Entry(PersistentDataType<?, ?> type, Object primitive) {
        }

        private final Map<NamespacedKey, Entry> entries = new LinkedHashMap<>();

        private static final PersistentDataAdapterContext ADAPTER = EventPersistentDataContainer::new;

        @Override
        public <P, C> void set(NamespacedKey key, PersistentDataType<P, C> type, C value) {
            entries.put(key, new Entry(type, type.toPrimitive(value, ADAPTER)));
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public <P, C> C get(NamespacedKey key, PersistentDataType<P, C> type) {
            Entry entry = entries.get(key);
            if (entry == null || entry.primitive() == null) {
                return null;
            }
            if (entry.type().getPrimitiveType() != type.getPrimitiveType()) {
                throw new ClassCastException("PDC key " + key + " 存储原始类型 "
                        + entry.type().getPrimitiveType().getSimpleName()
                        + " ≠ 请求类型 " + type.getPrimitiveType().getSimpleName());
            }
            return (C) ((PersistentDataType) type).fromPrimitive(entry.primitive(), ADAPTER);
        }

        @Override
        public <P, C> C getOrDefault(NamespacedKey key, PersistentDataType<P, C> type, C defaultValue) {
            C value = get(key, type);
            return value != null ? value : defaultValue;
        }

        @Override
        public <P, C> boolean has(NamespacedKey key, PersistentDataType<P, C> type) {
            Entry entry = entries.get(key);
            return entry != null && entry.type().getPrimitiveType() == type.getPrimitiveType();
        }

        @Override
        public boolean has(NamespacedKey key) {
            return entries.containsKey(key);
        }

        @Override
        public Set<NamespacedKey> getKeys() {
            return new LinkedHashSet<>(entries.keySet());
        }

        @Override
        public boolean isEmpty() {
            return entries.isEmpty();
        }

        @Override
        public int getSize() {
            return entries.size();
        }

        @Override
        public void remove(NamespacedKey key) {
            entries.remove(key);
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void copyTo(PersistentDataContainer other, boolean overwrite) {
            for (Map.Entry<NamespacedKey, Entry> e : entries.entrySet()) {
                if (!overwrite && other.has(e.getKey())) {
                    continue;
                }
                Entry src = e.getValue();
                // 原始值 → 复杂值 → 对方 set（对恒等映射类型无损；转换型类型尽力而为，见类注释）
                Object complex = ((PersistentDataType) src.type()).fromPrimitive(src.primitive(), ADAPTER);
                ((PersistentDataContainer) other).set(e.getKey(), (PersistentDataType) src.type(), complex);
            }
        }

        @Override
        public PersistentDataAdapterContext getAdapterContext() {
            return ADAPTER;
        }

        @Override
        public byte[] serializeToBytes() throws IOException {
            throw new IOException("RadiationExposureEvent 的瞬态 PDC 不支持序列化（不落盘，§1.3）");
        }

        @Override
        public void readFromBytes(byte[] bytes, boolean clear) throws IOException {
            throw new IOException("RadiationExposureEvent 的瞬态 PDC 不支持反序列化（不落盘，§1.3）");
        }
    }
}
