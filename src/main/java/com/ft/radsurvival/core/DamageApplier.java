package com.ft.radsurvival.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.api.RadiationExposureEvent;

/**
 * 伤害结算器（plan.md §2 core/DamageApplier.java、§1.3 伤害主线）。
 *
 * <p>唯一职责：把 {@link RadiationTask} 算出的 {@code finalDamage} 包装成
 * {@link RadiationExposureEvent} 广播，事件未被取消且（可能被监听器覆写后的）
 * 伤害 {@code > 0} 时调用 Bukkit 稳定伤害 API 真实结算掉血。</p>
 *
 * <p><b>伤害 API 适配说明（1.21.11，jar 核实）</b>：父任务口径引用的
 * {@code damage(amount, DamageCause.CUSTOM)} 重载在 paper-api 1.21.11 中<b>已被移除</b>
 * （{@code Damageable} 仅剩 {@code damage(double)} / {@code damage(double, Entity)} /
 * {@code damage(double, DamageSource)}；顶层 {@code DamageCause} 枚举也收敛为
 * {@code EntityDamageEvent.DamageCause} 嵌套形态）。本类据此改用同为<b>稳定 API</b>
 * （非 @UnstableApi）的 {@code damage(double, DamageSource)} +
 * {@code DamageType.GENERIC}：无归因外部伤害，语义最接近旧 CUSTOM——不携带实体/弹射物、
 * 不触发战斗逻辑。</p>
 *
 * <p><b>判定链路强化（本轮修复）</b>：{@code GENERIC} 仍会经过原版护甲减伤
 * （验收 §C.1 的「10 点 → 恰好 10.0」是当时盔甲槽恰为空的巧合，不是 GENERIC 绕过护甲的证据）。
 * 带甲时 §1.3 的腐蚀倍率与盔甲模型会被原版再乘一遍（= 双重减免）。故生产执行器
 * {@link #ARMOR_NEUTRALIZING_EXECUTOR} 在 {@code damage()} 的同步栈内摘下盔甲槽、调用后原样放回；
 * 外部伤害一侧由 {@code listener/SunRadiationListener} 还原原版减免，两者合起来满足
 * 「太阳伤害不吃原版护甲、别的伤害照常吃护甲」。</p>
 *
 * <p><b>取整口径</b>：不做任何取整/向上取整，直接把 §1.3 公式的浮点原值传入
 * {@code damage(double, ...)}（默认参数下半心 = 1.0 点，M4 无敌帧断言按每轮掉血 ≥1 点核对）。</p>
 *
 * <p>免疫短路（减免 ≥ 1.0 不广播事件、不掉血、不播反馈）与「先腐蚀后伤害」的顺序约束
 * 由 {@link RadiationTask} 保证，本类只在需要真实结算时被调用。</p>
 */
public final class DamageApplier {

    /**
     * 辐射伤害的 DamageSource 工厂：GENERIC（无归因外部伤害）。每次调用新建——
     * Bukkit 事件系统按源对象做死亡播报归属（M3 死亡文案再细化），不复用可变实例。
     */
    static DamageSource radiationDamageSource() {
        return DamageSource.builder(DamageType.GENERIC).build();
    }

    /** 太阳辐射伤害通道的稳定标识（{@link RadiationExposureEvent#getDamageChannel()}，
     *  供外部插件按字符串识别而不必依赖本类枚举）。 */
    public static final String CHANNEL_SUN = "SUN";

    /**
     * 伤害通道（判定链路强化，修复「盔甲把别的伤害也吃了」）：
     *
     * <p>本插件的<b>全部</b>伤害都经 {@code damage(double, DamageSource(GENERIC))} 落地，
     * 而 Bukkit 无法在 DamageSource 上附加标签——若直接用 {@code getDamageType()==GENERIC}
     * 判定「某次伤害是否来自本插件」，会把 {@code /damage <p> 10 minecraft:generic}、
     * {@code world.strikeLightning()} 等第三方 GENERIC 伤害一并认作太阳辐射，
     * 从而套用腐蚀倍率/节点配额/盔甲减免，把 10 点雷击减成 0 点。</p>
     *
     * <p>故以「结算窗口 + 下界合金节点的下一 tick 窗口」双判据识别自己刚造成的那一笔
     * （{@link SunRadiationListener}）。{@link #SUN} 是本插件产出的伤害，
     * {@link #EXTERNAL} 是任何其他来源（第三方插件/原版机制）。</p>
     */
    public enum DamageChannel {
        /** 太阳辐射结算（{@link DamageApplier#apply} 产出的那一笔）。 */
        SUN(CHANNEL_SUN),
        /** 非本插件结算的外部伤害（含第三方 GENERIC 伤害）。 */
        EXTERNAL("EXTERNAL");

