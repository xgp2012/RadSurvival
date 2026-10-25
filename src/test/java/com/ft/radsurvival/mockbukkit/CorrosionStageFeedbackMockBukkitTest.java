package com.ft.radsurvival.mockbukkit;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BossBar;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.api.CorrosionStageChangeEvent;
import com.ft.radsurvival.core.CorrosionMeter;
import com.ft.radsurvival.core.PlayerRadState;
import com.ft.radsurvival.feedback.FeedbackService;

import static org.assertj.core.api.Assertions.assertThat;
import static com.ft.radsurvival.mockbukkit.Support.applyConfigVariant;
import static com.ft.radsurvival.mockbukkit.Support.configYamlWith;

/**
 * 腐蚀阶段事件桥 + 播报反馈集成测试（M3 验收：CorrosionStageChangeEvent 取消→抑制播报
 * 且不改数值；未取消→title+音效；首次 warn 附规则说明；feedback.sound/actionbar 开关；
 * plan.md §1.5/§1.6/§5.2——MockBukkit 4.116.3 已实现 sendTitle/playSound/sendActionBar，
 * 播报投递可真实断言）。
 */
class CorrosionStageFeedbackMockBukkitTest {

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

    /** 捕获事件 + 可选 NORMAL 取消钩子。 */
    private final class Capture implements Listener {
        final List<CorrosionStageChangeEvent> events = new ArrayList<>();
        boolean cancelAtNormal;

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
        public void capture(CorrosionStageChangeEvent event) {
            events.add(event);
            if (cancelAtNormal) {
                event.setCancelled(true);
            }
        }
    }

    private Capture registerCapture() {
        Capture capture = new Capture();
        server.getPluginManager().registerEvents(capture, plugin);
        return capture;
    }

    // ------------------------------------------------------- 事件桥与数值语义

    @Test
    @DisplayName("跨 warn 阈值：事件广播字段完整（UUID/玩家/old/new/腐蚀值/阈值），未取消 → 播报")
    void warnCrossingBroadcastsEventAndAnnounces() {
        PlayerMock player = server.addPlayer("Warned");
        Capture capture = registerCapture();
        FeedbackService fb = plugin.feedback();
        long played0 = fb.announcementsPlayed();

        plugin.api().setCorrosion(player, 60.0);

        assertThat(capture.events).hasSize(1);
        CorrosionStageChangeEvent event = capture.events.get(0);
        assertThat(event.getPlayerId()).isEqualTo(player.getUniqueId());
        assertThat(event.getPlayer()).isSameAs(player);
        assertThat(event.getOldStage()).isEqualTo(CorrosionMeter.STAGE_NORMAL);
        assertThat(event.getNewStage()).isEqualTo(CorrosionMeter.STAGE_WARN);
        assertThat(event.getCorrosion()).isEqualTo(60.0);
        assertThat(event.getWarnThreshold()).isEqualTo(50.0);
        assertThat(event.getDangerThreshold()).isEqualTo(75.0);
        assertThat(event.isUpward()).isTrue();
        assertThat(event.isCancelled()).isFalse();

        // 播报：title+音效已投递（§1.6 阶段跳变 title+音效）
        assertThat(fb.announcementsPlayed()).isEqualTo(played0 + 1);
        assertThat(fb.announcementsSuppressed()).isZero();
        assertThat(player.nextTitle()).isEqualTo("§e辐射腐蚀加重！");
        String subtitle = player.nextSubTitle();
        assertThat(subtitle).contains("腐蚀已达 §e60%");
        assertThat(player.getHeardSounds())
                .anyMatch(s -> s.getSound().contains("note_block.bass")); // warn 低音音符（namespaced key 形态）
    }

    @Test
    @DisplayName("首次到达 warn 附规则说明；此后跳变（warn→danger）不再重复规则句")
    void firstWarnAnnouncesRuleOnce() {
        PlayerMock player = server.addPlayer("Newbie");
        PlayerRadState state = plugin.radiationTask().stateOf(player.getUniqueId());
        assertThat(state.isFirstWarnAnnounced()).isFalse();

        plugin.api().setCorrosion(player, 60.0);
        assertThat(player.nextTitle()).isEqualTo("§e辐射腐蚀加重！");
        assertThat(player.nextSubTitle()).contains("规则：");
        assertThat(state.isFirstWarnAnnounced()).isTrue();

        plugin.api().setCorrosion(player, 80.0); // warn→danger
        assertThat(player.nextTitle()).isEqualTo("§c辐射腐蚀危险！");
        assertThat(player.nextSubTitle()).doesNotContain("规则：");
    }

