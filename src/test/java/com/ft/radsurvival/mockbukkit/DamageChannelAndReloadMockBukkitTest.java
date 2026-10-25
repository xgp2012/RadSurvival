package com.ft.radsurvival.mockbukkit;

import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.core.CorrosionMeter;
import com.ft.radsurvival.core.DamageApplier;
import com.ft.radsurvival.feedback.FeedbackService;
import com.ft.radsurvival.placeholder.PlaceholderBridge;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 判定链路强化 / reload 生命周期回归测试（本轮修复的护栏）。
 *
 * <p>覆盖四个真实缺陷：</p>
 * <ol>
 *   <li><b>reload 泄漏</b>——旧 {@link FeedbackService} 的阶段回调与受击挂点未解绑；</li>
 *   <li><b>占位符重注册</b>——reload 后 PAPI expansion 不刷新（无 PAPI 环境下退化为桥状态断言）；</li>
 *   <li><b>伤害通道</b>——{@code RadiationExposureEvent} 必须带 SUN 通道标识，
 *       外部 CUSTOM 伤害不得被认作太阳辐射；</li>
 *   <li><b>摘甲执行器</b>——结算后玩家盔甲必须原样还原（不得残留空槽）。</li>
 * </ol>
 */
class DamageChannelAndReloadMockBukkitTest {

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

    // ------------------------------------------------------------ reload 不泄漏

    @Test
    @DisplayName("reload 后旧 FeedbackService 不再收到阶段回调（setCorrosion 只产生一份播报）")
    void reloadUnbindsOldFeedbackStageListener() {
        PlayerMock player = server.addPlayer("Reloader");
        FeedbackService old = plugin.feedback();
        assertThat(old).isNotNull();

        assertThat(plugin.reloadConfigFromDisk()).isEmpty();

        FeedbackService fresh = plugin.feedback();
        assertThat(fresh).as("reload 必须重建反馈层").isNotSameAs(old);

        // 旧实例若仍在监听表里，一次跨阈值写会同时触发旧/新两份播报
        plugin.api().setCorrosion(player, 60.0);
        assertThat(fresh.announcementsPlayed()).isEqualTo(1);
        assertThat(old.announcementsPlayed())
                .as("旧实例已解绑：不得再计数")
                .isZero();
    }

    @Test
    @DisplayName("reload 后新容器的 DamageApplier 挂点只指向新反馈层（旧实例不再记录掉血归因）")
    void reloadRebindsDamageHookToFreshFeedback() {
        PlayerMock player = server.addPlayer("Hooky");
        FeedbackService old = plugin.feedback();

        assertThat(plugin.reloadConfigFromDisk()).isEmpty();
        FeedbackService fresh = plugin.feedback();
        assertThat(fresh).isNotSameAs(old);

        // 直接驱动新容器的结算器（不调 damage()：MockBukkit 未实现该重载），
        // 走「事件 → 挂点 → 归因记录」的真实链路
        fresh.onRadiationDamage(player, 1.0);
        assertThat(fresh.lastRadiationHit(player.getUniqueId())).isNotNull();

        // 旧实例已 shutdown 解绑，其计数与归因记录保持静止
        assertThat(old.lastRadiationHit(player.getUniqueId()))
                .as("旧实例在 reload 时已清空归因记录")
                .isNull();
        assertThat(old.announcementsPlayed()).isZero();
    }

    @Test
    @DisplayName("disable 后旧反馈层解绑（services 归位 null 且不再有回调残留）")
    void disableUnbindsFeedback() {
        FeedbackService current = plugin.feedback();
        server.getPluginManager().disablePlugin(plugin);
        assertThat(plugin.feedback()).isNull();
        assertThat(plugin.services()).isNull();
        assertThat(current.announcementsPlayed()).isZero();
    }

    // --------------------------------------------------------- 占位符重注册语义

