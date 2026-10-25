package com.ft.radsurvival.mockbukkit;

import java.io.IOException;
import java.util.List;

import org.bukkit.event.EventPriority;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.api.RadiationExposureEvent;
import com.ft.radsurvival.core.RadiationTask.RoundOutcome;
import com.ft.radsurvival.core.RadiationTask.RoundResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * RadiationExposureEvent 事件链测试（plan.md §1.3 伤害标记主线、§4.8 可取消/可改伤）。
 *
 * <p>验证点：LOWEST 自有监听写事件 PDC 标记（{@code radsurvival:source=radiation}）；
 * 取消 → 不掉血；setFinalDamage 覆写 → 按覆写值掉血；覆写 ≤0 → 不调用伤害；
 * 免疫（全下界合金）零事件；事件字段快照正确；瞬态 PDC 不可序列化。</p>
 */
class RadiationExposureEventChainTest {

    private ServerMock server;
    private RadSurvivalPlugin plugin;
    private Support.Fixture fixture;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RadSurvivalPlugin.class);
        fixture = Support.install(plugin, server);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("NORMAL 优先级监听可见事件 PDC 已带 radsurvival:source=radiation（LOWEST 先跑）且字段快照正确")
    void eventCarriesSourceMarkerAndFields() {
        PlayerMock player = server.addPlayer("Marked");
        fixture.meter().setValue(player.getUniqueId(), 25.0);
        player.getWorld().setTime(6000L);

        RadiationExposureEvent[] seen = new RadiationExposureEvent[1];
        boolean[] markerAtNormal = new boolean[1];
        server.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(priority = EventPriority.NORMAL)
            public void onEvent(RadiationExposureEvent event) {
                seen[0] = event;
                markerAtNormal[0] = RadiationExposureEvent.SOURCE_PDC_VALUE.equals(
                        event.getPersistentDataContainer().get(
                                RadiationExposureEvent.SOURCE_PDC_KEY, PersistentDataType.STRING));
            }
        }, plugin);

        RoundOutcome outcome = fixture.task().settleRound(player);
        assertThat(outcome.result()).isEqualTo(RoundResult.DAMAGED);
        assertThat(markerAtNormal[0]).as("外部插件在 NORMAL 即可见来源标记").isTrue();
        RadiationExposureEvent event = seen[0];
        assertThat(event.getPlayer().getUniqueId()).isEqualTo(player.getUniqueId());
        assertThat(event.isExposed()).isTrue();
        assertThat(event.getTimeFactor()).isCloseTo(1.0, within(1e-9));
        assertThat(event.getArmorReduction()).isZero();
        assertThat(event.getCorrosion()).as("事件携带步进后腐蚀值（与本轮伤害倍率基数一致）")
                .isCloseTo(26.0, within(1e-9));
        // 本轮伤害：腐蚀步进 25→26 后 mult=1.26 → dmg=1.26（事件携带的即 26 这一参与计算值）
        assertThat(outcome.damage()).isCloseTo(1.26, within(1e-9));
        assertThat(fixture.corrosion(player)).isCloseTo(26.0, within(1e-9));
    }

    @Test
    @DisplayName("监听取消事件：不掉血、执行器零调用，但腐蚀已先步进（先累后打）")
    void cancelledEventPreventsDamageOnly() {
        PlayerMock player = server.addPlayer("Shielded");
        server.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(priority = EventPriority.LOW) // LOWEST 盖章之后取消
            public void cancelAll(RadiationExposureEvent event) {
                event.setCancelled(true);
            }
        }, plugin);

        RoundOutcome outcome = fixture.task().settleRound(player);
        assertThat(outcome.result()).isEqualTo(RoundResult.CANCELLED_BY_EVENT);
        assertThat(outcome.damage()).isZero();
        assertThat(fixture.damages).as("damage() 未被调用").isEmpty();
        assertThat(player.getHealth()).isEqualTo(20.0);
        assertThat(fixture.corrosion(player)).as("腐蚀步进仍发生").isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("setFinalDamage 覆写改伤：按覆写值真实掉血并回写状态 lastDamage")
    void setFinalDamageOverrides() {
        PlayerMock player = server.addPlayer("Reshaped");
        server.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(priority = EventPriority.HIGH)
            public void tweak(RadiationExposureEvent event) {
                event.setFinalDamage(3.0); // 外部插件加权/加权削减后的最终口径
            }
        }, plugin);

        RoundOutcome outcome = fixture.task().settleRound(player);
        assertThat(outcome.result()).isEqualTo(RoundResult.DAMAGED);
        assertThat(outcome.damage()).isCloseTo(3.0, within(1e-9));
        assertThat(fixture.damages).hasSize(1);
        assertThat(fixture.lastDamageAmount()).isCloseTo(3.0, within(1e-9));
        assertThat(player.getHealth()).isCloseTo(17.0, within(1e-9));
        assertThat(fixture.task().stateOf(player.getUniqueId()).getLastDamage()).isCloseTo(3.0, within(1e-9));
    }

    @Test
    @DisplayName("覆写为 0/负值 → 本轮不调用伤害执行器（等效免伤，不回血）")
    void zeroOverrideMeansNoDamage() {
        PlayerMock player = server.addPlayer("Zeroed");
        server.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(priority = EventPriority.HIGH)
            public void zero(RadiationExposureEvent event) {
                event.setFinalDamage(-5.0);
            }
        }, plugin);

        RoundOutcome outcome = fixture.task().settleRound(player);
        assertThat(outcome.result()).isEqualTo(RoundResult.CANCELLED_BY_EVENT);
        assertThat(fixture.damages).isEmpty();
        assertThat(player.getHealth()).isEqualTo(20.0); // 负伤害不得回血
    }

    @Test
    @DisplayName("免疫短路零事件：全下界合金下 RadiationExposureEvent 一次都不广播（§1.3 不播任何反馈的口径根基）")
    void immuneNeverBroadcastsEvents() {
        PlayerMock tank = server.addPlayer("Neri");
        Support.equipFullSet(tank, "NETHERITE");
        Support.resetCombat(fixture, server);
        server.getScheduler().performTicks(20);
        assertThat(fixture.events).isEmpty();
        assertThat(tank.getHealth()).isEqualTo(20.0);
    }

    @Test
    @DisplayName("事件瞬态 PDC：标记读写正常、serializeToBytes 拒绝落盘")
    void eventPdcIsTransientAndMinimal() {
        PlayerMock player = server.addPlayer("PdcProbe");
        RadiationExposureEvent event = new RadiationExposureEvent(player, true, 1.0, 0.2, 50.0, 0.8);
        assertThat(event.getPersistentDataContainer().isEmpty()).isTrue();
        event.getPersistentDataContainer()
                .set(RadiationExposureEvent.SOURCE_PDC_KEY, PersistentDataType.STRING,
                        RadiationExposureEvent.SOURCE_PDC_VALUE);
        assertThat(event.getPersistentDataContainer()
                .get(RadiationExposureEvent.SOURCE_PDC_KEY, PersistentDataType.STRING))
                .isEqualTo("radiation");
        assertThat(List.copyOf(event.getPersistentDataContainer().getKeys()))
                .containsExactly(RadiationExposureEvent.SOURCE_PDC_KEY);
        assertThatThrownBy(() -> event.getPersistentDataContainer().serializeToBytes())
                .isInstanceOf(IOException.class);
        // 类型错读抛 ClassCastException（防串型乱写）
        assertThatThrownBy(() -> event.getPersistentDataContainer()
                .get(RadiationExposureEvent.SOURCE_PDC_KEY, PersistentDataType.DOUBLE))
                .isInstanceOf(ClassCastException.class);
    }
}
