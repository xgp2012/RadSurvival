package com.ft.radsurvival.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ft.radsurvival.config.PluginConfig;
import com.ft.radsurvival.core.ArmorCalculator.ArmorItem;
import com.ft.radsurvival.core.ArmorCalculator.ArmorSlot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * ArmorCalculator 纯逻辑测试（plan.md §1.4，M2a）：对照 §1.4 权重×系数表手算钉死，
 * 归类前缀/后缀矩阵、非盔甲计 0、结果钳 [0,1]。无 Bukkit 服务器依赖
 * （仅使用 Material 常量枚举的 name()，与 M1 测试用 GameMode/Environment 同性质）。
 */
class ArmorCalculatorTest {

    private static final double EPS = 1e-9;

    /** §1.4 默认权重/系数表：直接取仓库真实 config.yml（经 M1 校验），避免测试与配置漂移。 */
    private static final PluginConfig CFG = loadRepoConfig();
    private static final Map<String, Double> W = CFG.armor().slotWeights();
    private static final Map<String, Double> F = CFG.armor().materialFactors();

    private static PluginConfig loadRepoConfig() {
        try {
            String yaml = Files.readString(Path.of("src", "main", "resources", "config.yml"));
            PluginConfig.LoadResult result = PluginConfig.loadFromString(yaml);
            assertThat(result.errors()).isEmpty();
            return result.config();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 便捷构造：部位 → 材质键映射；null 值 = 该槽为空。 */
    private static Map<ArmorSlot, String> slots(String helmet, String chestplate,
                                                String leggings, String boots) {
        Map<ArmorSlot, String> map = new EnumMap<>(ArmorSlot.class);
        map.put(ArmorSlot.HELMET, helmet);
        map.put(ArmorSlot.CHESTPLATE, chestplate);
        map.put(ArmorSlot.LEGGINGS, leggings);
        map.put(ArmorSlot.BOOTS, boots);
        return map;
    }

    // ------------------------------------------------------------ §1.4 预算对照

    @Test
    @DisplayName("全套下界合金减免恰为 1.0（=完全免疫，§1.4/§1.3；浮点溢出部分由 [0,1] 钳位吸收）")
    void fullNetheriteIsExactlyOne() {
        double r = ArmorCalculator.reduction(
                slots("NETHERITE", "NETHERITE", "NETHERITE", "NETHERITE"), W, F);
        assertThat(r).isEqualTo(1.0); // 0.2+0.4+0.3+0.1 = 1.0000000000000002 → 钳位后恰 1.0
    }

    @Test
    @DisplayName("仅下界合金胸甲 = 0.40（§1.4 明示值）")
    void netheriteChestplateOnly() {
        assertThat(ArmorCalculator.reduction(slots(null, "NETHERITE", null, null), W, F))
                .isCloseTo(0.40, within(EPS));
    }

    @Test
    @DisplayName("全套铁 = 0.70、全套皮革 = 0.25（§1.4 表）")
    void fullIronAndFullLeather() {
        assertThat(ArmorCalculator.reduction(
                slots("IRON", "IRON", "IRON", "IRON"), W, F)).isCloseTo(0.70, within(EPS));
        assertThat(ArmorCalculator.reduction(
                slots("LEATHER", "LEATHER", "LEATHER", "LEATHER"), W, F)).isCloseTo(0.25, within(EPS));
    }

    @Test
    @DisplayName("全套金 = 全套锁链 = 0.45（§1.4 同系数 0.45）")
    void goldAndChainmailEqualFactor() {
        assertThat(ArmorCalculator.reduction(slots("GOLD", "GOLD", "GOLD", "GOLD"), W, F))
                .isCloseTo(0.45, within(EPS));
        assertThat(ArmorCalculator.reduction(
                slots("CHAINMAIL", "CHAINMAIL", "CHAINMAIL", "CHAINMAIL"), W, F))
                .isCloseTo(0.45, within(EPS));
    }

    @Test
    @DisplayName("全套钻石 = 0.85；混搭铁盔+钻胸手算 = 0.20×0.70+0.40×0.85 = 0.48")
    void fullDiamondAndMixedCombo() {
        assertThat(ArmorCalculator.reduction(
                slots("DIAMOND", "DIAMOND", "DIAMOND", "DIAMOND"), W, F)).isCloseTo(0.85, within(EPS));
        // 铁头盔(0.20×0.70=0.14) + 钻石胸甲(0.40×0.85=0.34) = 0.48
        assertThat(ArmorCalculator.reduction(slots("IRON", "DIAMOND", null, null), W, F))
                .isCloseTo(0.48, within(EPS));
        // 四层混搭：金盔0.20×0.45=0.09 + 铁胸0.40×0.70=0.28 + 链甲腿0.30×0.45=0.135 + 皮靴0.10×0.25=0.025 = 0.53
        assertThat(ArmorCalculator.reduction(slots("GOLD", "IRON", "CHAINMAIL", "LEATHER"), W, F))
                .isCloseTo(0.09 + 0.28 + 0.135 + 0.025, within(EPS));
    }

    @Test
    @DisplayName("空槽位/未知材质键计 0（权重非法/越界由 M1 校验兜底，此处防御性截断）")
    void emptySlotsAndUnknownMaterialCountZero() {
        assertThat(ArmorCalculator.reduction(Map.of(), W, F)).isEqualTo(0.0);
        assertThat(ArmorCalculator.reduction(slots(null, null, null, null), W, F)).isEqualTo(0.0);
        assertThat(ArmorCalculator.reduction(slots("WOOD", null, null, null), W, F)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("结果钳 [0,1]：权重系数全 1 的极端表 → 原始和 4.0 截断为 1.0")
    void clampedToUnitInterval() {
        Map<String, Double> allWeights = Map.of("helmet", 1.0, "chestplate", 1.0, "leggings", 1.0, "boots", 1.0);
        Map<String, Double> allFactors = Map.of("NETHERITE", 1.0);
        assertThat(ArmorCalculator.reduction(
                slots("NETHERITE", "NETHERITE", "NETHERITE", "NETHERITE"), allWeights, allFactors))
                .isEqualTo(1.0);
    }

    // ---------------------------------------------------------------- 归类矩阵

    @Test
    @DisplayName("Material 名归类：GOLDEN_→GOLD、CHAINMAIL_→锁链、NETHERITE_→下界合金 等六材质×四部位")
    void classifyMaterialNamePrefixSuffix() {
        assertThat(ArmorCalculator.classify("LEATHER_HELMET")).isEqualTo(new ArmorItem(ArmorSlot.HELMET, "LEATHER"));
        assertThat(ArmorCalculator.classify("GOLDEN_CHESTPLATE")).isEqualTo(new ArmorItem(ArmorSlot.CHESTPLATE, "GOLD"));
        assertThat(ArmorCalculator.classify("CHAINMAIL_LEGGINGS")).isEqualTo(new ArmorItem(ArmorSlot.LEGGINGS, "CHAINMAIL"));
        assertThat(ArmorCalculator.classify("IRON_BOOTS")).isEqualTo(new ArmorItem(ArmorSlot.BOOTS, "IRON"));
        assertThat(ArmorCalculator.classify("DIAMOND_HELMET")).isEqualTo(new ArmorItem(ArmorSlot.HELMET, "DIAMOND"));
        assertThat(ArmorCalculator.classify("NETHERITE_CHESTPLATE")).isEqualTo(new ArmorItem(ArmorSlot.CHESTPLATE, "NETHERITE"));
    }

    @Test
    @DisplayName("非盔甲物品不计：IRON_SWORD/GOLDEN_APPLE/DIAMOND_HORSE_ARMOR/ELYTRA/CHAIN/STONE 一律 null")
    void nonArmorItemsAreIgnored() {
        assertThat(ArmorCalculator.classify("IRON_SWORD")).isNull();
        assertThat(ArmorCalculator.classify("GOLDEN_APPLE")).isNull();
        assertThat(ArmorCalculator.classify("DIAMOND_HORSE_ARMOR")).isNull();
        assertThat(ArmorCalculator.classify("ELYTRA")).isNull();
        assertThat(ArmorCalculator.classify("CHAIN")).isNull(); // 锁链方块：前缀不匹配 CHAINMAIL_
        assertThat(ArmorCalculator.classify("STONE")).isNull();
        assertThat(ArmorCalculator.classify("AIR")).isNull();
        assertThat(ArmorCalculator.classify((String) null)).isNull();
        assertThat(ArmorCalculator.classify((Material) null)).isNull();
    }

    @Test
    @DisplayName("Material 枚举入口与完整盔甲 16 件逐一归类正确（与原版枚举名对应关系核实）")
    void classifyMaterialEnumCoversAllVanillaArmor() {
        Material[] sixteen = {
                Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS,
                Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS,
                Material.CHAINMAIL_HELMET, Material.CHAINMAIL_CHESTPLATE, Material.CHAINMAIL_LEGGINGS, Material.CHAINMAIL_BOOTS,
                Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS,
                Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS,
                Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS,
        };
        assertThat(sixteen).hasSize(24);
        for (Material material : sixteen) {
            ArmorItem item = ArmorCalculator.classify(material);
            assertThat(item).withFailMessage(() -> material.name() + " 应归类为盔甲").isNotNull();
            assertThat(item.slot().name()).isEqualTo(material.name().substring(material.name().lastIndexOf('_') + 1));
        }
        // 枚举入口 == 字符串入口同结果
        assertThat(ArmorCalculator.classify(Material.IRON_HELMET))
                .isEqualTo(ArmorCalculator.classify(Material.IRON_HELMET.name()));
        // 手持物经枚举入口同样忽略
        assertThat(ArmorCalculator.classify(Material.IRON_SWORD)).isNull();
    }

    @Test
    @DisplayName("armorContents 空数组（四槽全 null）→ 减免 0（适配层空槽路径）")
    void emptyArmorContentsYieldZero() {
        ArmorCalculator calc = new ArmorCalculator(CFG);
        assertThat(calc.reductionOf(new org.bukkit.inventory.ItemStack[]{null, null, null, null}))
                .isEqualTo(0.0);
        // 实例 reduction(Map) 与静态内核同口径
        assertThat(calc.reduction(slots("IRON", "IRON", "IRON", "IRON"))).isCloseTo(0.70, within(EPS));
    }
}
