package com.ft.radsurvival.mockbukkit;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import com.ft.radsurvival.item.DustCraftStructure;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抗尘埃药合成台结构规则测试（v1.2 新增）。
 *
 * <p><b>为什么必须是 MockBukkit 而不是纯 JUnit</b>：结构判定用 {@code org.bukkit.Tag.ANVIL}
 * 与 {@code Tag.LOGS}，而 {@code Tag} 的静态初始化需要 Bukkit 注册表（纯 JUnit 下抛
 * {@code ExceptionInInitializerError}）。本测试因此套 MockBukkit 夹具，
 * 只测纯 {@link Material} 判定层、不触真实方块读取。</p>
 */
class DustCraftStructureMockBukkitTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** 一个合法的 8 邻环（全橡木原木）。 */
    private static List<Material> oakRing() {
        List<Material> ring = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) {
            ring.add(Material.OAK_LOG);
        }
        return ring;
    }

    @Test
    @DisplayName("核心方块：发射器与投掷器都接受（中文名易混，两者语义等价）")
    void coreAcceptsDispenserAndDropper() {
        assertThat(DustCraftStructure.isCoreContainer(Material.DISPENSER)).isTrue();
        assertThat(DustCraftStructure.isCoreContainer(Material.DROPPER)).isTrue();
        assertThat(DustCraftStructure.isCoreContainer(Material.HOPPER)).isFalse();
        assertThat(DustCraftStructure.isCoreContainer(Material.CHEST)).isFalse();
        assertThat(DustCraftStructure.isCoreContainer(Material.ANVIL)).isFalse();
        assertThat(DustCraftStructure.isCoreContainer(null)).isFalse();
    }

    @Test
    @DisplayName("铁砧 tag：普通/损坏/上蜡三种都算；铁块/炼药锅不算")
    void anvilTagCoversVariants() {
        assertThat(DustCraftStructure.isAnvil(Material.ANVIL)).isTrue();
        assertThat(DustCraftStructure.isAnvil(Material.CHIPPED_ANVIL)).isTrue();
        assertThat(DustCraftStructure.isAnvil(Material.DAMAGED_ANVIL)).isTrue();
        assertThat(DustCraftStructure.isAnvil(Material.IRON_BLOCK)).isFalse();
        assertThat(DustCraftStructure.isAnvil(Material.CAULDRON)).isFalse();
        assertThat(DustCraftStructure.isAnvil(null)).isFalse();
    }

    @Test
    @DisplayName("原木 tag：各树种原木/木干都算；木板/台阶/树叶不算")
    void woodTagCoversLogsNotPlanks() {
        assertThat(DustCraftStructure.isWood(Material.OAK_LOG)).isTrue();
        assertThat(DustCraftStructure.isWood(Material.SPRUCE_LOG)).isTrue();
        assertThat(DustCraftStructure.isWood(Material.CHERRY_LOG)).isTrue();
        assertThat(DustCraftStructure.isWood(Material.CRIMSON_STEM)).isTrue(); // 下界木干
        assertThat(DustCraftStructure.isWood(Material.STRIPPED_OAK_LOG)).isTrue();

        assertThat(DustCraftStructure.isWood(Material.OAK_PLANKS)).isFalse();
        assertThat(DustCraftStructure.isWood(Material.OAK_SLAB)).isFalse();
        assertThat(DustCraftStructure.isWood(Material.OAK_LEAVES)).isFalse();
        assertThat(DustCraftStructure.isWood(Material.STONE)).isFalse();
        assertThat(DustCraftStructure.isWood(null)).isFalse();
    }

    @Test
    @DisplayName("完整结构：发射器 + 上方铁砧 + 8 原木 → 无问题（含混树种/投掷器/损坏铁砧）")
    void completeStructureIsValid() {
        assertThat(DustCraftStructure.validate(Material.DISPENSER, Material.ANVIL, oakRing())).isEmpty();
        List<Material> mixed = List.of(Material.OAK_LOG, Material.BIRCH_LOG, Material.SPRUCE_LOG,
                Material.JUNGLE_LOG, Material.ACACIA_LOG, Material.DARK_OAK_LOG,
                Material.MANGROVE_LOG, Material.CHERRY_LOG);
        assertThat(DustCraftStructure.validate(Material.DROPPER, Material.DAMAGED_ANVIL, mixed)).isEmpty();
    }

    @Test
    @DisplayName("缺铁砧：诊断信息点名「正上方必须是铁砧」并写出实际方块")
    void missingAnvilReported() {
        List<String> problems = DustCraftStructure.validate(Material.DISPENSER, Material.AIR, oakRing());
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("正上方必须是铁砧").contains("AIR");
    }

    @Test
    @DisplayName("缺原木：诊断信息列出**全部**缺失方向（不是只报第一个）")
    void missingLogsReportEveryDirection() {
        List<Material> ring = oakRing();
        ring.set(0, Material.AIR);          // NORTH
        ring.set(4, Material.OAK_PLANKS);   // SOUTH
        List<String> problems = DustCraftStructure.validate(Material.DISPENSER, Material.ANVIL, ring);
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("外围一圈必须全是原木")
                .contains("NORTH=AIR")
                .contains("SOUTH=OAK_PLANKS");
    }

    @Test
    @DisplayName("核心方块不对 + 缺铁砧 + 环不完整：三类问题一次性全部报出（玩家可一次修好）")
    void allProblemsReportedTogether() {
        List<String> problems = DustCraftStructure.validate(Material.STONE, Material.STONE, new ArrayList<>());
        assertThat(problems).hasSize(3);
        assertThat(String.join(" | ", problems))
                .contains("核心方块必须是发射器/投掷器")
                .contains("正上方必须是铁砧")
                .contains("原木环必须是 8 个邻居");
    }

    @Test
    @DisplayName("GUI 槽位映射：中心 = 索引 4，外围 8 格 = 0..8 去掉 4")
    void slotMapping() {
        assertThat(DustCraftStructure.CENTER_SLOT).isEqualTo(4);
        assertThat(DustCraftStructure.ringSlots()).containsExactly(0, 1, 2, 3, 5, 6, 7, 8);
        assertThat(DustCraftStructure.ringSlots()).hasSize(8).doesNotContain(4);
    }

    @Test
    @DisplayName("8 邻方向表：8 个水平方向，无 UP/DOWN，无重复")
    void ringFacesAreHorizontal() {
        assertThat(DustCraftStructure.RING_FACES).hasSize(8);
        assertThat(DustCraftStructure.RING_FACES).doesNotContain(
                org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN,
                org.bukkit.block.BlockFace.SELF);
        assertThat(DustCraftStructure.RING_FACES).doesNotHaveDuplicates();
        for (org.bukkit.block.BlockFace face : DustCraftStructure.RING_FACES) {
            assertThat(face.getModY()).isZero(); // 全部同层（水平环）
        }
    }

    @Test
    @DisplayName("结果对象：summary 通过时说完整，失败时拼接问题")
    void resultSummary() {
        DustCraftStructure.Result ok = new DustCraftStructure.Result(true, null, null, List.of());
        assertThat(ok.ok()).isTrue();
        assertThat(ok.summary()).contains("结构完整");

        DustCraftStructure.Result bad = new DustCraftStructure.Result(false, null, null,
                List.of("缺铁砧", "缺原木"));
        assertThat(bad.ok()).isFalse();
        assertThat(bad.summary()).isEqualTo("缺铁砧；缺原木");

        DustCraftStructure.Result empty = new DustCraftStructure.Result(false, null, null, List.of());
        assertThat(empty.summary()).isEqualTo("结构不完整");
    }

    @Test
    @DisplayName("核心方块中文名：投掷器显示投掷器，其余显示发射器")
    void coreDisplayName() {
        assertThat(DustCraftStructure.coreDisplayName(Material.DROPPER)).isEqualTo("投掷器");
        assertThat(DustCraftStructure.coreDisplayName(Material.DISPENSER)).isEqualTo("发射器");
    }
}