        private final String id;

        DamageChannel(String id) {
            this.id = id;
        }

        /** 稳定标识字符串（事件字段口径）。 */
        public String id() {
            return id;
        }
    }

    /**
     * 实际掉血注入缝（测试点）：生产默认 = 稳定 API
     * {@code player.damage(amount, DamageSource(GENERIC))}；MockBukkit 4.116.3 的
     * {@code LivingEntityMock.damage(double, DamageSource)} 未实现（实测
     * UnimplementedOperationException），测试注入假执行器记录调用/手工扣血——
     * 事件广播、取消与覆写链路仍走同一生产代码，真实无敌帧行为按 §5.2 移交 M4 实机。
     */
    @FunctionalInterface
    public interface DamageExecutor {
        void damage(Player player, double amount);
    }

    /** 结算瞬间被摘下的盔甲快照（正常情况下在同一同步栈内立即恢复）。 */
    private static final Map<UUID, ItemStack[]> armorSnapshot = new ConcurrentHashMap<>();

    /**
     * 生产执行器（判定链路强化）：把 §1.3 公式的浮点原值交给稳定 API，同时保证<b>不被原版二次减免</b>。
     *
     * <p><b>为什么要在调用瞬间摘掉盔甲槽</b>：Bukkit 没有「物理伤害」这类绕过原版护甲的稳定入口
     * （{@code DamageType.GENERIC} 同样走护甲减伤——验收 §C.1 的「10 点 → 恰好 10.0」是当时
     * 盔甲槽恰好为空的巧合，不是 GENERIC 绕过护甲的证据）。带甲时 §1.3 的腐蚀倍率与盔甲模型
     * 会被原版再乘一遍。故在 {@code damage()} 的同步栈内先把四件盔甲从装备槽取出、调用后原样
     * 放回：护甲为 0 → 原版防御恒 0；物品只在同一 tick 内短暂离开槽位，背包内容不变。</p>
     *
     * <p>摘下的盔甲存在 {@link #armorSnapshot}，由同一同步栈的 finally 立即还原；
     * 服务器关停时 {@link #restoreSuspendedArmorAll()} 再兜底一次（幂等）。</p>
     */
    public static final DamageExecutor ARMOR_NEUTRALIZING_EXECUTOR = (player, amount) -> {
        ItemStack[] snapshot = player.getInventory().getArmorContents();
        boolean suspend = hasAnyArmor(snapshot);
        if (suspend) {
            player.getInventory().setArmorContents(new ItemStack[4]);
            armorSnapshot.put(player.getUniqueId(), snapshot);
        }
        try {
            player.damage(amount, radiationDamageSource());
        } finally {
            if (suspend) {
                restoreArmor(player, snapshot);
            }
        }
    };

    /** 生产默认执行器（= 摘甲执行器，见 {@link #ARMOR_NEUTRALIZING_EXECUTOR}）。 */
    public static final DamageExecutor DEFAULT_EXECUTOR = ARMOR_NEUTRALIZING_EXECUTOR;

    /** 关停兜底：把仍处于「摘下」状态的玩家盔甲放回（异常/crash 路径）。 */
    public static void restoreSuspendedArmorAll() {
        for (Map.Entry<UUID, ItemStack[]> entry : armorSnapshot.entrySet()) {
            try {
                Player online = Bukkit.getPlayer(entry.getKey());
                if (online != null) {
                    restoreArmor(online, entry.getValue());
                }
            } catch (RuntimeException ex) {
                Logger.getLogger("com.ft.radsurvival.core")
                        .warning("盔甲还原失败（请让该玩家重新登录以恢复）: " + ex);
            }
        }
        armorSnapshot.clear();
    }

    private static boolean hasAnyArmor(ItemStack[] contents) {
        if (contents == null) {
            return false;
        }
        for (ItemStack stack : contents) {
            if (stack != null && stack.getType() != Material.AIR) {
                return true;
            }
        }
        return false;
    }

    private static void restoreArmor(Player player, ItemStack[] snapshot) {
        armorSnapshot.remove(player.getUniqueId());
        player.getInventory().setArmorContents(snapshot);
    }

