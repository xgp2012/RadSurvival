package com.ft.radsurvival.mockbukkit;

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
 * 抗尘埃药合成台结构规则测试（v1.2 新增；结构改为「铁砧 + 发射器 + 铁块」竖列三格）。
 *
 * <p><b>为什么必须是 MockBukkit 而不是纯 JUnit</b>：结构判定用 {@code org.bukkit.Tag.ANVIL}，
 * 而 {@code Tag} 的静态初始化需要 Bukkit 注册表（纯 JUnit 下抛
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
    @DisplayName("底座：只认铁块（铁砧、原木、石头、铁粒块都不算）")
    void baseOnlyAcceptsIronBlock() {
        assertThat(DustCraftStructure.isBase(Material.IRON_BLOCK)).isTrue();
        assertThat(DustCraftStructure.isBase(Material.IRON_ORE)).isFalse();
        assertThat(DustCraftStructure.isBase(Material.ANVIL)).isFalse();
        assertThat(DustCraftStructure.isBase(Material.OAK_LOG)).isFalse();
        assertThat(DustCraftStructure.isBase(Material.STONE)).isFalse();
        assertThat(DustCraftStructure.isBase(null)).isFalse();
    }

    @Test
    @DisplayName("原木 tag 仍可用，但已不参与结构判定（保留仅供既有引用）")
    void woodTagStillAvailableButUnused() {
        assertThat(DustCraftStructure.isWood(Material.OAK_LOG)).isTrue();
        assertThat(DustCraftStructure.isWood(Material.CRIMSON_STEM)).isTrue(); // 下界木干
        assertThat(DustCraftStructure.isWood(Material.OAK_PLANKS)).isFalse();
        assertThat(DustCraftStructure.isWood(null)).isFalse();
    }

    @Test
    @DisplayName("完整结构：铁砧 + 发射器 + 铁块 → 无问题（含投掷器/损坏铁砧）")
    void completeStructureIsValid() {
        assertThat(DustCraftStructure.validate(Material.DISPENSER, Material.ANVIL, Material.IRON_BLOCK))
                .isEmpty();
        assertThat(DustCraftStructure.validate(Material.DROPPER, Material.DAMAGED_ANVIL, Material.IRON_BLOCK))
                .isEmpty();
        assertThat(DustCraftStructure.validate(Material.DISPENSER, Material.CHIPPED_ANVIL, Material.IRON_BLOCK))
                .isEmpty();
    }

    @Test
    @DisplayName("缺铁砧：诊断信息点名「正上方必须是铁砧」并写出实际方块")
    void missingAnvilReported() {
        List<String> problems = DustCraftStructure.validate(Material.DISPENSER, Material.AIR,
                Material.IRON_BLOCK);
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("正上方必须是铁砧").contains("AIR");
    }

    @Test
    @DisplayName("缺铁块底座：诊断信息点名「正下方必须是铁块」并写出实际方块")
    void missingBaseReported() {
        List<String> problems = DustCraftStructure.validate(Material.DISPENSER, Material.ANVIL,
                Material.STONE);
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("正下方必须是铁块").contains("STONE");
    }

    @Test
    @DisplayName("核心方块不对 + 缺铁砧 + 缺铁块：三类问题一次性全部报出（玩家可一次修好）")
    void allProblemsReportedTogether() {
        List<String> problems = DustCraftStructure.validate(Material.STONE, Material.STONE, Material.STONE);
        assertThat(problems).hasSize(3);
        assertThat(String.join(" | ", problems))
                .contains("核心方块必须是发射器/投掷器")
                .contains("正上方必须是铁砧")
                .contains("正下方必须是铁块");
    }

    @Test
    @DisplayName("水平方向不再参与判定：四个水平邻居放什么都不影响结果")
    void horizontalNeighboursAreIrrelevant() {
        // validate 已不接收环参数，因此「周围放什么都成立」在签名层面即被保证；
        // 这里再锁一次：同一组上下取值无论怎么换，结论都只由 core/above/below 决定。
        assertThat(DustCraftStructure.validate(Material.DISPENSER, Material.ANVIL, Material.IRON_BLOCK))
                .isEmpty();
        assertThat(DustCraftStructure.validate(Material.DISPENSER, Material.ANVIL, Material.AIR))
                .hasSize(1);
    }

    @Test
    @DisplayName("GUI 槽位映射：中心 = 索引 4，其余 8 格 = 0..8 去掉 4")
    void slotMapping() {
        assertThat(DustCraftStructure.CENTER_SLOT).isEqualTo(4);
        assertThat(DustCraftStructure.ringSlots()).containsExactly(0, 1, 2, 3, 5, 6, 7, 8);
        assertThat(DustCraftStructure.ringSlots()).hasSize(8).doesNotContain(4);
    }

    @Test
    @DisplayName("结果对象：summary 通过时说完整，失败时拼接问题")
    void resultSummary() {
        DustCraftStructure.Result ok = new DustCraftStructure.Result(true, null, null, List.of());
        assertThat(ok.ok()).isTrue();
        assertThat(ok.summary()).contains("结构完整");

        DustCraftStructure.Result bad = new DustCraftStructure.Result(false, null, null,
                List.of("缺铁砧", "缺铁块"));
        assertThat(bad.ok()).isFalse();
        assertThat(bad.summary()).isEqualTo("缺铁砧；缺铁块");

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
