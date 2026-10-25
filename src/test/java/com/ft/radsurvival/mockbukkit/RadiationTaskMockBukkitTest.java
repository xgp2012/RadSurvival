package com.ft.radsurvival.mockbukkit;

import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.core.RadiationTask.RoundOutcome;
import com.ft.radsurvival.core.RadiationTask.RoundResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static com.ft.radsurvival.mockbukkit.Support.configYamlWith;

/**
 * 主循环结算集成测试（plan.md §5 M2「MockBukkit 跑监听器与 tick 推进」、§1.3 过滤链与免疫短路）。
 *
 * <p>无敌帧口径：MockBukkit 不模拟 20tick 伤害免疫窗口（{@code noDamageTicks} 恒 0、
 * 连续两击都掉血——能力钉死测试已验证），故本类按 §5.2 预案退化为
 * 「每轮 damage() 调用 + 事件链完整 + 血量逐轮下降」断言；真实无敌帧行为移交 M4 实机。</p>
 */
class RadiationTaskMockBukkitTest {

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

    // ------------------------------------------------------------ 每轮真实掉血链

    @Test
    @DisplayName("白天露天连续 10 轮：每轮事件广播 + damage() 调用且伤害 1.01→1.10 递增、血量逐轮下降")
    void dayLoopTenRoundsEveryRoundDamaged() {
        PlayerMock player = server.addPlayer("Daywalker");
        Support.resetCombat(fixture, server);

        for (int round = 1; round <= 10; round++) {
            server.getScheduler().performTicks(20);
            assertThat(fixture.damages).as("第 %d 轮应发生 damage() 调用", round).hasSize(round);
            assertThat(fixture.events).as("第 %d 轮事件链完整", round).hasSize(round);
            double expected = 1.0 * (1.0 + round / 100.0); // base1.0 × tf1.0 × (1+腐蚀k%)
            assertThat(fixture.damages.get(round - 1).amount())
                    .isCloseTo(expected, within(1e-9));
            assertThat(player.getHealth())
                    .as("血量单调下降（每轮 ≥1 点）")
                    .isLessThan(20.0 - round + 1e-9)
                    .isCloseTo(20.0 - (round + round * (round + 1) / 200.0), within(1e-9));
            assertThat(fixture.corrosion(player)).isCloseTo(round, within(1e-9));
        }
    }

    @Test
    @DisplayName("夜晚（time 13000，硬切换夜档）：伤害与累积都 ×0.5")
    void nightHalvesEverything() {
        PlayerMock player = server.addPlayer("NightOwl");
        player.getWorld().setTime(13000L); // §1.2 夜晚段（smooth 开/关此点都 = 0.5）
        Support.resetCombat(fixture, server);

        server.getScheduler().performTicks(20);

        assertThat(fixture.damages).hasSize(1);
        assertThat(fixture.lastDamageAmount()).isCloseTo(0.5 * 1.005, within(1e-9));
        assertThat(fixture.corrosion(player)).isCloseTo(0.5, within(1e-9));
        assertThat(player.getHealth()).isCloseTo(20.0 - 0.5 * 1.005, within(1e-9));
    }

    // ------------------------------------------------------ 免疫/遮蔽（零掉血零事件）

    @Test
    @DisplayName("v1.2 全套下界合金不再免疫：正常掉血、正常广播事件、腐蚀照常累积")
    void fullNetheriteNoLongerImmune() {
        PlayerMock player = server.addPlayer("Tank");
        fixture.meter().setValue(player.getUniqueId(), 50.0);
        Support.equipFullSet(player, "NETHERITE");
        Support.resetCombat(fixture, server);

        server.getScheduler().performTicks(40); // 2 轮

        // 旧断言（零掉血/零事件/腐蚀不动）已反转：72% 减免只是减伤，不是免疫
        assertThat(fixture.damages).hasSize(2);
        assertThat(fixture.events).hasSize(2);
        assertThat(player.getHealth()).isLessThan(20.0);
        // armor-affects-accum=true → 累积速率 ×(1−0.72)=0.28，腐蚀仍会涨
        assertThat(fixture.corrosion(player)).isGreaterThan(50.0);
        // 每轮伤害 = base × 1.0 × mult × (1−0.72)；这里污染起始腐蚀为 50% → mult = 1.5
        // → dmg = 1 × 1.0 × 1.5 × 0.28 = 0.42（先累后打，第二轮再略高）
        assertThat(fixture.damages.get(0).amount()).isCloseTo(1.5 * (1 - 0.72), within(1e-3));
        assertThat(fixture.damages.get(0).playerName()).isEqualTo("Tank");
        // 第二轮腐蚀已涨（先累后打）→ mult>1 → 伤害略高于第一轮
        assertThat(fixture.damages.get(1).amount()).isGreaterThan(fixture.damages.get(0).amount());
    }