    @Test
    @DisplayName("降级回档（遮蔽恢复跨越 danger→warn）：播「辐射腐蚀减轻」，非升档")
    void downwardTransitionAnnouncesRelief() {
        PlayerMock player = server.addPlayer("Recovering");
        Capture capture = registerCapture();
        plugin.api().setCorrosion(player, 80.0);
        capture.events.clear();
        player.nextTitle(); // 消费升档播报（danger），聚焦降档行为
        player.nextSubTitle();

        FeedbackService fb = plugin.feedback();
        long played0 = fb.announcementsPlayed();
        plugin.api().setCorrosion(player, 60.0); // danger→warn（向下）

        assertThat(capture.events).hasSize(1);
        assertThat(capture.events.get(0).isUpward()).isFalse();
        assertThat(fb.announcementsPlayed()).isEqualTo(played0 + 1);
        assertThat(player.nextTitle()).isEqualTo("§a辐射腐蚀减轻");
        assertThat(player.getHeardSounds())
                .anyMatch(s -> s.getSound().contains("experience_orb")); // 减轻提示音（原版 ID 带下划线）
    }

    // --------------------------------------------------- Cancellable：只抑制播报

    @Test
    @DisplayName("取消事件（NORMAL）→ MONITOR 播报端抑制（计数器+无 title），腐蚀数值不回滚")
    void cancelledEventSuppressesAnnouncementOnly() {
        PlayerMock player = server.addPlayer("Muted");
        Capture capture = registerCapture();
        capture.cancelAtNormal = true;
        FeedbackService fb = plugin.feedback();

        plugin.api().setCorrosion(player, 85.0);

        assertThat(capture.events).hasSize(1);
        assertThat(capture.events.get(0).isCancelled()).isTrue();
        // 播报抑制：played 不增、suppressed 增、title 队列为空、无音效
        assertThat(fb.announcementsPlayed()).isZero();
        assertThat(fb.announcementsSuppressed()).isEqualTo(1);
        assertThat(player.nextTitle()).isNull();
        assertThat(player.getHeardSounds()).isEmpty();
        // 数值零回滚（Cancellable 语义钉死，§1.6）
        assertThat(plugin.services().corrosionMeter().get(player.getUniqueId())).isEqualTo(85.0);
        assertThat(plugin.radiationTask().stateOf(player.getUniqueId()).getCorrosion()).isEqualTo(85.0);
    }

    @Test
    @DisplayName("feedback.sound=false → 事件照常广播但零播报；=true 恢复播报")
    void soundSwitchGatesAnnouncement() {
        PlayerMock player = server.addPlayer("SilentFilm");
        Capture capture = registerCapture();
        applyConfigVariant(plugin, configYamlWith(
                "  sound: true", "  sound: false"));

        plugin.api().setCorrosion(plugin.getServer().getPlayer(player.getUniqueId()), 60.0);
        assertThat(capture.events).as("事件桥不受 sound 开关影响").hasSize(1);
        assertThat(plugin.feedback().announcementsPlayed()).isZero();
        assertThat(player.getHeardSounds()).isEmpty();

        applyConfigVariant(plugin, Support.repoConfigYaml()); // 回默认仓库配置（sound=true；
        // Support.configYamlWith 永远基于仓库原文，不能用上一变体做锚点——实测钉死）
        PlayerMock player2 = server.addPlayer("Heard");
        plugin.api().setCorrosion(player2, 60.0);
        assertThat(plugin.feedback().announcementsPlayed()).isEqualTo(1);
        assertThat(player2.nextTitle()).isEqualTo("§e辐射腐蚀加重！");
    }

    // ------------------------------------------------------ actionbar 双条投递

    @Test
    @DisplayName("HUD run()：按开关逐玩家投递 actionbar（计数）；渲染内容由 HudRendererTest 纯函数钉死")
    void hudRendersBothBarsToActionBar() {
        // 能力边界（实测，MockBukkit 4.116.3）：PlayerMock.sendActionBar(String) 为 no-op 桩
        // （只有 Component 重载入队 nextActionBar()），因此本类经 sendActionBar(String) 的投递
        // 内容在 MockBukkit 不可观测 → 此处钉「渲染调用发生且计数正确」；
        // 双条字符串内容/取整/颜色/夜晚比例/免疫段由 HudRendererTest（纯函数）钉死；
        // 真机视觉呈现移交 M4 实机清单。
        PlayerMock player = server.addPlayer("HudOn");
        PlayerRadState state = plugin.radiationTask().stateOf(player.getUniqueId());
        FeedbackService fb = plugin.feedback();

        state.setExposed(true);
        state.setTimeFactor(1.0);
        state.setArmorReduction(0.0);
        plugin.api().setCorrosion(player, 67.0);
        fb.run();
        assertThat(fb.actionbarSends()).isEqualTo(1);
        assertThat(fb.feedbackErrors()).as("渲染链路零异常").isZero();

        state.setTimeFactor(0.5);
        fb.run();
        state.setArmorReduction(1.0);
        fb.run();
        state.setArmorReduction(0.0);
        state.setExposed(false);
        fb.run();
        assertThat(fb.actionbarSends()).isEqualTo(4);
        assertThat(fb.feedbackErrors()).isZero();
    }

