package com.ft.radsurvival.mockbukkit;

import java.util.List;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.config.PluginConfig;
import com.ft.radsurvival.item.DustItemFactory;

import io.papermc.paper.datacomponent.DataComponentTypes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抗尘埃药物品构建测试（v1.2 新增）。
 *
 * <p>覆盖：基底材质/堆叠/PDC 档位标签、名称着色、Lore 内容、CONSUMABLE 组件写入、
 * 档位反查（含「档位被服主删除」的失效场景）、以及 {@code /rad dust give} 用的工厂路径。</p>
 */
class DustItemFactoryMockBukkitTest {

    private ServerMock server;
    private RadSurvivalPlugin plugin;
    private DustItemFactory factory;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RadSurvivalPlugin.class);
        factory = new DustItemFactory(plugin.config());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PluginConfig.DustLevel refined() {
        return plugin.config().antiDust().levelById("refined");
    }

    @Test
    @DisplayName("构建：蜂蜜瓶基底、数量正确、最大堆叠 8、附魔微光开")
    void buildsBaseShape() {
        ItemStack potion = factory.create(refined(), 3);
        assertThat(potion.getType()).isEqualTo(Material.HONEY_BOTTLE);
        assertThat(potion.getAmount()).isEqualTo(3);
        assertThat(potion.getData(DataComponentTypes.MAX_STACK_SIZE)).isEqualTo(8);
        assertThat(potion.getData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE)).isTrue();
        // 数量下限钳制：0 / 负数都视为 1
        assertThat(factory.create(refined(), 0).getAmount()).isEqualTo(1);
        assertThat(factory.create(refined(), -5).getAmount()).isEqualTo(1);
    }

    @Test
    @DisplayName("写入 CONSUMABLE：喝的动作交给原版（动画 DRINK / 1.6s / 不重复粒子）")
    void consumableComponentWritten() {
        ItemStack potion = factory.create(refined(), 1);
        var consumable = potion.getData(DataComponentTypes.CONSUMABLE);
        assertThat(consumable).isNotNull();
        assertThat(consumable.consumeSeconds()).isCloseTo(1.6f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(consumable.animation())
                .isEqualTo(io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation.DRINK);
        assertThat(consumable.hasConsumeParticles()).isFalse();
        // 效果列表必须为空：效果由 AntiDustListener 结算，写进组件会绕过冷却判断
        assertThat(consumable.consumeEffects()).isEmpty();
    }

    @Test
    @DisplayName("名称与 Lore：显示名按配置、Lore 写明降腐蚀与免疫时长、逐行非斜体")
    void nameAndLore() {
        ItemStack potion = factory.create(refined(), 1);
        String name = PlainTextComponentSerializer.plainText().serialize(potion.displayName());
        // MockBukkit 的 displayName 序列化会带方括号包裹，这里只断言内容
        assertThat(name).contains("精制抗尘埃药");

        List<net.kyori.adventure.text.Component> lore = potion.lore();
        assertThat(lore).isNotNull().hasSize(4);
        String joined = lore.stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c))
                .reduce("", (a, b) -> a + "|" + b);
        assertThat(joined).contains("−50%").contains("30 秒").contains("辐射环境");
        // 每行都关闭斜体（否则原版 Lore 默认斜体，观感廉价）
        for (var line : lore) {
            assertThat(line.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC))
                    .isEqualTo(net.kyori.adventure.text.format.TextDecoration.State.FALSE);
        }
    }

    @Test
    @DisplayName("PDC 识别：是抗尘埃药、档位 id 正确；普通蜂蜜瓶/其它物品一律否")
    void pdcIdentification() {
        ItemStack potion = factory.create(refined(), 1);
        assertThat(DustItemFactory.isDust(potion)).isTrue();
        assertThat(DustItemFactory.levelIdOf(potion)).isEqualTo("refined");
        assertThat(factory.levelOf(potion).id()).isEqualTo("refined");

        assertThat(DustItemFactory.isDust(new ItemStack(Material.HONEY_BOTTLE))).isFalse();
        assertThat(DustItemFactory.levelIdOf(new ItemStack(Material.HONEY_BOTTLE))).isNull();
        assertThat(DustItemFactory.isDust(new ItemStack(Material.DIAMOND))).isFalse();
        assertThat(DustItemFactory.isDust(null)).isFalse();
        assertThat(factory.levelOf(null)).isNull();
    }

    @Test
    @DisplayName("四档全部可构建且档位互不串味（PDC 值是唯一权威来源）")
    void allLevelsDistinct() {
        for (PluginConfig.DustLevel level : plugin.config().antiDust().levels()) {
            ItemStack potion = factory.create(level, 1);
            assertThat(DustItemFactory.levelIdOf(potion)).isEqualTo(level.id());
            assertThat(factory.levelOf(potion).corrosionReduction())
                    .isEqualTo(level.corrosionReduction());
            assertThat(factory.levelOf(potion).immuneSeconds()).isEqualTo(level.immuneSeconds());
        }
    }

    @Test
    @DisplayName("失效药剂：档位被服主从配置删除后，物品仍可识别但 levelOf 返回 null（不静默误判）")
    void orphanedLevelResolvesToNull() {
        ItemStack potion = factory.create(refined(), 1);
        // 用一份删掉 refined 档的配置重建工厂，模拟服主改配置后仓库里的旧药水
        String yaml = Support.repoConfigYaml()
                .replace("refined:", "refined_removed:");
        PluginConfig.LoadResult result = PluginConfig.loadFromString(yaml);
        assertThat(result.errors()).isEmpty();
        DustItemFactory narrowed = new DustItemFactory(result.config());

        assertThat(DustItemFactory.isDust(potion)).isTrue();     // PDC 还在
        assertThat(narrowed.levelOf(potion)).isNull();           // 但档位查不到
    }

    @Test
    @DisplayName("levelById 大小写不敏感；未知 id 返回 null；levels() 按配置顺序")
    void levelLookup() {
        assertThat(factory.levelById("REFINED").id()).isEqualTo("refined");
        assertThat(factory.levelById("nope")).isNull();
        assertThat(factory.levelById(null)).isNull();
        assertThat(factory.levels().stream().map(PluginConfig.DustLevel::id))
                .containsExactly("refined", "strong", "improved", "crude");
    }

    @Test
    @DisplayName("命名空间与基底常量稳定（外部插件/资源包按此对齐）")
    void stableConstants() {
        assertThat(DustItemFactory.PDC_NAMESPACE).isEqualTo("radsurvival");
        assertThat(DustItemFactory.PDC_LEVEL_KEY).isEqualTo("dust_level");
        assertThat(DustItemFactory.LEVEL_KEY.getKey()).isEqualTo("dust_level");
        assertThat(DustItemFactory.LEVEL_KEY.getNamespace()).isEqualTo("radsurvival");
        assertThat(DustItemFactory.BASE_MATERIAL).isEqualTo(Material.HONEY_BOTTLE);
        assertThat(DustItemFactory.baseMaterialName()).isEqualTo("HONEY_BOTTLE");
    }
}