    @Test
    @DisplayName("v1.2 抗尘埃药免疫：窗口内零掉血零事件（唯一的免疫途径）")
    void dustImmunitySuppressesDamage() {
        PlayerMock player = server.addPlayer("Dusted");
        fixture.meter().setValue(player.getUniqueId(), 50.0);
        Support.resetCombat(fixture, server);

        // 直接登记免疫窗口（绕开合成/服药链路，只验证结算链的免疫分支）
        plugin.services().dustImmunity().grant(player.getUniqueId(), 30);

        server.getScheduler().performTicks(40); // 2 轮

        assertThat(fixture.damages).isEmpty();
        assertThat(fixture.events).isEmpty();
        assertThat(player.getHealth()).isEqualTo(20.0);

        // 窗口手动结束后立刻恢复掉血（证明免疫是唯一抑制因素）
        plugin.services().dustImmunity().clear(player.getUniqueId());
        Support.resetCombat(fixture, server);
        server.getScheduler().performTicks(20);
        assertThat(fixture.damages).hasSize(1);
    }

    @Test
    @DisplayName("遮蔽分支：零伤害零事件、腐蚀按 regen 1%/s 回落；豁免维度走真实 SunExposureChecker 同分支")
    void coveredAndExemptDimensionRecover() {
        PlayerMock covered = server.addPlayer("Miner");
        fixture.exposed.set(false);
        fixture.meter().setValue(covered.getUniqueId(), 30.0);
        server.getScheduler().performTicks(20);
        assertThat(fixture.damages).isEmpty();
        assertThat(fixture.events).isEmpty();
        assertThat(fixture.corrosion(covered)).isCloseTo(29.0, within(1e-9));
        fixture.exposed.set(true);

        // 真实接线验证：NETHER 世界名入白名单 → 生产露天解析器在维度过滤处短路（不触方块）
        MockBukkit.unmock();
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RadSurvivalPlugin.class);
        Support.applyConfigVariant(plugin, configYamlWith(
                "enabled-worlds: [world]", "enabled-worlds: [world, world_nether]"));
        World nether = server.createWorld(new WorldCreator("world_nether")
                .environment(World.Environment.NETHER));
        PlayerMock piglin = server.addPlayer("Piglin");
        piglin.teleport(nether.getSpawnLocation());
        plugin.services().corrosionMeter().setValue(piglin.getUniqueId(), 50.0);
        RoundOutcome outcome = plugin.radiationTask().settleRound(piglin);
        assertThat(outcome.result()).isEqualTo(RoundResult.COVERED);
        assertThat(outcome.exposed()).isFalse();
        assertThat(plugin.services().corrosionMeter().get(piglin.getUniqueId()))
                .as("豁免维度恢复 -1%（生产解析器 + 真 checker 维度短路）")
                .isCloseTo(49.0, within(1e-9));
    }

    // ------------------------------------------------------------------ 过滤链

    @Test
    @DisplayName("CREATIVE/SPECTATOR 无条件跳过：零副作用且腐蚀冻结")
    void creativeAndSpectatorAlwaysSkipped() {
        PlayerMock creative = server.addPlayer("CreativeGuy");
        creative.setGameMode(GameMode.CREATIVE);
        fixture.meter().setValue(creative.getUniqueId(), 40.0);

        RoundOutcome outcome = fixture.task().settleRound(creative);
        assertThat(outcome.result()).isEqualTo(RoundResult.SKIPPED_CREATIVE_SPECTATOR);
        assertThat(fixture.damages).isEmpty();
        assertThat(fixture.corrosion(creative)).isEqualTo(40.0); // 不结算不累积不恢复

        PlayerMock spectator = server.addPlayer("Ghost");
        spectator.setGameMode(GameMode.SPECTATOR);
        assertThat(fixture.task().settleRound(spectator).result())
                .isEqualTo(RoundResult.SKIPPED_CREATIVE_SPECTATOR);
    }

    @Test
    @DisplayName("damage-game-modes 白名单：变体配置排除 ADVENTURE → 跳过；SURVIVAL 照常结算")
    void gameModeWhitelistVariant() {
        MockBukkit.unmock();
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RadSurvivalPlugin.class);
        Support.applyConfigVariant(plugin, configYamlWith(
                "damage-game-modes: [SURVIVAL, ADVENTURE]", "damage-game-modes: [SURVIVAL]"));
        fixture = Support.install(plugin, server);

        PlayerMock adventure = server.addPlayer("AdventureAl");
        adventure.setGameMode(GameMode.ADVENTURE);
        assertThat(fixture.task().settleRound(adventure).result())
                .isEqualTo(RoundResult.SKIPPED_GAME_MODE);
        assertThat(fixture.damages).isEmpty();

        PlayerMock survival = server.addPlayer("SurvivalSam");
        survival.setGameMode(GameMode.SURVIVAL);
        assertThat(fixture.task().settleRound(survival).result()).isEqualTo(RoundResult.DAMAGED);
        assertThat(fixture.damages).hasSize(1);
    }

    @Test
    @DisplayName("radsurvival.exempt 豁免：不结算不累积（addAttachment 授权，冻结腐蚀值）")
    void exemptPermissionFreezesEverything() {
        PlayerMock vault = server.addPlayer("VaultKeeper");
        vault.addAttachment(plugin, "radsurvival.exempt", true);
        assertThat(vault.hasPermission("radsurvival.exempt")).isTrue();
        fixture.meter().setValue(vault.getUniqueId(), 70.0);

        assertThat(fixture.task().settleRound(vault).result()).isEqualTo(RoundResult.SKIPPED_EXEMPT);
        server.getScheduler().performTicks(20);
        assertThat(fixture.damages).isEmpty();
        assertThat(fixture.events).isEmpty();
        assertThat(fixture.corrosion(vault)).isEqualTo(70.0);
    }

    @Test
    @DisplayName("require-on-ground=true 变体：空中跳过、着地结算（§1.3 字面口径）")
    void requireOnGroundVariant() {
        MockBukkit.unmock();
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RadSurvivalPlugin.class);
        Support.applyConfigVariant(plugin, configYamlWith(
                "require-on-ground: false", "require-on-ground: true"));
        fixture = Support.install(plugin, server);

        PlayerMock jumper = server.addPlayer("Jumper");
        jumper.setOnGround(false);
        assertThat(fixture.task().settleRound(jumper).result())
                .isEqualTo(RoundResult.SKIPPED_NOT_ON_GROUND);
        assertThat(fixture.damages).isEmpty();

        jumper.setOnGround(true);
        assertThat(fixture.task().settleRound(jumper).result()).isEqualTo(RoundResult.DAMAGED);
        assertThat(fixture.damages).hasSize(1);
    }

    @Test
    @DisplayName("enabled-worlds 之外世界：整轮冻结（零事件零腐蚀变化）")
    void disabledWorldFreezes() {
        World lobby = server.createWorld(new WorldCreator("lobby")
                .environment(World.Environment.NORMAL));
        PlayerMock visitor = server.addPlayer("Visitor");
        visitor.teleport(lobby.getSpawnLocation());
        fixture.meter().setValue(visitor.getUniqueId(), 12.0);

        RoundOutcome outcome = fixture.task().settleRound(visitor);
        assertThat(outcome.result()).isEqualTo(RoundResult.SKIPPED_DISABLED_WORLD);
        assertThat(fixture.damages).isEmpty();
        assertThat(fixture.events).isEmpty();
        assertThat(fixture.corrosion(visitor)).isEqualTo(12.0);
    }

    // ---------------------------------------------------------------- 状态镜像

    @Test
    @DisplayName("PlayerRadState 每轮镜像：exposed/timeFactor/减免/腐蚀/lastDamage 全字段更新")
    void stateMirrorsEveryRound() {
        PlayerMock player = server.addPlayer("Mirror");
        player.getWorld().setTime(13500L);
        server.getScheduler().performTicks(20);
        var state = fixture.task().stateOf(player.getUniqueId());
        assertThat(state).isNotNull();
        assertThat(state.isExposed()).isTrue();
        assertThat(state.getTimeFactor()).isCloseTo(0.5, within(1e-9));
        assertThat(state.getArmorReduction()).isZero();
        assertThat(state.getCorrosion()).isCloseTo(0.5, within(1e-9));
        assertThat(state.getLastDamage()).isCloseTo(0.5 * 1.005, within(1e-9));
        assertThat(state.getLastSettleStamp()).isPositive();
    }

    @Test
    @DisplayName("预览 previewRound 零副作用：不步进腐蚀、不广播事件、不掉血")
    void previewHasNoSideEffects() {
        PlayerMock player = server.addPlayer("Viewer");
        fixture.meter().setValue(player.getUniqueId(), 20.0);
        RoundOutcome preview = fixture.task().previewRound(player);
        assertThat(preview.result()).isEqualTo(RoundResult.DAMAGED);
        assertThat(preview.damageApplied()).isFalse();
        assertThat(preview.damage()).isCloseTo(1.0 * 1.2, within(1e-9)); // 腐蚀20%→×1.2
        assertThat(preview.corrosionBefore()).isEqualTo(preview.corrosionAfter());
        assertThat(fixture.damages).isEmpty();
        assertThat(fixture.events).isEmpty();
        assertThat(player.getHealth()).isEqualTo(20.0);
    }

    @Test
    @DisplayName("v1.2 钻石胸甲+铁护腿混合盔甲：减免按 Σ权重×系数 进入公式（新系数 0.46×0.60 + 0.30×0.48）")
    void mixedArmorReduction() {
        PlayerMock player = server.addPlayer("HalfPlate");
        player.getInventory().setChestplate(new org.bukkit.inventory.ItemStack(
                org.bukkit.Material.DIAMOND_CHESTPLATE)); // v1.2: 0.46×0.60 = 0.276
        player.getInventory().setLeggings(new org.bukkit.inventory.ItemStack(
                org.bukkit.Material.IRON_LEGGINGS));      // v1.2: 0.30×0.48 = 0.144 → Σ=0.42
        RoundOutcome outcome = fixture.task().settleRound(player);
        double expectedReduction = 0.46 * 0.60 + 0.30 * 0.48;
        assertThat(outcome.armorReduction()).isCloseTo(expectedReduction, within(1e-9));
        assertThat(outcome.result()).isEqualTo(RoundResult.DAMAGED);
        // 先累后打：本轮腐蚀 ≈ (1−0.42) = 0.58%（rate=1×1×(1−0.42)）→ mult 略大于 1 → dmg 贴近 0.58
        double after = outcome.corrosionAfter();
        assertThat(after).isCloseTo(1.0 * (1 - expectedReduction), within(1e-9));
        double mult = com.ft.radsurvival.core.RadiationMath.corrosionMultiplier(
                after, plugin.config().corrosion().maxMultiplier(),
                plugin.config().corrosion().curve());
        assertThat(outcome.damage()).isCloseTo(mult * (1 - expectedReduction), within(1e-9));
    }

    // ---------------------------------------------------------------- 每轮 PDC 同步（§2 抗崩溃）

    @Test
    @DisplayName("腐蚀值每轮变化即同步 Player PDC（不等 quit/death；崩溃也不丢最近结算值）")
    void corrosionSyncedToPdcEveryRound() {
        PlayerMock player = server.addPlayer("Durable");
        Support.resetCombat(fixture, server);
        // 第 1 轮：0→1.0，PDC 应立刻可见 1.0
        fixture.task().settleRound(player);
        assertThat(player.getPersistentDataContainer()
                .get(com.ft.radsurvival.core.CorrosionMeter.CORROSION_PDC_KEY,
                        org.bukkit.persistence.PersistentDataType.DOUBLE))
                .isCloseTo(1.0, within(1e-9));
        // 第 2 轮：1.0→2.0，PDC 跟进 2.0
        fixture.task().settleRound(player);
        assertThat(player.getPersistentDataContainer()
                .get(com.ft.radsurvival.core.CorrosionMeter.CORROSION_PDC_KEY,
                        org.bukkit.persistence.PersistentDataType.DOUBLE))
                .isCloseTo(2.0, within(1e-9));
        // 豁免权限 → 完全不结算（腐蚀不步进、PDC 不动）——替代旧的「下界合金免疫」写法
        player.addAttachment(plugin, "radsurvival.exempt", true);
        fixture.task().settleRound(player);
        assertThat(player.getPersistentDataContainer()
                .get(com.ft.radsurvival.core.CorrosionMeter.CORROSION_PDC_KEY,
                        org.bukkit.persistence.PersistentDataType.DOUBLE))
                .isCloseTo(2.0, within(1e-9));
    }

    @Test
    @DisplayName("遮蔽恢复轮同样同步 PDC（100→99 即写，室内挂机崩溃不丢进度）")
    void coveredRecoveryAlsoSyncedToPdc() {
        PlayerMock player = server.addPlayer("Cellar");
        fixture.meter().setValue(player.getUniqueId(), 100.0);
        fixture.exposed.set(false);
        fixture.task().settleRound(player);
        assertThat(fixture.corrosion(player)).isCloseTo(99.0, within(1e-9));
        assertThat(player.getPersistentDataContainer()
                .get(com.ft.radsurvival.core.CorrosionMeter.CORROSION_PDC_KEY,
                        org.bukkit.persistence.PersistentDataType.DOUBLE))
                .isCloseTo(99.0, within(1e-9));
    }
}
