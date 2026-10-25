package com.ft.radsurvival.mockbukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Dispenser;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.config.PluginConfig;
import com.ft.radsurvival.item.DustCraftStructure;
import com.ft.radsurvival.item.DustItemFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抗尘埃药端到端链路测试（v1.2 新增）：搭结构 → 放作物 → 木棍敲铁砧 → 产出药水 → 服用 → 免疫生效。
 *
 * <p>刻意走<b>真实方块</b>而不是纯逻辑替身：结构判定、容器 UI 读写、木棍触发顺序
 * 这三处的坑（相邻格顺序、容器 BlockState 失效、事件取消时机）只有真方块能暴露。</p>
 */
class AntiDustChainMockBukkitTest {

    private ServerMock server;
    private WorldMock world;
    private RadSurvivalPlugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.load(RadSurvivalPlugin.class);
        player = server.addPlayer("Alchemist");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** 在 (100, 64, 100) 处按规则搭一套完整结构，返回核心方块。 */
    private Block buildStructure(Material coreType) {
        Block core = world.getBlockAt(100, 64, 100);
        core.setType(coreType);
        core.getRelative(BlockFace.UP).setType(Material.ANVIL);
        for (BlockFace face : DustCraftStructure.RING_FACES) {
            core.getRelative(face).setType(Material.OAK_LOG);
        }
        return core;
    }

    /** 往核心方块 UI 中心格放物品（走实时容器，与生产代码同一条路径）。 */
    private void putCenter(Block core, Material material, int amount) {
        Inventory inv = DustCraftStructure.inventoryOf(core);
        assertThat(inv).as("核心方块应有实时容器").isNotNull();
        inv.setItem(DustCraftStructure.CENTER_SLOT, new ItemStack(material, amount));
    }