    /**
     * 受击反馈挂点（M3，plan.md §1.5「受击时轻微粒子 + 低功耗音效」+ 死亡文案归因线索）：
     * 仅在<b>事件未取消、覆写后伤害 &gt; 0 且 damageExecutor 实际执行之后</b>回调一次。
     * 反馈实现方（feedback/FeedbackService）在此挂粒子/音效/掉血归因记录；
     * 挂点抛出的任何运行时异常都被捕获并记日志，<b>绝不中断结算主线</b>（M2b 单玩家隔离原则）。
     */
    @FunctionalInterface
    public interface DamageFeedbackHook {
        void onRadiationDamage(Player player, double amount);
    }

    private final PluginManager pluginManager;
    private final DamageExecutor damageExecutor;
    /** M3 受击反馈挂点；volatile：装配/重建与结算读并发可见。 */
    private volatile DamageFeedbackHook damageFeedbackHook;

    /** 设置受击反馈挂点（M3 装配；传 null 摘除）。 */
    public void setDamageFeedbackHook(DamageFeedbackHook hook) {
        this.damageFeedbackHook = hook;
    }

    public DamageApplier(RadSurvivalPlugin plugin) {
        this(plugin.getServer().getPluginManager());
    }

    /** 测试友好构造：直接注入 PluginManager（MockBukkit 提供），执行器用生产默认。 */
    public DamageApplier(PluginManager pluginManager) {
        this(pluginManager, DEFAULT_EXECUTOR);
    }

    /** 全参构造：注入假 DamageExecutor（仅测试/M4 前调试用；生产走 {@link #DEFAULT_EXECUTOR}）。 */
    public DamageApplier(PluginManager pluginManager, DamageExecutor damageExecutor) {
        this.pluginManager = pluginManager;
        this.damageExecutor = damageExecutor;
    }

    /**
     * 广播 {@link RadiationExposureEvent} 并按覆写后的 finalDamage 结算掉血。
     *
     * @param player         受罚玩家
     * @param exposed        露天判定（主循环保证 true 才会调用本方法）
     * @param timeFactor     §1.2 时间因子
     * @param armorReduction §1.4 盔甲减免
     * @param corrosion      本轮腐蚀值（腐蚀步进后、伤害结算前，§1.6）——
     *                       与 finalDamage 所用倍率基数一致，事件按此原样携带
     * @param finalDamage    §1.3 公式伤害（&gt;0）
     * @return 实际造成的伤害点数：0 = 事件被取消或覆写为 ≤0；否则为覆写后的伤害值
     *         （供 {@link RadiationTask} 记录 {@code PlayerRadState#lastDamage} 与
     *         /rad force-check 报告；「damage() 已调用」与「掉血真实发生」在 M4 实机
     *         按无敌帧口径复核，见类 javadoc）
     */
    public double apply(Player player, boolean exposed, double timeFactor, double armorReduction,
                        double corrosion, double finalDamage) {
        RadiationExposureEvent event = new RadiationExposureEvent(
                player, exposed, timeFactor, armorReduction, corrosion, finalDamage,
                DamageChannel.SUN);
        pluginManager.callEvent(event);
        if (event.isCancelled()) {
            return 0.0;
        }
        double amount = event.getFinalDamage();
        if (amount <= 0.0) {
            return 0.0;
        }
        // 稳定 API 伤害主线（§1.3）；来源标记已由 LOWEST 监听写在事件 PDC（radsurvival:source=radiation）。
        // damageExecutor 的 finally 已保证摘下的盔甲在返回前还原；此处再兜一次，防自定义执行器漏还原。
        try {
            damageExecutor.damage(player, amount);
        } finally {
            if (armorSnapshot.containsKey(player.getUniqueId())) {
                restoreArmor(player, armorSnapshot.get(player.getUniqueId()));
            }
        }
        // —— M3 受击反馈挂点（§1.5）：异常隔离，绝不中断结算（M2b 单玩家 try/catch 原则同款）
        DamageFeedbackHook hook = damageFeedbackHook;
        if (hook != null) {
            try {
                hook.onRadiationDamage(player, amount);
            } catch (RuntimeException | Error ex) {
                Logger.getLogger("com.ft.radsurvival.feedback")
                        .warning("受击反馈挂点异常（已忽略，不影响结算）: " + ex);
            }
        }
        return amount;
    }
}
