package com.ft.radsurvival.mockbukkit;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.core.DustImmunity;
import com.ft.radsurvival.core.RadiationServices;
import com.ft.radsurvival.item.DustCraftStructure;
import com.ft.radsurvival.item.DustItemFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全代码排查（bug hunt）确认缺陷的复现测试。
 *
 * <p>本类的每个 {@code @Nested} 对应排查报告中的一个独立结论，测试名直接写明
 * <b>缺陷现象</b>，因此在缺陷被修复后会立刻转红——这是回归护栏的意图：
 * 断言的是「当前代码真的会这样」，不是「我们希望它这样」。</p>
 *
 * <p>修复缺陷时请把这些断言改成期望行为（并保留 {@code DisplayName} 里的缺陷编号，
 * 便于在 CHANGELOG/ACCEPTANCE 里追溯）。</p>
 */
class BugHuntRegressionTest {

    private ServerMock server;
    private WorldMock world;
    private RadSurvivalPlugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.load(RadSurvivalPlugin.class);
        player = server.addPlayer("Hunter");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Block buildStructure(Material coreType) {
        Block core = world.getBlockAt(100, 64, 100);
        core.setType(coreType);
        core.getRelative(BlockFace.UP).setType(Material.ANVIL);
        for (BlockFace face : DustCraftStructure.RING_FACES) {
            core.getRelative(face).setType(Material.OAK_LOG);
        }
        return core;
    }

    private void putCenter(Block core, Material material, int amount) {
        Inventory inv = DustCraftStructure.inventoryOf(core);
        assertThat(inv).as("核心方块应有实时容器").isNotNull();
        inv.setItem(DustCraftStructure.CENTER_SLOT, new ItemStack(material, amount));
    }

    // ================================================================ BUG-1（已修复）

    @Nested
    @DisplayName("BUG-1【已修复】背包满时不再刷药：整单失败，产出与支付同生共死")
    class BackpackFullDuplicateDust {

        @Test
        @DisplayName("修复后：背包满 → 零产出、零掉落、作物不扣；3 次重试仍然零产出")
        void fullInventoryYieldsNothingAtAll() {
            Block core = buildStructure(Material.DISPENSER);
            putCenter(core, Material.WHEAT, 2);
            for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
                player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
            }

            assertThat(plugin.dustGui().craft(player, core, core.getRelative(BlockFace.UP))).isZero();
            plugin.dustGui().craft(player, core, core.getRelative(BlockFace.UP));
            plugin.dustGui().craft(player, core, core.getRelative(BlockFace.UP));