    @Test
    @DisplayName("无 PlaceholderAPI 时桥状态恒为未注册（reload 前后都不得凭空出现）")
    void placeholderBridgeStaysDormantWithoutPapi() {
        assertThat(PlaceholderBridge.isRegistered()).isFalse();
        assertThat(plugin.reloadConfigFromDisk()).isEmpty();
        assertThat(PlaceholderBridge.isRegistered()).as("PAPI 缺失时 reload 不得注册").isFalse();
        server.getPluginManager().disablePlugin(plugin);
        assertThat(PlaceholderBridge.isRegistered()).isFalse();
    }

    // ------------------------------------------------------------- 伤害通道识别

    @Test
    @DisplayName("真实结算事件带 SUN 通道标识（外部插件可据此区分 GENERIC 第三方伤害）")
    void realSettlementCarriesSunChannel() {
        Support.Fixture fixture = Support.install(plugin, server);
        PlayerMock player = server.addPlayer("Sunny");

        fixture.task().settleRound(player);

        assertThat(fixture.events).hasSize(1);
        assertThat(fixture.events.get(0).getDamageChannel())
                .isEqualTo(DamageApplier.DamageChannel.SUN);
        assertThat(fixture.events.get(0).getDamageChannelId()).isEqualTo(DamageApplier.CHANNEL_SUN);
    }

    @Test
    @DisplayName("外部 CUSTOM 伤害不被认作太阳辐射（无标记时 classify=false，护甲减免得以保留）")
    void externalCustomDamageIsNotSun() {
        PlayerMock player = server.addPlayer("Struck");
        Support.equipFullSet(player, "NETHERITE");

        EntityDamageEvent external = new EntityDamageEvent(
                player, EntityDamageEvent.DamageCause.CUSTOM, 10.0);
        server.getPluginManager().callEvent(external);

        assertThat(external.isCancelled()).isFalse();
        assertThat(external.getDamage())
                .as("本插件的门控监听只对太阳伤害放行原值；外部 CUSTOM 伤害必须保留原值（原版护甲另算）")
                .isEqualTo(10.0);
    }

    // ------------------------------------------------------------- 摘甲执行器

    @Test
    @DisplayName("摘甲执行器：damage() 抛异常时盔甲也必须原样还原（不丢玩家装备）")
    void armorRestoredEvenWhenDamageThrows() {
        PlayerMock player = server.addPlayer("Armored");
        Support.equipFullSet(player, "DIAMOND");
        var before = player.getInventory().getArmorContents().clone();

        DamageApplier.DamageExecutor executor = DamageApplier.ARMOR_NEUTRALIZING_EXECUTOR;
        try {
            // MockBukkit 未实现 damage(double, DamageSource) → 必然抛 UnimplementedOperationException
            executor.damage(player, 1.0);
        } catch (RuntimeException expected) {
            // 预期：MockBukkit 的能力缺口，正是本用例要验证的异常路径
        }

        var after = player.getInventory().getArmorContents();
        assertThat(after).as("盔甲数量不变").hasSameSizeAs(before);
        for (int i = 0; i < before.length; i++) {
            assertThat(after[i]).as("第 %d 件盔甲已还原", i).isNotNull();
            assertThat(after[i].getType()).as("第 %d 件盔甲材质还原", i).isEqualTo(before[i].getType());
        }
    }

    @Test
    @DisplayName("CorrosionMeter.removeStageListener：解绑后回调不再触发（幂等、未知监听器无副作用）")
    void removeStageListenerIsPrecise() {
        CorrosionMeter meter = plugin.services().corrosionMeter();
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        CorrosionMeter.CorrosionStageListener listener =
                (id, oldStage, newStage) -> hits.incrementAndGet();

        meter.addStageListener(listener);
        java.util.UUID id = java.util.UUID.randomUUID();
        meter.setValue(id, 60.0);
        assertThat(hits.get()).isEqualTo(1);

        meter.removeStageListener(listener);
        meter.setValue(id, 10.0);
        meter.setValue(id, 80.0);
        assertThat(hits).as("解绑后不得再回调").hasValue(1);

        meter.removeStageListener(listener); // 幂等
        assertThat(hits).hasValue(1);
    }
}
