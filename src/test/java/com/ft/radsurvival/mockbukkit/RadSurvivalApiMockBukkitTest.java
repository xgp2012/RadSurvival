package com.ft.radsurvival.mockbukkit;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.api.RadSurvivalApi;
import com.ft.radsurvival.core.CorrosionMeter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RadSurvivalApi 最小门面测试（plan.md §2 api/、§6 读写收敛）：
 * 写走 CorrosionMeter + PDC 同步 + 状态镜像，读优先内存、缺记录回读 PDC，未启用安全降级。
 */
class RadSurvivalApiMockBukkitTest {

    private ServerMock server;
    private RadSurvivalPlugin plugin;
    private RadSurvivalApi api;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RadSurvivalPlugin.class);
        api = plugin.api();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("setCorrosion 钳 [0,100] + 同步 PDC + 状态镜像；getCorrosion 内存权威")
    void writeThroughMeterAndPersist() {
        PlayerMock player = server.addPlayer("ApiUser");
        assertThat(api.getCorrosion(player.getUniqueId())).isZero();

        assertThat(api.setCorrosion(player, 150.0)).isEqualTo(100.0);
        assertThat(api.getCorrosion(player.getUniqueId())).isEqualTo(100.0);
        assertThat(player.getPersistentDataContainer()
                .get(CorrosionMeter.CORROSION_PDC_KEY, org.bukkit.persistence.PersistentDataType.DOUBLE))
                .isEqualTo(100.0);
        assertThat(plugin.radiationTask().stateOf(player.getUniqueId()).getCorrosion()).isEqualTo(100.0);

        assertThat(api.setCorrosion(player, -20.0)).isZero();
    }

    @Test
    @DisplayName("addCorrosion 负增量（§4.3 抗辐宁入口）钳 0")
    void addNegativeClamps() {
        PlayerMock player = server.addPlayer("Healer");
        api.setCorrosion(player, 30.0);
        assertThat(api.addCorrosion(player, -50.0)).isZero();
        assertThat(api.addCorrosion(player, 25.0)).isEqualTo(25.0);
    }

    @Test
    @DisplayName("getCorrosion(Player)：内存缺记录时回读 PDC（§6 读收敛于本门面）")
    void playerReadFallsBackToPdc() {
        PlayerMock player = server.addPlayer("Reloaded");
        // 人为制造「内存缺记录」：forget 后 PDC 仍留 42
        api.setCorrosion(player, 42.0);
        plugin.services().corrosionMeter().forget(player.getUniqueId());
        assertThat(api.getCorrosion(player.getUniqueId())).as("UUID 路径读内存缺省 0").isZero();
        assertThat(api.getCorrosion(player)).as("Player 路径回读 PDC 并回灌内存").isEqualTo(42.0);
        assertThat(api.getCorrosion(player.getUniqueId())).isEqualTo(42.0);
    }

    @Test
    @DisplayName("插件停用后：isReady=false，读写安全降级为 0/无副作用（不再写 PDC）")
    void safeWhenDisabled() {
        PlayerMock player = server.addPlayer("OfflineSoon");
        server.getPluginManager().disablePlugin(plugin);
        // onDisable 已把 join 初始化的默认 0 冲刷到 PDC；此后任何门面写都必须无副作用
        assertThat(player.getPersistentDataContainer()
                .get(CorrosionMeter.CORROSION_PDC_KEY, org.bukkit.persistence.PersistentDataType.DOUBLE))
                .isEqualTo(0.0);
        assertThat(api.isReady()).isFalse();
        assertThat(api.getCorrosion(player.getUniqueId())).isZero();
        assertThat(api.setCorrosion(player, 50.0)).isZero();
        assertThat(api.getCorrosion(player)).isZero();
        assertThat(player.getPersistentDataContainer()
                .get(CorrosionMeter.CORROSION_PDC_KEY, org.bukkit.persistence.PersistentDataType.DOUBLE))
                .as("停用后写门面不得触碰 PDC").isEqualTo(0.0);
        assertThat(api.getCorrosion(UUID.randomUUID())).isZero();
    }
}
