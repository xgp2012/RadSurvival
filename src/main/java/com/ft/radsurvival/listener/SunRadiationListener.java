package com.ft.radsurvival.listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.core.RadiationServices;

/**
 * 太阳辐射伤害门控 / 原版防御还原监听（判定链路强化）。
 *
 * <p><b>要解决的缺陷</b>：Bukkit 的伤害入口只有 {@code damage(double, DamageSource)} 一个稳定形态
 * （1.21.11 已移除 {@code damage(double, DamageCause)}），本插件与
 * {@code /damage <p> 10 minecraft:generic}、{@code world.strikeLightning()} 等第三方伤害
 * <b>共用 GENERIC → DamageCause.CUSTOM</b>——仅凭伤害类型无法区分来源。</p>
 *
 * <p>而 §1.3「不双重减免」要求本插件结算时把原版盔甲点数清零（腐蚀倍率公式里已含盔甲模型）。
 * 于是服务器上<b>所有</b>第三方伤害都会落在「原版防御 = 0」的窗口里：雷击 10 点在全套下界合金
 * 玩家身上结算为 0 点（全套盔甲挡了一次雷）。盔甲越厚，外部伤害被吞得越多，且没有任何提示。</p>
 *
 * <p><b>双判据识别「本插件那一笔」</b>（{@link #classify}，宁可放过不可错杀）：</p>
 * <ol>
 *   <li>{@link DamageCause#CUSTOM}——{@code DamageSource.builder(GENERIC)} 落地即 CUSTOM；</li>
 *   <li>时间窗：距本插件最近一次 SUN 通道掉血 &lt;= {@link #SUN_WINDOW_MS}
 *       （{@code damage()} 与 {@code EntityDamageEvent} 为同一同步栈）；</li>
 *   <li>数值吻合：事件伤害 == 本插件应造成的那一笔（±{@link #AMOUNT_EPS}）；</li>
 *   <li>反伤/荆棘（伤害者即受害者本人）一律按外部伤害处理——那是玩家盔甲的原版反击。</li>
 * </ol>
 *
 * <p><b>外部伤害</b>：按「本应生效的原版减免 × 被清零的盔甲点数」写入
 * {@code armor-damage-reduction} 属性，让原版护甲/保护附魔照常生效，读后立即还原。
 * 绝不写满 {@code 1.0}：该属性是<b>乘性</b>的，护甲为 0 时会反过来放大伤害。</p>
 *
 * <p>严格 LOWEST + {@code ignoreCancelled=false}：其他插件在更高优先级看到的就是最终值；
 * 本监听器不取消事件。全部逻辑 try/catch 隔离，异常即按原值放行，绝不阻断伤害管线。</p>
 */
public final class SunRadiationListener implements Listener {

    /** 本插件那一笔伤害的识别窗口（毫秒）：damage() → EntityDamageEvent 为同栈同步调用。 */
    public static final long SUN_WINDOW_MS = 100L;
    /** 数值吻合容差（double 往返误差）。 */
    public static final double AMOUNT_EPS = 1e-6;

    /** 一次「本插件刚造成的伤害」标记。 */
    record SunMark(long atMillis, double amount) {
    }

    private final RadSurvivalPlugin plugin;
    private final LongSupplier clock;
    /** 每名玩家最近一次 SUN 通道掉血（窗口判据）；quit 时清理。 */
    private final Map<UUID, SunMark> marks = new ConcurrentHashMap<>();

    public SunRadiationListener(RadSurvivalPlugin plugin) {
        this(plugin, System::currentTimeMillis);
    }

    /** 测试用：注入毫秒时钟。 */
    public SunRadiationListener(RadSurvivalPlugin plugin, LongSupplier clock) {
        this.plugin = plugin;
        this.clock = clock;
    }

    // ------------------------------------------------------ 记账（DamageApplier 挂点）

    /** 登记「本插件刚造成一笔 SUN 伤害」（在 {@code damage()} 之后同步调用）。 */
    public void recordSunDamage(Player player, double amount) {
        // bug hunt P1-2：reload 后本实例已不是当前门控监听，不得再往自己的 marks 表里记。
        // 旧实例注册在 HandlerList 里无法按实例注销，只能靠这个守卫让它彻底沉默；
        // 否则 marks 只增不减（quit 清理走的是**新**实例），UUID 复用时会误判他人 GENERIC 伤害。
        if (plugin.sunRadiation() != this) {
            return;
        }
        marks.put(player.getUniqueId(), new SunMark(clock.getAsLong(), amount));
    }

    /** 清除标记（quit / 测试）。 */
    public void forget(UUID playerId) {
        marks.remove(playerId);
    }

    /** 当前标记数（排障/测试读口）。 */
    public int trackedMarks() {
        return marks.size();
    }

    /** 指定玩家的标记（测试读口；无则 null）。 */
    SunMark markOf(UUID playerId) {
        return marks.get(playerId);
    }

    // ------------------------------------------------------------ 归因纯函数（可单测）