            assertThat(world.getEntitiesByClass(org.bukkit.entity.Item.class))
                    .as("掉在地上的药水也算「已产出」——只要作物没扣就是刷药，必须一滴都不掉")
                    .isEmpty();
            assertThat(DustCraftStructure.centerItem(core).getAmount())
                    .as("作物一个不少").isEqualTo(2);
        }
    }

    // ================================================================ BUG-2

    @Nested
    @DisplayName("BUG-2【P1】任意发射器/投掷器都被当成制药台（不看结构）")
    class NonStructureDispenserIsHijacked {

        @Test
        @DisplayName("完全没有铁砧与原木环的裸发射器：UI 照样被「只收聚合物」规则接管")
        void bareDispenserContainerIsStillRestricted() {
            // 随便找个空地放一台孤立发射器（没有铁砧、没有原木环）
            Block bare = world.getBlockAt(300, 64, 300);
            bare.setType(Material.DISPENSER);
            assertThat(DustCraftStructure.inspect(bare).ok())
                    .as("结构显然不成立").isFalse();

            Inventory bareInv = DustCraftStructure.inventoryOf(bare);
            assertThat(bareInv).isNotNull();
            player.openInventory(bareInv);
            Inventory top = player.getOpenInventory().getTopInventory();

            // 试图把矿石放进中心格（正常容器应允许）
            ItemStack diamond = new ItemStack(Material.DIAMOND, 5);
            player.setItemOnCursor(diamond);
            var click = new org.bukkit.event.inventory.InventoryClickEvent(
                    player.getOpenInventory(), org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
                    DustCraftStructure.CENTER_SLOT, org.bukkit.event.inventory.ClickType.LEFT, null);
            server.getPluginManager().callEvent(click);

            // —— 修复后：非结构容器退回原版行为，放入矿石不再被取消
            assertThat(click.isCancelled())
                    .as("非结构发射器不该被制药台规则接管")
                    .isFalse();
            assertThat(top).isNotNull();
        }

        @Test
        @DisplayName("结构成立后同一容器才切换到「只收农作物」：非法物品被拒、合法作物放行")
        void structuredDispenserStillRestricts() {
            Block core = buildStructure(Material.DISPENSER);
            assertThat(DustCraftStructure.inspect(core).ok()).isTrue();

            player.openInventory(DustCraftStructure.inventoryOf(core));
            player.setItemOnCursor(new ItemStack(Material.DIAMOND, 5));
            var badClick = new org.bukkit.event.inventory.InventoryClickEvent(
                    player.getOpenInventory(), org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
                    DustCraftStructure.CENTER_SLOT, org.bukkit.event.inventory.ClickType.LEFT, null);
            server.getPluginManager().callEvent(badClick);
            assertThat(badClick.isCancelled()).as("结构成立 → 只收农作物").isTrue();

            player.setItemOnCursor(new ItemStack(Material.WHEAT, 5));
            var goodClick = new org.bukkit.event.inventory.InventoryClickEvent(
                    player.getOpenInventory(), org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
                    DustCraftStructure.CENTER_SLOT, org.bukkit.event.inventory.ClickType.LEFT, null);
            server.getPluginManager().callEvent(goodClick);
            assertThat(goodClick.isCancelled()).as("合法作物必须放行").isFalse();
        }
    }

    // ================================================================ BUG-3

    @Nested
    @DisplayName("BUG-3【已修复】配置键拼写：正确拼写生效，历史误拼仍兼容")
    class MisspelledConfigKey {

        @Test
        @DisplayName("仓库 config.yml 已使用正确拼写 hud-immune-mark")
        void repoConfigUsesCorrectSpelling() {
            String yaml = Support.repoConfigYaml();
            assertThat(yaml)
                    .as("仓库默认配置必须是正确拼写")
                    .contains("hud-immune-mark");
        }

        @Test
        @DisplayName("两种拼写都被接受：只写正确拼写不再被静默忽略")
        void bothSpellingsAccepted() {
            // —— 正确拼写单独存在（仓库默认就是这种）→ 生效
            var correct = Support.loadConfigYaml(Support.repoConfigYaml());
            assertThat(correct.errors()).as("仓库默认配置必须零错误").isEmpty();
            assertThat(correct.config().antiDust().hudImmuneMark()).isEqualTo("◆抗尘");

            // —— 自定义值也生效：证明读的确实是这个键，而不是碰巧命中默认值
            var custom = Support.loadConfigWith("anti_dust", "hud-immune-mark", "\"★正确\"");
            assertThat(custom.errors()).isEmpty();
            assertThat(custom.config().antiDust().hudImmuneMark())
                    .as("修复前写正确拼写会被静默忽略并回退默认值 \"◆抗尘\"")
                    .isEqualTo("★正确");

            // —— 历史误拼单独存在 → 仍然生效（向后兼容老服配置）
            String legacyYaml = Support.configYamlWith(
                    "  hud-immune-mark: \"◆抗尘\"", "  hud-immume-mark: \"◆兼容\"");
            var legacy = Support.loadConfigYaml(legacyYaml);
            assertThat(legacy.errors()).as("历史拼写不应触发错误").isEmpty();
            assertThat(legacy.config().antiDust().hudImmuneMark()).isEqualTo("◆兼容");
        }

        @Test
        @DisplayName("两种拼写同时存在：以正确拼写为准，并给出明确提示")
        void bothPresentPrefersCorrectSpelling() {
            String bothYaml = Support.configYamlWith(
                    "  hud-immune-mark: \"◆抗尘\"",
                    "  hud-immune-mark: \"◆新的\"\n  hud-immume-mark: \"◆旧的\"");
            var both = Support.loadConfigYaml(bothYaml);

            String all = String.join(" ", both.errors());
            assertThat(all)
                    .as("两个键并存必须提示服主，否则他改了旧键发现没生效会困惑")
                    .contains("hud-immume-mark");
            // 错误信息里已写明「当前已按 hud-immune-mark 生效」，且提示是唯一的一条
            assertThat(both.errors()).hasSize(1);
        }
    }

    // ================================================================ BUG-4

    @Nested
    @DisplayName("BUG-4【P0/P1】重载（reload）整容器重建 = 全场所有人抗尘埃药免疫被静默清空")
    class ReloadWipesImmunity {

        @Test
        @DisplayName("grant 30s 免疫后执行 /rad reload → 免疫窗口归零（DustImmunity 随容器重建）")
        void reloadClearsEveryonesImmunity() {
            RadiationServices before = plugin.services();
            DustImmunity immunity = before.dustImmunity();
            immunity.grant(player.getUniqueId(), 30);
            assertThat(immunity.isImmune(player.getUniqueId())).isTrue();

            server.dispatchCommand(server.getConsoleSender(), "rad reload");

            RadiationServices after = plugin.services();
            assertThat(after).as("reload 产出了新容器").isNotSameAs(before);
            // —— 缺陷现场：玩家喝下的 30s 免疫在 reload 后消失，而玩家没有任何提示
            assertThat(after.dustImmunity().isImmune(player.getUniqueId()))
                    .as("reload 后免疫窗口被清空，玩家仍在窗口内却立刻开始吃伤害")
                    .isFalse();
            assertThat(after.dustImmunity().remainingSeconds(player.getUniqueId())).isZero();
        }
    }

    // ================================================================ BUG-5（已修复）

    @Nested
    @DisplayName("BUG-5【已修复】/rad status <他人> 不再泄露对方盔甲减免与预计伤害")
    class StatusLeaksArmorOfTarget {

        @Test
        @DisplayName("普通玩家查他人：盔甲减免/最低杀伤/预计伤害三行被遮蔽；查自己正常显示")
        void equipmentDetailsHiddenFromNonAdmin() {
            PlayerMock victim = server.addPlayer("Victim");
            Support.equipFullSet(victim, "NETHERITE");
            PlayerMock snoop = server.addPlayer("Snoop");

            // 底层预览本身仍能精确算出 72%（业务内核不受影响）
            var outcome = plugin.services().radiationTask().previewRound(victim);
            assertThat(outcome.armorReduction()).isCloseTo(0.72, org.assertj.core.api.Assertions.within(1e-9));

            // —— 普通玩家查他人：不得出现 72% 这类装备细节
            snoop.performCommand("rad status Victim");
            String seen = drainMessages(snoop);
            assertThat(seen).as("不得泄露他人盔甲减免").doesNotContain("72%");
            assertThat(seen).contains("他人装备细节仅管理员可见");

            // —— 查自己：自己的装备信息应当可见
            Support.equipFullSet(snoop, "NETHERITE");
            snoop.performCommand("rad status");
            String self = drainMessages(snoop);
            assertThat(self).as("查自己应看到自己的减免").contains("72%");
        }

        private String drainMessages(PlayerMock p) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = p.nextMessage()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    // ================================================================ BUG-6（已修复）

    @Nested
    @DisplayName("BUG-6【已修复】/rad force-check 需要管理权限：普通玩家无法触发真实结算")
    class ForceCheckIsFree {

        @Test
        @DisplayName("普通玩家 force-check 被拒，腐蚀零推进；管理员仍可正常强制结算")
        void forceCheckRequiresAdmin() {
            Support.Fixture fixture = Support.install(plugin, server);
            Support.resetCombat(fixture, server);
            ServerMock unused = server; // 保持夹具语义清晰
            PlayerMock casual = server.addPlayer("Casual");
            assertThat(casual.hasPermission("radsurvival.admin")).isFalse();

            casual.performCommand("rad force-check");
            assertThat(fixture.corrosion(casual)).as("被拒 → 腐蚀不得推进").isZero();
            assertThat(fixture.damages).as("被拒 → 不得真实掉血").isEmpty();

            // —— 管理员放行：仍然是一次真正的结算
            PlayerMock admin = server.addPlayer("Boss");
            admin.addAttachment(plugin, "radsurvival.admin", true);
            admin.performCommand("rad force-check");
            assertThat(fixture.corrosion(admin)).as("管理员可强制结算").isGreaterThan(0.0);
            assertThat(fixture.damages).as("管理员路径仍走真实 DamageApplier").hasSize(1);
        }
    }
}