    private int countDust() {
        int n = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (DustItemFactory.isDust(stack)) {
                n += stack.getAmount();
            }
        }
        return n;
    }

    // -------------------------------------------------------------- 结构识别

    @Test
    @DisplayName("完整结构被识别（发射器与投掷器均可），缺铁砧/缺原木被拒")
    void structureRecognition() {
        Block core = buildStructure(Material.DISPENSER);
        assertThat(DustCraftStructure.inspect(core).ok()).isTrue();

        // 拆掉铁砧 → 不成立
        Block anvil = core.getRelative(BlockFace.UP);
        anvil.setType(Material.AIR);
        assertThat(DustCraftStructure.inspect(core).ok()).isFalse();
        anvil.setType(Material.ANVIL);

        // 拆掉东侧原木 → 不成立，且诊断点名 EAST
        Block east = core.getRelative(BlockFace.EAST);
        east.setType(Material.AIR);
        DustCraftStructure.Result missingLog = DustCraftStructure.inspect(core);
        assertThat(missingLog.ok()).isFalse();
        assertThat(missingLog.summary()).contains("EAST");
        east.setType(Material.BIRCH_LOG); // 换树种同样算
        assertThat(DustCraftStructure.inspect(core).ok()).isTrue();

        // 投掷器也认
        assertThat(DustCraftStructure.inspect(buildStructure(Material.DROPPER)).ok()).isTrue();
    }

    @Test
    @DisplayName("中心格读取：只有索引 4 被视为作物位（外围 8 格的物品不参与合成）")
    void centerItemIsSlotFour() {
        Block core = buildStructure(Material.DISPENSER);
        Inventory inv = DustCraftStructure.inventoryOf(core);
        assertThat(inv).isNotNull();
        inv.setItem(0, new ItemStack(Material.WHEAT, 5)); // 外围格
        assertThat(DustCraftStructure.centerItem(core)).isNull(); // 中心为空

        putCenter(core, Material.WHEAT, 3);
        ItemStack center = DustCraftStructure.centerItem(core);
        assertThat(center).isNotNull();
        assertThat(center.getType()).isEqualTo(Material.WHEAT);
        assertThat(center.getAmount()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- 合成产出

    @Test
    @DisplayName("木棍敲铁砧 → 产出对应档位药水，作物 -1，木棍不被消耗")
    void craftProducesPotion() {
        Block core = buildStructure(Material.DISPENSER);
        putCenter(core, Material.WHEAT, 2); // WHEAT → crude 档

        ItemStack stick = new ItemStack(Material.STICK, 1);
        player.getInventory().setItemInMainHand(stick);
        int made = plugin.dustGui().craft(player, core, core.getRelative(BlockFace.UP));

        assertThat(made).isEqualTo(1);
        assertThat(countDust()).isEqualTo(1);
        // 档位正确
        ItemStack potion = firstDust();
        assertThat(DustItemFactory.levelIdOf(potion)).isEqualTo("crude");
        // 作物只剩 1 个
        assertThat(DustCraftStructure.centerItem(core).getAmount()).isEqualTo(1);
        // 木棍没被消耗（需求明确要求钥匙不消耗）
        assertThat(player.getInventory().getItemInMainHand().getType()).isEqualTo(Material.STICK);
        assertThat(player.getInventory().getItemInMainHand().getAmount()).isEqualTo(1);
    }

    @Test
    @DisplayName("作物耗尽：中心格清空，再次敲不产出（不消耗任何东西）")
    void craftConsumesLastCrop() {
        Block core = buildStructure(Material.DISPENSER);
        putCenter(core, Material.GOLDEN_CARROT, 1); // refined 档

        assertThat(plugin.dustGui().craft(player, core, core.getRelative(BlockFace.UP))).isEqualTo(1);
        assertThat(firstDust().getType()).isEqualTo(Material.HONEY_BOTTLE);
        assertThat(DustItemFactory.levelIdOf(firstDust())).isEqualTo("refined");
        assertThat(DustCraftStructure.centerItem(core)).isNull(); // 1 个用完即清空

        // 再敲一次：中心空 → 失败且不产出
        assertThat(plugin.dustGui().craft(player, core, core.getRelative(BlockFace.UP))).isZero();
        assertThat(countDust()).isEqualTo(1);
    }

    @Test
    @DisplayName("结构不完整时敲铁砧：不产出、不扣作物（失败不惩罚）")
    void craftFailsOnIncompleteStructure() {
        Block core = buildStructure(Material.DISPENSER);
        putCenter(core, Material.WHEAT, 5);
        core.getRelative(BlockFace.UP).setType(Material.AIR); // 拆铁砧

        assertThat(plugin.dustGui().craft(player, core, core.getRelative(BlockFace.UP))).isZero();
        assertThat(countDust()).isZero();
        // 作物原封不动
        assertThat(DustCraftStructure.centerItem(core).getAmount()).isEqualTo(5);
    }

    @Test
    @DisplayName("中心放非作物：不产出、不扣物品（不允许拿发射器当万能加工台）")
    void craftRejectsNonCrop() {
        Block core = buildStructure(Material.DISPENSER);
        putCenter(core, Material.DIAMOND, 3);

        assertThat(plugin.dustGui().craft(player, core, core.getRelative(BlockFace.UP))).isZero();
        assertThat(countDust()).isZero();
        assertThat(DustCraftStructure.centerItem(core).getAmount()).isEqualTo(3);
    }

    @Test
    @DisplayName("背包满：药水掉脚下但**作物不消耗**（旧实现在这里会把整组作物写回 → 可无限刷药）")
    void fullInventoryDoesNotConsumeCrop() {
        Block core = buildStructure(Material.DISPENSER);
        putCenter(core, Material.WHEAT, 2);
        // 把背包彻底塞满，addItem 必然失败
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        }
        int made = plugin.dustGui().craft(player, core, core.getRelative(BlockFace.UP));

        assertThat(made).isZero();
        // 关键断言：作物数量没变（不会被白扣，也不能借此刷药）
        assertThat(DustCraftStructure.centerItem(core).getAmount()).isEqualTo(2);
        assertThat(countDust()).isZero();
        // 药水掉在脚下（世界里有掉落物），但玩家背包里没有
        assertThat(world.getEntitiesByClass(org.bukkit.entity.Item.class))
                .withFailMessage("背包满时应把药水丢在脚下")
                .isNotEmpty();
    }

    @Test
    @DisplayName("同一 tick 内的重复触发被去重：不会一次右键出两瓶、扣两个作物")
    void duplicateTriggerDebounced() {
        Block core = buildStructure(Material.DISPENSER);
        putCenter(core, Material.WHEAT, 5);
        Block anvil = core.getRelative(BlockFace.UP);
        ItemStack stick = new ItemStack(Material.STICK, 1);
        player.getInventory().setItemInMainHand(stick);

        // 模拟「主手 + 副手各派发一次」：连续两次同一目标的事件
        for (int i = 0; i < 2; i++) {
            var event = new org.bukkit.event.player.PlayerInteractEvent(player,
                    org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
                    new ItemStack(Material.STICK, 1),
                    anvil, BlockFace.UP,
                    org.bukkit.inventory.EquipmentSlot.HAND);
            server.getPluginManager().callEvent(event);
        }

        // 只产出 1 瓶、只扣 1 个作物
        assertThat(countDust()).isEqualTo(1);
        assertThat(DustCraftStructure.centerItem(core).getAmount()).isEqualTo(4);
    }

    @Test
    @DisplayName("潜行时木棍右键铁砧不接管：事件不被取消，也不产出（保住原版/其他插件交互）")
    void sneakingDoesNotCraft() {
        Block core = buildStructure(Material.DISPENSER);
        putCenter(core, Material.WHEAT, 3);
        player.setSneaking(true);
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK, 1));

        var event = new org.bukkit.event.player.PlayerInteractEvent(player,
                org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
                new ItemStack(Material.STICK, 1),
                core.getRelative(BlockFace.UP), BlockFace.UP,
                org.bukkit.inventory.EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);

        assertThat(event.isCancelled()).isFalse();
        assertThat(countDust()).isZero();
        assertThat(DustCraftStructure.centerItem(core).getAmount()).isEqualTo(3);
    }

    @Test
    @DisplayName("结构不完整时敲铁砧：给诊断但不取消事件、不扣东西")
    void incompleteStructureDoesNotCancel() {
        Block core = buildStructure(Material.DISPENSER);
        putCenter(core, Material.WHEAT, 3);
        core.getRelative(BlockFace.UP).setType(Material.AIR); // 拆铁砧
        // 铁砧没了就没有「铁砧」可点，这里退一步：保留铁砧但拆一朵原木
        core.getRelative(BlockFace.UP).setType(Material.ANVIL);
        core.getRelative(BlockFace.EAST).setType(Material.AIR);

        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK, 1));
        var event = new org.bukkit.event.player.PlayerInteractEvent(player,
                org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
                new ItemStack(Material.STICK, 1),
                core.getRelative(BlockFace.UP), BlockFace.UP,
                org.bukkit.inventory.EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);

        assertThat(event.isCancelled()).isFalse(); // 不霸占交互
        assertThat(countDust()).isZero();
        assertThat(DustCraftStructure.centerItem(core).getAmount()).isEqualTo(3);
    }

    @Test
    @DisplayName("漏斗既不能往核心灌、也不能从核心抽（否则加工台会被自动化绕过/搬空）")
    void hopperBlockedBothWays() {
        Block core = buildStructure(Material.DISPENSER);
        putCenter(core, Material.WHEAT, 4);
        Block hopper = world.getBlockAt(105, 64, 105);
        hopper.setType(Material.HOPPER);

        org.bukkit.inventory.Inventory coreInv = DustCraftStructure.inventoryOf(core);
        org.bukkit.inventory.Inventory hopperInv =
                ((org.bukkit.block.Hopper) hopper.getState()).getInventory();

        // 灌入方向
        var into = new org.bukkit.event.inventory.InventoryMoveItemEvent(
                hopperInv, new ItemStack(Material.DIAMOND, 1), coreInv, true);
        server.getPluginManager().callEvent(into);
        assertThat(into.isCancelled()).isTrue();

        // 抽出方向
        var outOf = new org.bukkit.event.inventory.InventoryMoveItemEvent(
                coreInv, new ItemStack(Material.WHEAT, 1), hopperInv, true);
        server.getPluginManager().callEvent(outOf);
        assertThat(outOf.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("四档作物各自产出对应档位（配置驱动，不硬编码）")
    void everyLevelCraftable() {
        for (PluginConfig.DustLevel level : plugin.config().antiDust().levels()) {
            String crop = level.items().get(0);
            Block core = buildStructure(Material.DISPENSER);
            putCenter(core, Material.valueOf(crop), 1);
            assertThat(plugin.dustGui().craft(player, core, core.getRelative(BlockFace.UP)))
                    .withFailMessage("档位 %s 的作物 %s 应可合成", level.id(), crop)
                    .isEqualTo(1);
        }
        assertThat(countDust()).isEqualTo(plugin.config().antiDust().levels().size());
    }

    // ---------------------------------------------------------------- 服用结算

    @Test
    @DisplayName("服用：腐蚀按档位下降、免疫窗口登记、解毒生效、冷却记录写入")
    void consumeAppliesEffects() {
        PluginConfig.DustLevel level = plugin.config().antiDust().levelById("refined"); // −50% / 30s
        ItemStack potion = plugin.dustItems().create(level, 1);
        player.getInventory().setItemInMainHand(potion);
        plugin.api().setCorrosion(player, 80.0);
        player.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 200, 0));

        // 走原版进食事件路径（AntiDustListener 监听 PlayerItemConsumeEvent）
        var event = new org.bukkit.event.player.PlayerItemConsumeEvent(player, potion);
        server.getPluginManager().callEvent(event);

        assertThat(event.isCancelled()).isFalse();
        assertThat(plugin.api().getCorrosion(player)).isCloseTo(30.0,
                org.assertj.core.data.Offset.offset(1e-9)); // 80 − 50
        assertThat(plugin.services().dustImmunity().isImmune(player)).isTrue();
        assertThat(plugin.services().dustImmunity().remainingSeconds(player.getUniqueId())).isEqualTo(30);
        assertThat(player.hasPotionEffect(PotionEffectType.POISON)).isFalse(); // 解毒
        assertThat(plugin.antiDustListener().cooldownRemaining(player)).isPositive();
    }

    @Test
    @DisplayName("腐蚀下跌钳到 0：腐蚀 10 + 精制(−50) → 0，不会变负")
    void corrosionClampedAtZero() {
        PluginConfig.DustLevel level = plugin.config().antiDust().levelById("refined");
        ItemStack potion = plugin.dustItems().create(level, 1);
        plugin.api().setCorrosion(player, 10.0);

        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerItemConsumeEvent(player, potion));
        assertThat(plugin.api().getCorrosion(player)).isZero();
    }

    @Test
    @DisplayName("冷却中服用：事件被取消（原版不扣物品），腐蚀与免疫不变")
    void consumeBlockedByCooldown() {
        PluginConfig.DustLevel level = plugin.config().antiDust().levelById("crude");
        ItemStack first = plugin.dustItems().create(level, 1);
        plugin.api().setCorrosion(player, 50.0);

        var firstEvent = new org.bukkit.event.player.PlayerItemConsumeEvent(player, first);
        server.getPluginManager().callEvent(firstEvent);
        assertThat(firstEvent.isCancelled()).isFalse();
        double afterFirst = plugin.api().getCorrosion(player);
        assertThat(afterFirst).isCloseTo(35.0, org.assertj.core.data.Offset.offset(1e-9)); // 50 − 15

        // 立刻再喝一瓶 → 冷却内，事件被取消
        ItemStack second = plugin.dustItems().create(level, 1);
        var secondEvent = new org.bukkit.event.player.PlayerItemConsumeEvent(player, second);
        server.getPluginManager().callEvent(secondEvent);
        assertThat(secondEvent.isCancelled()).isTrue();
        assertThat(plugin.api().getCorrosion(player)).isCloseTo(afterFirst,
                org.assertj.core.data.Offset.offset(1e-9)); // 未被二次扣减
    }

    @Test
    @DisplayName("普通蜂蜜瓶不受影响：事件不取消、不受冷却限制")
    void plainHoneyBottleUntouched() {
        ItemStack honey = new ItemStack(Material.HONEY_BOTTLE, 1);
        var event = new org.bukkit.event.player.PlayerItemConsumeEvent(player, honey);
        server.getPluginManager().callEvent(event);
        assertThat(event.isCancelled()).isFalse();
        assertThat(plugin.services().dustImmunity().isImmune(player)).isFalse();
    }

    @Test
    @DisplayName("失效药剂（档位已从配置删除）：取消消耗并提示，不白吃玩家东西")
    void orphanedPotionRejected() {
        // 先造一瓶 refined，再把配置改成没有 refined 档
        ItemStack potion = plugin.dustItems().create(
                plugin.config().antiDust().levelById("refined"), 1);
        Support.applyConfigVariant(plugin, Support.configYamlWith(
                "refined:", "refined_orphan:"));
        assertThat(plugin.config().antiDust().levelById("refined")).isNull();

        var event = new org.bukkit.event.player.PlayerItemConsumeEvent(player, potion);
        server.getPluginManager().callEvent(event);
        assertThat(event.isCancelled()).isTrue();
        assertThat(plugin.services().dustImmunity().isImmune(player)).isFalse();
    }

    // ------------------------------------------------------------ 免疫阻断伤害

    @Test
    @DisplayName("免疫窗口内：辐射结算走 IMMUNE 分支，零伤害")
    void immunityBlocksRoundDamage() {
        ItemStack potion = plugin.dustItems().create(
                plugin.config().antiDust().levelById("refined"), 1);
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerItemConsumeEvent(player, potion));
        assertThat(plugin.services().dustImmunity().isImmune(player)).isTrue();

        var outcome = plugin.services().radiationTask().settleRound(player);
        assertThat(outcome.result())
                .isEqualTo(com.ft.radsurvival.core.RadiationTask.RoundResult.IMMUNE);
        assertThat(outcome.damage()).isZero();
    }

    @Test
    @DisplayName("免疫窗口内：腐蚀**不累积**（旧实现只挡伤害，腐蚀照涨，与 HUD 的 ◆抗尘 矛盾）")
    void immunityFreezesCorrosionGrowth() {
        // 必须用一个「确定露天」的装配，否则 MockBukkit 默认判遮蔽、
        // 新旧实现在遮蔽下都会走恢复分支，测不出差异（这正是本条容易写假的地方）。
        Support.Fixture fixture = Support.install(plugin, server);
        fixture.exposed.set(true);
        UUID id = player.getUniqueId();
        // 必须先 registerPlayer（它会从 PDC loadFromPlayer 覆盖内存值），再种基线，
        // 否则 setValue 之后又会被注册流程刷回 0。
        fixture.task().registerPlayer(player);
        fixture.meter().setValue(id, 40.0);
        double before = fixture.meter().get(id);

        // 先确认前提：这个装配下**确实在累积**（否则下面的断言没有意义）
        fixture.task().settleRound(player);
        double afterOneExposedRound = fixture.meter().get(id);
        assertThat(afterOneExposedRound)
                .withFailMessage("前提不成立：露天装配下腐蚀应上涨，实际 before=%s after=%s",
                        before, afterOneExposedRound)
                .isGreaterThan(before);

        // 进入免疫窗口后，腐蚀不得继续上涨
        plugin.services().dustImmunity().grant(id, 30);
        double beforeImmune = fixture.meter().get(id);
        for (int i = 0; i < 10; i++) {
            fixture.task().settleRound(player);
        }
        double after = fixture.meter().get(id);
        assertThat(after)
                .withFailMessage("免疫期间腐蚀不应上涨：before=%s after=%s", beforeImmune, after)
                .isLessThanOrEqualTo(beforeImmune + 1e-9);
    }

    @Test
    @DisplayName("死亡结束免疫：不能靠「喝药 → 自杀 → 复活」带着免伤窗口重生")
    void deathClearsImmunity() {
        UUID id = player.getUniqueId();
        plugin.services().dustImmunity().grant(id, 30);
        assertThat(plugin.services().dustImmunity().isImmune(id)).isTrue();

        server.getPluginManager().callEvent(new org.bukkit.event.entity.PlayerDeathEvent(
                player, org.bukkit.damage.DamageSource.builder(
                        org.bukkit.damage.DamageType.GENERIC).build(),
                new java.util.ArrayList<>(), 0, net.kyori.adventure.text.Component.empty(),
                false));

        assertThat(plugin.services().dustImmunity().isImmune(id)).isFalse();
    }

    @Test
    @DisplayName("退出登录清免疫与冷却：防止「下线喝药、上线免伤」")
    void quitClearsImmunityAndCooldown() {
        ItemStack potion = plugin.dustItems().create(
                plugin.config().antiDust().levelById("refined"), 1);
        UUID id = player.getUniqueId();
        plugin.services().dustImmunity().grant(id, 30);

        player.disconnect();
        assertThat(plugin.services().dustImmunity().isImmune(id)).isFalse();
        assertThat(plugin.antiDustListener().cooldownRemaining(player)).isZero();
    }

    // -------------------------------------------------------------- 命令 / GUI 辅助

    @Test
    @DisplayName("/rad dust list 输出四档与作物；/rad dust give 发放；/rad dust help 讲结构")
    void dustCommand() {
        player.setOp(true);
        player.performCommand("rad dust list");
        String list = joinDrain(player);
        assertThat(list).contains("精制抗尘埃药").contains("粗制抗尘埃药").contains("GOLDEN_CARROT");

        player.performCommand("rad dust give refined");
        assertThat(countDust()).isEqualTo(1);
        assertThat(DustItemFactory.levelIdOf(firstDust())).isEqualTo("refined");

        player.performCommand("rad dust help");
        assertThat(joinDrain(player)).contains("铁砧").contains("木棍");
    }

    @Test
    @DisplayName("非管理员执行 /rad dust 被拒（权限 radsurvival.admin）")
    void dustCommandRequiresPermission() {
        player.setOp(false);
        player.performCommand("rad dust list");
        assertThat(joinDrain(player)).contains("缺少权限");
        assertThat(countDust()).isZero();
    }

    // ------------------------------------------------------------------ 小工具

    private ItemStack firstDust() {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (DustItemFactory.isDust(stack)) {
                return stack;
            }
        }
        throw new AssertionError("背包里没有抗尘埃药");
    }

    private static String joinDrain(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = player.nextMessage()) != null) {
            lines.add(line);
        }
        return String.join("\n", lines);
    }
}