    /**
     * 判定纯内核（单测钉死）：该次 {@link EntityDamageEvent} 是否为本插件的太阳辐射结算。
     *
     * @param cause     事件 DamageCause
     * @param victimId  受害者 UUID
     * @param damagerId 事件伤害源里的「造成者」UUID（绝大多数外部伤害为 null）
     * @param amount    事件伤害值
     * @param mark      该玩家最近一次 SUN 标记（无则 null）
     * @param nowMillis 当前毫秒时刻
     */
    public static boolean classify(DamageCause cause, UUID victimId, UUID damagerId, double amount,
                                   SunMark mark, long nowMillis) {
        if (cause != DamageCause.CUSTOM) {
            return false; // mob_attack / magic / explosion / … 与太阳辐射无关
        }
        if (damagerId != null && damagerId.equals(victimId)) {
            return false; // 荆棘/反伤：玩家盔甲触发的原版反击，不是阳光
        }
        if (mark == null) {
            return false;
        }
        long age = nowMillis - mark.atMillis();
        if (age < 0 || age > SUN_WINDOW_MS) {
            return false; // 超窗 = 外部 CUSTOM 伤害（/damage generic、第三方插件）
        }
        return Math.abs(amount - mark.amount()) <= AMOUNT_EPS;
    }

    /**
     * 给定护甲点数时，原版应在该伤害上减免的比例：
     * {@code min(20, max(armor − damage/2, armor × damage/5)) / 25}。
     * 与 Paper 1.21.x {@code CombatRules.getDamageAfterAbsorb} 同口径（护甲 0 时恒 0）。
     */
    public static double vanillaArmorReduction(double armor, double damage) {
        double a = Math.max(0.0, Math.min(30.0, armor));
        double d = Math.max(0.0, damage);
        double absorbed = Math.min(20.0, Math.max(a - d / 2.0, a * d / 5.0));
        return absorbed / 25.0;
    }

    /** 1.21.11 口径：每 1 点护甲 = 4% 减伤（20 点 = 80%），随伤害衰减（见 {@link #vanillaArmorReduction}）。 */
    public static final double VANILLA_ARMOR_POINTS_PER_PERCENT = 0.05;

    // ------------------------------------------------------------------ 事件主路径

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityDamage(EntityDamageEvent event) {
        try {
            // bug hunt P1-2：reload 后旧实例仍在 HandlerList 里，但它既不认识新的 SUN 标记，
            // 也不该再改写伤害（否则同一笔外部伤害会被新旧两个实例各还原一次原版减免）。
            if (plugin.sunRadiation() != this) {
                return;
            }
            if (!(event.getEntity() instanceof Player player)) {
                return;
            }
            RadiationServices services = plugin.services();
            if (services == null) {
                return; // 未启用
            }
            if (isSunRadiation(event, player.getUniqueId())) {
                return; // 本插件那一笔：数值已含腐蚀倍率与盔甲模型，不再改动
            }
            restoreVanillaDefense(player, event);
        } catch (RuntimeException | Error ex) {
            // 归因层异常绝不阻断伤害管线：按原值放行
            plugin.getLogger().warning("外部伤害判定异常（该次伤害按原值放行）: " + ex);
        }
    }

    private boolean isSunRadiation(EntityDamageEvent event, UUID id) {
        Entity damager = event.getDamageSource().getCausingEntity();
        UUID damagerId = damager == null ? null : damager.getUniqueId();
        return classify(event.getCause(), id, damagerId, event.getDamage(),
                marks.get(id), clock.getAsLong());
    }

    /**
     * 外部伤害：解锁原版护甲/保护附魔（本插件结算期间清空的那些点数）。
     *
     * <p>本插件在 {@code damage()} 的同步栈内把盔甲槽摘下（见
     * {@code DamageApplier#ARMOR_NEUTRALIZING_EXECUTOR}），因此外部伤害到来时原版防御恒为 0。
     * 这里按「原版本应生效的减免比例」把等价减伤<b>直接作用在事件伤害上</b>：
     * 先让事件数值还原为原版衰减后的值，再由原版管线（护甲为 0）原样落地。</p>
     *
     * <p>不用 {@code armor-damage-reduction} 属性：1.21.11 的 Bukkit API 没有该常量
     * （jar 核实，只有 ARMOR / ARMOR_TOUGHNESS），且它是产出于护甲点数的派生属性，
     * 直接改写会带来乘性放大风险。此处只用原版可考的护甲公式，结果可预期、可单测。</p>
     */
    private void restoreVanillaDefense(Player player, EntityDamageEvent event) {
        double armorPoints = armorPointsOf(player);
        if (armorPoints <= 0.0) {
            return; // 裸装：没有需要还原的减免
        }
        double reduction = vanillaArmorReduction(armorPoints, event.getDamage());
        if (reduction <= 0.0) {
            return; // 原版公式对小伤害的减免可为 0
        }
        event.setDamage(Math.max(0.0, event.getDamage() * (1.0 - reduction)));
    }

    /** 玩家当前身上（含插件未接管场景）的原版护甲点数；未装配该属性时按 0 处理。 */
    static double armorPointsOf(Player player) {
        AttributeInstance instance = player.getAttribute(Attribute.ARMOR);
        return instance == null ? 0.0 : Math.max(0.0, instance.getValue());
    }
}