    @Test
    @DisplayName("feedback.actionbar=false → 不投递 actionbar、HUD 任务不建（与 bossbar 双关时）")
    void actionbarSwitchGatesHud() {
        applyConfigVariant(plugin, configYamlWith(
                "  actionbar: true", "  actionbar: false"));
        FeedbackService fb = plugin.feedback();
        assertThat(fb.isHudScheduled()).as("actionbar 与 bossbar 全关 → 无 HUD 任务").isFalse();

        PlayerMock player = server.addPlayer("NoBar");
        plugin.api().setCorrosion(player, 60.0);
        fb.run(); // 手动触发也应短路
        assertThat(fb.actionbarSends()).isZero();
        assertThat(player.nextActionBar()).isNull();
    }

    // ------------------------------------------- BossBar 常驻腐蚀条 + reload 泄漏

    @Test
    @DisplayName("feedback.bossbar-corrosion=true：BossBar 常驻（进度/配色/标题），actionbar 仅等级条")
    void bossbarShowsCorrosionAndActionBarDropsIt() {
        applyConfigVariant(plugin, configYamlWith(
                "bossbar-corrosion: false", "bossbar-corrosion: true"));
        PlayerMock player = server.addPlayer("Barista");
        FeedbackService fb = plugin.feedback();
        assertThat(fb.isHudScheduled()).isTrue();

        plugin.api().setCorrosion(player, 42.0);
        fb.run();

        BossBar bar = fb.bossbarsView().get(player.getUniqueId());
        assertThat(bar).isNotNull();
        assertThat(bar.getTitle()).isEqualTo("辐射腐蚀 42%");
        assertThat(bar.getProgress()).isEqualTo(0.42);
        assertThat(bar.getColor()).isEqualTo(BarColor.BLUE);
        assertThat(bar.getPlayers()).containsExactly(player);
        assertThat(fb.actionbarSends()).as("actionbar 仍投递（内容=仅等级条，由纯函数测试钉死）")
                .isEqualTo(1);

        plugin.api().setCorrosion(player, 80.0);
        fb.run();
        assertThat(bar.getTitle()).isEqualTo("辐射腐蚀 80%·危险");
        assertThat(bar.getColor()).isEqualTo(BarColor.RED);
        assertThat(bar.getProgress()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("reload 重建反馈层：旧实例任务取消/BossBar 全清（无泄漏），新实例接管渲染")
    void reloadRebuildsFeedbackLayerWithoutLeak() {
        applyConfigVariant(plugin, configYamlWith(
                "bossbar-corrosion: false", "bossbar-corrosion: true"));
        PlayerMock player = server.addPlayer("Reloader");
        FeedbackService fb1 = plugin.feedback();
        plugin.api().setCorrosion(player, 55.0);
        fb1.run();
        BossBar oldBar = fb1.bossbarsView().get(player.getUniqueId());
        assertThat(oldBar).isNotNull();
        assertThat(oldBar.getPlayers()).containsExactly(player);

        assertThat(plugin.reloadConfigFromDisk()).isEmpty();
        FeedbackService fb2 = plugin.feedback();
        assertThat(fb2).isNotSameAs(fb1);
        assertThat(fb1.isHudScheduled()).as("旧 HUD 任务已取消").isFalse();
        assertThat(fb2.isHudScheduled()).as("新 HUD 任务已建").isTrue();
        assertThat(fb1.bossbarsView()).as("旧实例 BossBar 映射已清空").isEmpty();
        assertThat(oldBar.getPlayers()).as("玩家已从旧 BossBar 移除").isEmpty();
        assertThat(oldBar.isVisible()).isFalse();
        assertThat(fb2.bossbarsView()).as("新实例干净起步（腐蚀经 PDC 续接 55）")
                .doesNotContainKey(player.getUniqueId());
        assertThat(plugin.services().corrosionMeter().get(player.getUniqueId())).isEqualTo(55.0);

        fb2.run(); // 新一轮渲染：新实例建自己的条
        BossBar newBar = fb2.bossbarsView().get(player.getUniqueId());
        assertThat(newBar).isNotSameAs(oldBar);
        assertThat(newBar.getProgress()).isEqualTo(0.55);
    }

    @Test
    @DisplayName("退服清理：BossBar 移除、map 无残留；onDisable 同样全清")
    void quitAndDisableCleanBossbars() {
        applyConfigVariant(plugin, configYamlWith(
                "bossbar-corrosion: false", "bossbar-corrosion: true"));
        PlayerMock staying = server.addPlayer("Staying");
        PlayerMock leaving = server.addPlayer("Leaving");
        FeedbackService fb = plugin.feedback();
        fb.run();
        assertThat(fb.bossbarsView()).hasSize(2);

        leaving.disconnect();
        assertThat(fb.bossbarsView()).hasSize(1).containsKey(staying.getUniqueId());

        server.getPluginManager().disablePlugin(plugin);
        assertThat(fb.bossbarsView()).isEmpty();
        assertThat(plugin.feedback()).isNull();
        assertThat(Bukkit.getOnlinePlayers()).isNotEmpty(); // 环境完好，仅插件层清理
    }
}
