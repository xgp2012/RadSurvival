package com.ft.radsurvival.mockbukkit;

import java.util.List;

import net.kyori.adventure.text.Component;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.core.CorrosionMeter;

import static org.assertj.core.api.Assertions.assertThat;
import static com.ft.radsurvival.mockbukkit.Support.configYamlWith;

/**
 * 会话生命周期与持久化测试（plan.md §1.6 持久化、§2 玩家状态：join/quit/death、
 * PDC key radsurvival:corrosion 往返、离线冻结、死亡清零/减半、onDisable 冲刷、reload 续接）。
 */
class SessionLifecycleMockBukkitTest {

    private ServerMock server;
    private RadSurvivalPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RadSurvivalPlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Double pdcValue(PlayerMock player) {
        return player.getPersistentDataContainer()
                .get(CorrosionMeter.CORROSION_PDC_KEY, PersistentDataType.DOUBLE);
    }

    @Test
    @DisplayName("join：PDC 缺省 → 腐蚀初始化 0 并建状态条目")
    void joinInitializesDefaultZero() {
        PlayerMock player = server.addPlayer("Fresh");
        var state = plugin.radiationTask().stateOf(player.getUniqueId());
        assertThat(state).isNotNull();
        assertThat(state.getCorrosion()).isZero();
        assertThat(plugin.services().corrosionMeter().get(player.getUniqueId())).isZero();
    }

    @Test
    @DisplayName("join/reload 读 PDC 已有值（radsurvival:corrosion=42 → 重登记恢复 42，缺省才 0）")
    void registrationRestoresFromPdc() {
        PlayerMock player = server.addPlayer("Returning");
        // 模拟「离线期间落盘的旧值」：内存清空、PDC 写 42
        plugin.services().corrosionMeter().forget(player.getUniqueId());
        player.getPersistentDataContainer()
                .set(CorrosionMeter.CORROSION_PDC_KEY, PersistentDataType.DOUBLE, 42.0);

        assertThat(plugin.reloadConfigFromDisk()).isEmpty(); // 合法 reload = 服务重建 + 在线玩家重登记

        assertThat(plugin.services().corrosionMeter().get(player.getUniqueId())).isEqualTo(42.0);
        assertThat(plugin.radiationTask().stateOf(player.getUniqueId()).getCorrosion()).isEqualTo(42.0);
    }

    @Test
    @DisplayName("quit：写回 PDC → 清内存与状态（离线冻结，重新上线从原值继续）")
    void quitPersistsAndFreezes() {
        PlayerMock player = server.addPlayer("Leaver");
        plugin.api().setCorrosion(player, 60.0);

        player.disconnect();

        assertThat(pdcValue(player)).isEqualTo(60.0);
        assertThat(plugin.services().corrosionMeter().get(player.getUniqueId()))
                .as("内存权威已清除（forget）")
                .isZero();
        assertThat(plugin.radiationTask().stateOf(player.getUniqueId())).isNull();
    }

    @Test
    @DisplayName("death（reset-on-death=true 默认）：内存与 PDC 同时清零、状态镜像刷新")
    void deathResetsCorrosion() {
        PlayerMock player = server.addPlayer("Deadeasy");
        plugin.api().setCorrosion(player, 80.0);

        server.getPluginManager().callEvent(new PlayerDeathEvent(
                player, DamageSource.builder(DamageType.GENERIC).build(),
                List.<ItemStack>of(), 0, Component.text("died"), false));

        assertThat(plugin.services().corrosionMeter().get(player.getUniqueId())).isZero();
        assertThat(pdcValue(player)).as("死亡后立即持久化").isEqualTo(0.0);
        assertThat(plugin.radiationTask().stateOf(player.getUniqueId()).getCorrosion()).isZero();
    }

    @Test
    @DisplayName("death（decay-on-death=0.5 变体配置）：减半不清零并立即落 PDC")
    void deathDecaysWhenConfigured() {
        MockBukkit.unmock();
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RadSurvivalPlugin.class);
        Support.applyConfigVariant(plugin, configYamlWith(
                "reset-on-death: true", "reset-on-death: false",
                "  # decay-on-death: 0.5", "  decay-on-death: 0.5"));
        assertThat(plugin.config().corrosion().decayOnDeath()).isEqualTo(0.5);

        PlayerMock player = server.addPlayer("Graveyard");
        plugin.api().setCorrosion(player, 80.0);
        server.getPluginManager().callEvent(new PlayerDeathEvent(
                player, DamageSource.builder(DamageType.GENERIC).build(),
                List.<ItemStack>of(), 0, Component.text("died again"), false));

        assertThat(plugin.services().corrosionMeter().get(player.getUniqueId())).isEqualTo(40.0);
        assertThat(pdcValue(player)).isEqualTo(40.0);
    }

    @Test
    @DisplayName("onDisable：冲刷在线玩家腐蚀到 PDC、services 归位 null、任务取消")
    void disableFlushesAndClears() {
        PlayerMock player = server.addPlayer("Stayer");
        plugin.api().setCorrosion(player, 55.0);

        server.getPluginManager().disablePlugin(plugin);

        assertThat(pdcValue(player)).isEqualTo(55.0);
        assertThat(plugin.services()).isNull();
        assertThat(plugin.radiationTask()).isNull();
    }

    @Test
    @DisplayName("合法 reload：在线玩家腐蚀跨容器无缝续接（冲刷→PDC→重登记）")
    void reloadKeepsCorrosionSeamlessly() {
        PlayerMock player = server.addPlayer("Seamless");
        plugin.api().setCorrosion(player, 33.0);

        assertThat(plugin.reloadConfigFromDisk()).isEmpty();

        assertThat(plugin.services().corrosionMeter().get(player.getUniqueId())).isEqualTo(33.0);
        assertThat(pdcValue(player)).isEqualTo(33.0);
        assertThat(plugin.radiationTask().stateOf(player.getUniqueId()).getCorrosion()).isEqualTo(33.0);
        assertThat(plugin.radiationTask().isScheduled()).isTrue();
    }

    @Test
    @DisplayName("非法 reload：返回错误明细且旧容器/旧配置原样保留（M1 语义 + 服务不重建）")
    void invalidReloadKeepsEverything() throws java.io.IOException {
        PlayerMock player = server.addPlayer("Keeper");
        plugin.api().setCorrosion(player, 21.0);
        var oldServices = plugin.services();

        java.nio.file.Files.writeString(plugin.configFile(),
                Support.configYamlWith("tick-interval: 20", "tick-interval: 5"));
        List<String> errors = plugin.reloadConfigFromDisk();

        assertThat(errors).anyMatch(e -> e.contains("tick-interval"));
        assertThat(plugin.services()).isSameAs(oldServices); // 未重建
        assertThat(plugin.config().tickInterval()).isEqualTo(20);
        assertThat(plugin.services().corrosionMeter().get(player.getUniqueId())).isEqualTo(21.0);
    }
}
