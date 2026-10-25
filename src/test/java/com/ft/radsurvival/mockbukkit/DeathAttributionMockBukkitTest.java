package com.ft.radsurvival.mockbukkit;

import java.util.List;

import net.kyori.adventure.text.Component;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.core.CorrosionMeter;
import com.ft.radsurvival.feedback.FeedbackService;
import com.ft.radsurvival.listener.DamageTagListener;
import com.ft.radsurvival.listener.DamageTagListener.RadiationHit;

import static org.assertj.core.api.Assertions.assertThat;
import static com.ft.radsurvival.mockbukkit.Support.applyConfigVariant;
import static com.ft.radsurvival.mockbukkit.Support.configYamlWith;

/**
 * 死亡文案归因测试（M3 验收「死亡归因正/反例」，plan.md §2 DamageTagListener/§1.5）：
 * 辐射致死（掉血归因 + GENERIC 无实体归因双判据）→ 废土风文案含死亡瞬间腐蚀值；
 * 非辐射死因（其他伤害类型 / 有实体归因 / 无归因记录 / 超时间窗）→ 绝不误改。
 *
 * <p>归因记录注入走 FeedbackService 的公开挂点 {@code onRadiationDamage}
 * （= DamageApplier 真实掉血后的回调入口，MockBukkit 不实现 damage(double, DamageSource)，
 * 生产接线由 M2b 事件链测试覆盖，此处钉「记录→死亡事件→文案」集成链路）。</p>
 */
class DeathAttributionMockBukkitTest {

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

    private PlayerDeathEvent fireDeath(PlayerMock victim, DamageSource source) {
        PlayerDeathEvent event = new PlayerDeathEvent(
                victim, source, List.<ItemStack>of(), 0, Component.text("vanilla death text"), false);
        server.getPluginManager().callEvent(event);
        return event;
    }

    // ------------------------------------------------------------------ 正例

    @Test
    @DisplayName("辐射致死：文案替换（LOWEST 先读死亡瞬间腐蚀值），随后 NORMAL 会话监听清零")
    void radiationKillReplacesMessageWithDeathMomentCorrosion() {
        PlayerMock victim = server.addPlayer("Sunbather");
        plugin.api().setCorrosion(victim, 88.0);
        plugin.feedback().onRadiationDamage(victim, 1.32); // 模拟击杀轮真实掉血后的挂点

        PlayerDeathEvent event = fireDeath(victim, DamageSource.builder(DamageType.GENERIC).build());

        String message = event.getDeathMessage();
        assertThat(message).startsWith("§8[§c辐照§8]");
        assertThat(message).contains("Sunbather");
        assertThat(message).contains("腐蚀 88%"); // LOWEST 早于 PlayerSessionListener 的 reset
        assertThat(plugin.services().corrosionMeter().get(victim.getUniqueId()))
                .as("会话监听（NORMAL）随后按 reset-on-death 清零").isZero();
    }

    @Test
    @DisplayName("反馈全关（actionbar/particles/sound=false）归因记录仍写入 → 文案仍替换")
    void attributionRecordSurvivesFeedbackSwitches() {
        applyConfigVariant(plugin, configYamlWith(
                "  actionbar: true", "  actionbar: false",
                "  particles: true", "  particles: false",
                "  sound: true", "  sound: false"));
        PlayerMock victim = server.addPlayer("MutedVictim");
        FeedbackService fb = plugin.feedback();
        fb.onRadiationDamage(victim, 1.0);

        assertThat(fb.lastRadiationHit(victim.getUniqueId())).isNotNull();
        assertThat(victim.getHeardSounds()).as("音效开关关闭").isEmpty();

        PlayerDeathEvent event = fireDeath(victim, DamageSource.builder(DamageType.GENERIC).build());
        assertThat(event.getDeathMessage()).contains("辐照");
    }

    @Test
    @DisplayName("feedback.death-message=false → 双判据齐全也不改文案（开关生效）")
    void deathMessageSwitchDisablesReplacement() {
        applyConfigVariant(plugin, configYamlWith(
                "death-message: true", "death-message: false"));
        PlayerMock victim = server.addPlayer("SilentDeath");
        plugin.feedback().onRadiationDamage(victim, 1.0);
        plugin.api().setCorrosion(victim, 70.0);

        PlayerDeathEvent event = fireDeath(victim, DamageSource.builder(DamageType.GENERIC).build());
        assertThat(event.getDeathMessage()).doesNotContain("辐照");
    }

    // ------------------------------------------------------------------ 反例（绝不误伤）

