package com.ft.radsurvival.core;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抗尘埃药免疫窗口纯逻辑测试（v1.2 新增）。
 *
 * <p>注入毫秒时钟，精确断言窗口边界而不用 sleep。覆盖点：
 * 未登记不免疫、登记后免疫、到期即失效（左开右闭）、重复服用取较长、
 * 剩余秒数向上取整、clear/clearAll 语义、惰性清理不泄漏。</p>
 */
class DustImmunityTest {

    private static final UUID P = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    /** 可控时钟。 */
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final DustImmunity immunity = new DustImmunity(now::get);

    @Test
    @DisplayName("未登记 → 不免疫、剩余 0")
    void unregisteredIsNotImmune() {
        assertThat(immunity.isImmune(P)).isFalse();
        assertThat(immunity.remainingSeconds(P)).isZero();
        assertThat(immunity.tracked()).isZero();
    }

    @Test
    @DisplayName("登记 30s → 立刻免疫，剩余 30，29.9s 后仍免疫")
    void grantStartsWindow() {
        long end = immunity.grant(P, 30);
        assertThat(end).isEqualTo(now.get() + 30_000L);
        assertThat(immunity.isImmune(P)).isTrue();
        assertThat(immunity.remainingSeconds(P)).isEqualTo(30);

        now.addAndGet(29_900L); // 29.9s
        assertThat(immunity.isImmune(P)).isTrue();
        assertThat(immunity.remainingSeconds(P)).isEqualTo(1); // 0.1s 向上取整为 1
    }

    @Test
    @DisplayName("到期即失效：恰在第 30s 边界上不再免疫（保护宁可早失效）")
    void expiresExactlyAtDeadline() {
        immunity.grant(P, 30);
        now.addAndGet(30_000L);
        assertThat(immunity.isImmune(P)).isFalse();
        assertThat(immunity.remainingSeconds(P)).isZero();
        // 惰性清理：判定后条目被移除
        assertThat(immunity.tracked()).isZero();
    }

    @Test
    @DisplayName("重复服用取较长结束时刻：短时长不会覆盖长时长")
    void repeatedGrantKeepsLongerWindow() {
        immunity.grant(P, 30);
        long firstEnd = now.get() + 30_000L;
        now.addAndGet(5_000L); // 已过 5s
        long end = immunity.grant(P, 10); // 粗制药：10s → 结束于 now+10s，仍早于 firstEnd
        assertThat(end).isEqualTo(firstEnd);
        assertThat(immunity.remainingSeconds(P)).isEqualTo(25);
    }

    @Test
    @DisplayName("两次长时长叠加时取较晚者（后一次更长 → 延长）")
    void grantExtendsWindow() {
        immunity.grant(P, 10);
        now.addAndGet(1_000L);
        long end = immunity.grant(P, 30);
        assertThat(end).isEqualTo(now.get() + 30_000L);
        assertThat(immunity.remainingSeconds(P)).isEqualTo(30);
    }

    @Test
    @DisplayName("seconds ≤ 0 不登记（返回 0），不影响已有窗口")
    void nonPositiveSecondsIgnored() {
        assertThat(immunity.grant(P, 0)).isZero();
        assertThat(immunity.grant(P, -5)).isZero();
        assertThat(immunity.isImmune(P)).isFalse();

        immunity.grant(P, 20);
        immunity.grant(P, 0); // 不覆盖
        assertThat(immunity.remainingSeconds(P)).isEqualTo(20);
    }

    @Test
    @DisplayName("玩家间互不影响；clear 只清一个，clearAll 清全部")
    void isolationAndClearing() {
        immunity.grant(P, 30);
        immunity.grant(OTHER, 30);
        assertThat(immunity.tracked()).isEqualTo(2);

        immunity.clear(P);
        assertThat(immunity.isImmune(P)).isFalse();
        assertThat(immunity.isImmune(OTHER)).isTrue();

        immunity.clearAll();
        assertThat(immunity.isImmune(OTHER)).isFalse();
        assertThat(immunity.tracked()).isZero();
    }

    @Test
    @DisplayName("null 安全：null 玩家不免疫，clear(null) 不抛")
    void nullSafe() {
        assertThat(immunity.isImmune((UUID) null)).isFalse();
        assertThat(immunity.isImmune((org.bukkit.entity.Player) null)).isFalse();
        assertThat(immunity.grant(null, 30)).isZero();
        immunity.clear(null);
        assertThat(immunity.tracked()).isZero();
    }
}
