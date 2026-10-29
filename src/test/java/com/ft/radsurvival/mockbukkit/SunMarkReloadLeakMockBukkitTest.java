package com.ft.radsurvival.mockbukkit;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.listener.SunRadiationListener;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * bug hunt P1-2 的回归护栏：reload 之后 SUN 标记表不得泄漏。
 *
 * <p>缺陷现场：{@code SunRadiationListener} 原本是 onEnable 的局部变量、只在首次装配时绑定给
 * FeedbackService。reload 新建的 FeedbackService 拿不到引用 → quit 时不再 {@code forget(id)}
 * → {@code marks} 只增不减。UUID 复用时旧标记可能把别人的 GENERIC 伤害认成太阳伤害
 * （从而跳过原版护甲还原）。
 *
 * <p>修复后：门控监听在 reload 时新建并重新绑定，旧实例靠
 * {@code plugin.sunRadiation() != this} 守卫彻底沉默。</p>
 */
class SunMarkReloadLeakMockBukkitTest {

    private ServerMock server;
    private RadSurvivalPlugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(RadSurvivalPlugin.class);
        player = server.addPlayer("Marked");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("reload 后门控监听被替换并绑定：新实例能记账，旧实例已沉默")
    void reloadRebindsSunListener() {
        SunRadiationListener first = plugin.sunRadiation();
        assertThat(first).as("onEnable 装配了门控监听").isNotNull();

        server.dispatchCommand(server.getConsoleSender(), "rad reload");

        SunRadiationListener second = plugin.sunRadiation();
        assertThat(second).as("reload 应新建门控监听").isNotNull().isNotSameAs(first);

        // —— 旧实例（仍在 HandlerList 里）记的账必须被丢弃
        first.recordSunDamage(player, 1.25);
        assertThat(first.trackedMarks())
                .as("旧实例已不是当前监听，不得再记账").isZero();

        // —— 新实例（当前监听）正常记账
        second.recordSunDamage(player, 1.25);
        assertThat(second.trackedMarks()).isEqualTo(1);
    }

    @Test
    @DisplayName("reload 后 quit 仍能清掉 SUN 标记（修复前新 FeedbackService 未绑定监听 → 标记残留）")
    void quitStillClearsMarksAfterReload() {
        // 先让当前监听记一笔，模拟刚发生一次太阳伤害
        plugin.sunRadiation().recordSunDamage(player, 1.25);
        assertThat(plugin.sunRadiation().trackedMarks()).isEqualTo(1);

        server.dispatchCommand(server.getConsoleSender(), "rad reload");

        plugin.sunRadiation().recordSunDamage(player, 1.25);
        assertThat(plugin.sunRadiation().trackedMarks()).isEqualTo(1);

        // 退服：FeedbackService.onQuit 应把这个标记清掉
        player.disconnect();
        assertThat(plugin.sunRadiation().trackedMarks())
                .as("quit 必须清掉 SUN 标记，否则 UUID 表无界增长")
                .isZero();
    }

    @Test
    @DisplayName("多次 reload 不累积残留标记")
    void repeatedReloadKeepsMarksBounded() {
        for (int i = 0; i < 5; i++) {
            plugin.sunRadiation().recordSunDamage(player, 1.5);
            server.dispatchCommand(server.getConsoleSender(), "rad reload");
            assertThat(plugin.sunRadiation().trackedMarks())
                    .as("第 %d 次 reload 后新实例应是干净的", i + 1)
                    .isZero();
        }
    }
}
