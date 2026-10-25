package com.ft.radsurvival.mockbukkit;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.core.CorrosionMeter;
import com.ft.radsurvival.core.RadiationTask;
import com.ft.radsurvival.listener.RadiationMarkListener;
import com.ft.radsurvival.api.RadiationExposureEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MockBukkit 能力钉死测试（plan.md §5.2「版本滞后」判据；同时钉住 M2b 集成测试的口径边界）。
 *
 * <p>结论（MockBukkit 4.116.3 + paper-api 1.21.11 实测）：
 * 可用——插件装载/tick 推进/join-quit-death 事件/玩家 PDC/damage(double) 简单重载/
 * addAttachment 权限/消息捕获/多世界创建；
 * 不可用——damage(double, DamageSource)（生产伤害调用只留实机）、BlockData#isOccluding
 * （露天扫描的方块环节经测试注入缝替换，缓存/维度短路等内核逻辑仍由 M2a 单测覆盖）、
 * 原版无敌帧模拟（noDamageTicks 恒 0，连续两击都掉血）→ 三项均移交 M4 实机验收。</p>
 */
class MockBukkitCapabilityTest {

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

    @Test
    @DisplayName("onEnable 装配：services/任务/双监听器就绪（M2b 冒烟基准）")
    void wiring() {
        assertThat(plugin.config()).isNotNull();
        assertThat(plugin.services()).isNotNull();
        assertThat(plugin.radiationTask()).isNotNull();
        assertThat(plugin.radiationTask().isScheduled()).isTrue();
        assertThat(plugin.api().isReady()).isTrue();
        assertThat(RadiationExposureEvent.getHandlerList().getRegisteredListeners(plugin))
                .anyMatch(l -> l.getListener() instanceof RadiationMarkListener);
    }

    @Test
    @DisplayName("任务由 scheduler 真实驱动（tick 推进可用；首任务延迟一个周期）")
    void schedulerDrivesTask() {
        RadiationTask task = plugin.radiationTask();
        PlayerMock player = server.addPlayer("Ticker");
        // 生产任务 + 生产露天解析器在 MockBukkit 的 NORMAL 世界不可扫描（isOccluding 未实现），
        // 结算异常被主循环 try/catch 隔离；此处只验证「任务确实按周期被调度执行」：
        // require 链在露天判定之前——用豁免玩家验证零异常轮次推进
        player.setGameMode(org.bukkit.GameMode.CREATIVE);
        assertThatCode(() -> server.getScheduler().performTicks(40)).doesNotThrowAnyException();
        assertThat(plugin.services().corrosionMeter().get(player.getUniqueId())).isZero();
    }

    @Test
    @DisplayName("damage 重载可用性：damage(double)/(double,Entity) 真实扣血；damage(double,DamageSource) 未实现→M4")
    void damageOverloads() {
        PlayerMock p = server.addPlayer("Dmg");
        p.setHealth(20.0);
        p.damage(3.0);
        assertThat(p.getHealth()).isEqualTo(17.0);
        p.damage(2.0, p);
        assertThat(p.getHealth()).isEqualTo(15.0);
        assertThatThrownBy(() -> p.damage(1.0, DamageSource.builder(DamageType.GENERIC).build()))
                .isInstanceOf(UnimplementedOperationException.class);
    }

    @Test
    @DisplayName("无敌帧不模拟：noDamageTicks 恒 0、连续两击都掉血（M4 实机断言每轮真实掉血）")
    void noInvulnerabilityEmulation() {
        PlayerMock p = server.addPlayer("Frames");
        p.setHealth(20.0);
        p.damage(1.0);
        assertThat(p.getNoDamageTicks()).isZero();
        p.damage(1.0); // 立即第二击
        assertThat(p.getHealth()).as("原版应吞掉窗口内第二击，MockBukkit 直接掉血").isEqualTo(18.0);
    }

    @Test
    @DisplayName("BlockData#isOccluding 未实现（露天方块扫描环节测试期由 ExposureResolver 注入）")
    void occludingUnimplemented() {
        PlayerMock p = server.addPlayer("Eye");
        World world = p.getWorld();
        assertThat(world.getName()).isEqualTo("world");
        assertThat(world.getEnvironment()).isEqualTo(World.Environment.NORMAL);
        assertThatThrownBy(() -> world.getBlockAt(0, 100, 0).getBlockData().isOccluding())
                .isInstanceOf(UnimplementedOperationException.class);
        assertThatCode(() -> world.getBlockAt(0, 100, 0).setType(Material.STONE))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("豁免维度真路径：NETHER 世界在方块扫描前被 dimensions 短路（生产 SunExposureChecker）")
    void exemptDimensionShortCircuits() {
        World nether = server.createWorld(new WorldCreator("nether_cap")
                .environment(World.Environment.NETHER));
        PlayerMock p = server.addPlayer("NetherCap");
        p.teleport(nether.getSpawnLocation());
        assertThat(plugin.services().sunExposureChecker().isExposed(nether, p.getEyeLocation())).isFalse();
    }

    @Test
    @DisplayName("玩家 PDC（radsurvival:corrosion）读写与常量钉死")
    void playerPdcBasics() {
        assertThat(CorrosionMeter.PDC_KEY_STRING).isEqualTo("radsurvival:corrosion");
        PlayerMock p = server.addPlayer("Pdc");
        p.getPersistentDataContainer()
                .set(CorrosionMeter.CORROSION_PDC_KEY,
                        org.bukkit.persistence.PersistentDataType.DOUBLE, 37.5);
        assertThat(p.getPersistentDataContainer()
                .get(CorrosionMeter.CORROSION_PDC_KEY,
                        org.bukkit.persistence.PersistentDataType.DOUBLE))
                .isEqualTo(37.5);
    }

    @Test
    @DisplayName("权限模型：非 op 默认拒 admin；setOp/addAttachment 生效（corrosion/exempt 依赖）")
    void permissions() {
        PlayerMock plain = server.addPlayer("Plain");
        assertThat(plain.hasPermission("radsurvival.admin")).isFalse();
        plain.setOp(true);
        assertThat(plain.hasPermission("radsurvival.admin")).isTrue();
        PlayerMock vault = server.addPlayer("Vault");
        assertThat(vault.hasPermission(RadiationTask.PERMISSION_EXEMPT)).isFalse();
        vault.addAttachment(plugin, RadiationTask.PERMISSION_EXEMPT, true);
        assertThat(vault.hasPermission(RadiationTask.PERMISSION_EXEMPT)).isTrue();
    }

    @Test
    @DisplayName("join/quit 事件真实触发（会话监听依赖）；disconnect 广播 PlayerQuitEvent")
    void joinQuitEvents() {
        PlayerMock p = server.addPlayer("JoJo");
        assertThat(plugin.radiationTask().stateOf(p.getUniqueId())).isNotNull();
        p.disconnect();
        assertThat(plugin.radiationTask().stateOf(p.getUniqueId())).isNull();
    }
}