    @Test
    @DisplayName("反例①：刚有辐射掉血记录，但死于带实体归因的攻击 → 不改")
    void entityAttributedKillNotTaggedEvenWithRecentHit() {
        PlayerMock victim = server.addPlayer("PvpVictim");
        PlayerMock attacker = server.addPlayer("Raidacker");
        plugin.feedback().onRadiationDamage(victim, 1.0); // 记录在窗口内

        DamageSource source = DamageSource.builder(DamageType.GENERIC)
                .withCausingEntity(attacker).build(); // 1.21.11 Builder 方法名：withCausingEntity
        PlayerDeathEvent event = fireDeath(victim, source);

        assertThat(event.getDeathMessage()).doesNotContain("辐照");
    }

    @Test
    @DisplayName("反例②：记录在窗口内但死因 FALL（非 GENERIC）→ 不改")
    void fallDeathNotTaggedEvenWithRecentHit() {
        PlayerMock victim = server.addPlayer("Clumsy");
        plugin.feedback().onRadiationDamage(victim, 1.0);

        PlayerDeathEvent event = fireDeath(victim, DamageSource.builder(DamageType.FALL).build());
        assertThat(event.getDeathMessage()).doesNotContain("辐照");
    }

    @Test
    @DisplayName("反例③：GENERIC 但从未有辐射掉血记录（如 /kill 型脚本死）→ 不改")
    void genericKillWithoutRecordNotTagged() {
        PlayerMock victim = server.addPlayer("Adminned");
        plugin.api().setCorrosion(victim, 95.0); // 高腐蚀但非辐射击杀

        PlayerDeathEvent event = fireDeath(victim, DamageSource.builder(DamageType.GENERIC).build());
        assertThat(event.getDeathMessage()).doesNotContain("辐照");
    }

    @Test
    @DisplayName("反例④（纯判据）：超时间窗 / 时钟回拨 / null 记录 → 一律不归因；fresh+GENERIC 才归因")
    void windowBoundariesOnPurePredicate() {
        long now = System.currentTimeMillis();
        long window = 20 * 50L + DamageTagListener.HIT_WINDOW_SLICE_MS; // tick-interval=20 口径

        assertThat(DamageTagListener.looksLikeRadiationKill(null, null, now, window)).isFalse();
        assertThat(DamageTagListener.looksLikeRadiationKill(
                new RadiationHit(now - window - 1, 1.0), null, now, window)).isFalse(); // 超窗
        assertThat(DamageTagListener.looksLikeRadiationKill(
                new RadiationHit(now + 1, 1.0), null, now, window)).isFalse();          // 未来戳=时钟异常
        assertThat(DamageTagListener.looksLikeRadiationKill(
                new RadiationHit(now - 100, 1.0), null, now, window)).isTrue();         // source 缺失仅凭窗
        assertThat(DamageTagListener.looksLikeRadiationKill(
                new RadiationHit(now - 100, 1.0),
                DamageSource.builder(DamageType.GENERIC).build(), now, window)).isTrue();
        assertThat(DamageTagListener.looksLikeRadiationKill(
                new RadiationHit(now - 100, 1.0),
                DamageSource.builder(DamageType.IN_FIRE).build(), now, window)).isFalse();
    }

    @Test
    @DisplayName("退服清归因：quit 后记录移除（Map 无泄漏，防复用窗口误伤）")
    void quitClearsAttributionRecord() {
        PlayerMock victim = server.addPlayer("Departing");
        plugin.feedback().onRadiationDamage(victim, 1.0);
        assertThat(plugin.feedback().lastRadiationHit(victim.getUniqueId())).isNotNull();

        victim.disconnect();
        assertThat(plugin.feedback().lastRadiationHit(victim.getUniqueId())).isNull();
    }

    // -------------------------------------------------------------- 纯文案口径

    @Test
    @DisplayName("废土风文案：显示取整（66.7→67）、阶段风味分支（<50 灰斑 / ≥50 崩解 / 0 曝干）")
    void flavorTextRoundingAndBands() {
        String high = DamageTagListener.radiationDeathMessage("A", 66.7);
        assertThat(high).contains("A").contains("腐蚀 67%").contains("崩解");

        String mid = DamageTagListener.radiationDeathMessage("B", 30.0);
        assertThat(mid).contains("辐射腐蚀 30%").contains("吞没");

        String zero = DamageTagListener.radiationDeathMessage("C", 0.0);
        assertThat(zero).contains("辐射腐蚀 0%").contains("曝干");
    }

    @Test
    @DisplayName("死亡瞬间值经时间窗读口与 tick-interval 联动（窗口 = interval×50ms + 500ms）")
    void windowScalesWithTickInterval() {
        assertThat(plugin.feedback().radiationWindowMillis())
                .isEqualTo(plugin.config().tickInterval() * 50L + DamageTagListener.HIT_WINDOW_SLICE_MS);
        assertThat(CorrosionMeter.PDC_KEY_STRING).isEqualTo("radsurvival:corrosion"); // 链路前提
    }
}
