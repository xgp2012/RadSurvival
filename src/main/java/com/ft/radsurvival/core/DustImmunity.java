package com.ft.radsurvival.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.bukkit.entity.Player;

/**
 * 抗尘埃药免疫窗口（v1.2 新增）。
 *
 * <p>背景：v1.2 把「全套下界合金 = 完全免疫」这一捷径删掉了——护甲现在只能减伤、不能免伤。
 * 如果就此不给玩家任何免疫手段，玩法会退化成「躲起来」单一解。抗尘埃药补上这一环：
 * <b>有限时、有冷却、要加工</b>的免疫，而不是永久免伤。</p>
 *
 * <p><b>职责边界</b>：只回答「这名玩家此刻是否处于免疫窗口」以及窗口的登记/清除，
 * 不含任何 Bukkit 业务逻辑（{@code Player} 仅用于取 UUID）。掉血、腐蚀、事件广播仍全在
 * {@link RadiationTask} / {@link DamageApplier} 主线上。</p>
 *
 * <p><b>线程</b>：全部方法主线程调用（与结算循环同线程），内部用 {@code ConcurrentHashMap}
 * 仅为防御性——quit 清理与结算可能在同一 tick 的不同回调里发生。</p>
 *
 * <p><b>为什么不持久化</b>：免疫窗口是「短期战斗状态」，跨重启保留会造成「下线时喝药、
 * 上线仍免伤」的利用漏洞。quit 时无条件清除（见 {@code PlayerSessionListener}）。</p>
 */
public final class DustImmunity {

    /** 一次免疫登记：到期时间戳（毫秒，System.currentTimeMillis 口径）。 */
    private final Map<UUID, Long> untilMillis = new ConcurrentHashMap<>();

    private final LongSupplier clock;

    public DustImmunity() {
        this(System::currentTimeMillis);
    }

    /** 测试用：注入毫秒时钟（可精确断言窗口边界，无需 sleep）。 */
    public DustImmunity(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * 登记一段免疫窗口：从「此刻」起 {@code seconds} 秒。
     *
     * <p>重复服用取<b>较长</b>的结束时刻（而不是简单覆盖），避免短时长的粗制药把
     * 刚喝下的精制药效果顶掉——这对玩家是可预期的，也堵住「故意用低级药覆盖」的迷惑行为。</p>
     *
     * @param playerId 玩家
     * @param seconds  时长（≤0 视为不登记，返回 0）
     * @return 登记后的结束时间戳（毫秒）；未登记返回 0
     */
    public long grant(UUID playerId, int seconds) {
        if (playerId == null || seconds <= 0) {
            return 0L;
        }
        long end = clock.getAsLong() + seconds * 1000L;
        return untilMillis.merge(playerId, end, Math::max);
    }

    /**
     * 该玩家此刻是否免疫。
     *
     * <p>判定口径：{@code now < untilMillis}（左开右闭——到期的那一毫秒即失效，
     * 与本插件「到达即算」的阶段阈值口径保持区别，免疫是<b>保护</b>，宁可早失效不可晚失效）。</p>
     */
    public boolean isImmune(Player player) {
        return player != null && isImmune(player.getUniqueId());
    }

    public boolean isImmune(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        Long until = untilMillis.get(playerId);
        if (until == null) {
            return false;
        }
        if (clock.getAsLong() >= until) {
            untilMillis.remove(playerId, until); // 惰性清理，顺便避免 map 无限增长
            return false;
        }
        return true;
    }

    /** 剩余免疫秒数（向上取整到秒；未免疫返回 0）。 */
    public int remainingSeconds(UUID playerId) {
        if (!isImmune(playerId)) {
            return 0;
        }
        long remaining = untilMillis.getOrDefault(playerId, 0L) - clock.getAsLong();
        return (int) Math.ceil(Math.max(0L, remaining) / 1000.0);
    }

    /** 立即结束该玩家的免疫（quit / 死亡 / 测试）。 */
    public void clear(UUID playerId) {
        if (playerId != null) {
            untilMillis.remove(playerId);
        }
    }

    /** 清空全部（reload / onDisable 交接用：免疫窗口不跨容器保留，避免旧时长泄漏到新配置）。 */
    public void clearAll() {
        untilMillis.clear();
    }

    /** 当前登记人数（排障/测试读口）。 */
    public int tracked() {
        return untilMillis.size();
    }
}
