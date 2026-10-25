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
 * ArmorCalculator 纯逻辑测试（plan.md §1.4；v1.2 护甲全面削弱后重算）。
 *
 * <p><b>v1.2 变更要点</b>：旧口径下界合金满配 = 1.0 = 完全免疫，本测试曾用
 * {@code fullNetheriteIsExactlyOne} 钉死该值。护甲削弱后满配上限降到 72%，
 * 「免疫」不再可能通过穿甲达成，因此本测试的核心断言改成
 * <b>「任何材质满配都 &lt; 1.0」</b> + 逐档具体数值 + 最低杀伤保底。</p>
 *
 * <p>数值直接取仓库真实 {@code config.yml}（经 M1 校验），避免测试与配置漂移。</p>
 */
class ArmorCalculatorTest {

    private static final double EPS = 1e-9;

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

    private static double fullSet(String material) {
        return ArmorCalculator.reduction(slots(material, material, material, material), W, F);
    }

    // ------------------------------------------------------------ v1.2 削弱对照

    @Test
    @DisplayName("v1.2 六档满配减免：皮革 15% / 金·锁链 30% / 铁 48% / 钻石 60% / 下界合金 72%")
    void fullSetReductionMatchesNerfTable() {
        assertThat(fullSet("LEATHER")).isCloseTo(0.15, within(EPS));
        assertThat(fullSet("GOLD")).isCloseTo(0.30, within(EPS));
        assertThat(fullSet("CHAINMAIL")).isCloseTo(0.30, within(EPS));
        assertThat(fullSet("IRON")).isCloseTo(0.48, within(EPS));
        assertThat(fullSet("DIAMOND")).isCloseTo(0.60, within(EPS));
        assertThat(fullSet("NETHERITE")).isCloseTo(0.72, within(EPS));
    }

    @Test
    @DisplayName("v1.2 硬约束：没有任何材质能靠满配达成完全免疫（全部 < 1.0，留出 ≥ 28% 伤害）")
    void noMaterialCanReachFullImmunity() {
        for (String material : F.keySet()) {
            double reduction = fullSet(material);
            assertThat(reduction)
                    .withFailMessage("%s 满配减免 %.4f 必须 < 1.0（v1.2 已取消下界合金免疫）",
                            material, reduction)
                    .isLessThan(1.0);
        }
        // 最强档也至少放行 28% 伤害——这是「削弱」而非「微调」的量化体现
        assertThat(fullSet("NETHERITE")).isLessThanOrEqualTo(0.75);
        assertThat(1.0 - fullSet("NETHERITE")).isGreaterThanOrEqualTo(0.28 - EPS);
    }

    @Test
    @DisplayName("单件下界合金胸甲 = 0.46 × 0.72 = 0.3312（v1.2 手算）")
    void netheriteChestplateOnly() {
        // 0.46（胸甲权重，v1.2 由 0.40 上调）× 0.72（下界合金系数，v1.2 由 1.00 下调）
        assertThat(ArmorCalculator.reduction(slots(null, "NETHERITE", null, null), W, F))
                .isCloseTo(0.46 * 0.72, within(EPS));
    }

    @Test
    @DisplayName("金与锁链同系数：四层混搭手算 = 0.12×0.30 + 0.46×0.48 + 0.30×0.30 + 0.12×0.15")
    void mixedComboByHand() {
        double expected = 0.12 * 0.30 + 0.46 * 0.48 + 0.30 * 0.30 + 0.12 * 0.15;
        assertThat(ArmorCalculator.reduction(slots("GOLD", "IRON", "CHAINMAIL", "LEATHER"), W, F))
                .isCloseTo(expected, within(EPS)); // = 0.4146
    }

    @Test
    @DisplayName("空槽位/未知材质键计 0（权重非法/越界由 M1 校验兜底，此处防御性截断）")
    void emptySlotsAndUnknownMaterialCountZero() {
        assertThat(ArmorCalculator.reduction(Map.of(), W, F)).isEqualTo(0.0);
        assertThat(ArmorCalculator.reduction(slots(null, null, null, null), W, F)).isEqualTo(0.0);
        assertThat(ArmorCalculator.reduction(slots("WOOD", null, null, null), W, F)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("结果钳 [0,1]：权重系数全 1 的极端表 → 原始和 4.0 截断为 1.0（内核防御，不是可配置玩法）")
    void clampedToUnitInterval() {
        Map<String, Double> allWeights = Map.of("helmet", 1.0, "chestplate", 1.0, "leggings", 1.0, "boots", 1.0);
        Map<String, Double> allFactors = Map.of("NETHERITE", 1.0);
        assertThat(ArmorCalculator.reduction(
                slots("NETHERITE", "NETHERITE", "NETHERITE", "NETHERITE"), allWeights, allFactors))
                .isEqualTo(1.0);
    }

    // ------------------------------------------------------- v1.2 最低杀伤保底

    @Test
    @DisplayName("最低杀伤保底：减免拉满时单轮伤害仍 ≥ 基线 × min-damage-fraction")
    void floorKeepsMinimumDamage() {
        double baseline = 1.0; // base × timeFactor × corrosionMult
        // 未被压到保底以下：原样返回
        assertThat(ArmorCalculator.applyFloor(0.28, baseline, 0.10)).isCloseTo(0.28, within(EPS));
        // 被压到 0（如减免 100% 的极端配置）：抬到 0.10
        assertThat(ArmorCalculator.applyFloor(0.0, baseline, 0.10)).isCloseTo(0.10, within(EPS));
        // 低于保底：抬到保底
        assertThat(ArmorCalculator.applyFloor(0.05, baseline, 0.10)).isCloseTo(0.10, within(EPS));
    }

    @Test
    @DisplayName("最低杀伤保底不凭空造伤害：基线 ≤ 0（夜晚因子 0）时原样返回")
    void floorNeverInventsDamage() {
        assertThat(ArmorCalculator.applyFloor(0.0, 0.0, 0.10)).isEqualTo(0.0);
        assertThat(ArmorCalculator.applyFloor(0.0, -1.0, 0.10)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("配置装配：minDamageFraction 取自 armor.min-damage-fraction（默认 0.10）")
    void minDamageFractionFromConfig() {
        ArmorCalculator calc = new ArmorCalculator(CFG);
        assertThat(calc.minDamageFraction()).isCloseTo(CFG.armor().minDamageFraction(), within(EPS));
        assertThat(calc.minDamageFraction()).isCloseTo(0.10, within(EPS));
        // 实例 floor 与静态内核同口径
        assertThat(calc.floor(0.0, 2.0)).isCloseTo(0.20, within(EPS));
    }

    @Test
    @DisplayName("ArmorConfig#fullSetReduction / maxFullSetReduction 与内核一致（命令摘要素源）")
    void armorConfigHelpersMatchKernel() {
        PluginConfig.ArmorConfig armor = CFG.armor();
        assertThat(armor.fullSetReduction("netherite")).isCloseTo(fullSet("NETHERITE"), within(EPS));
        assertThat(armor.fullSetReduction("NETHERITE")).isCloseTo(fullSet("NETHERITE"), within(EPS));
        assertThat(armor.fullSetReduction("WOOD")).isEqualTo(0.0); // 未知材质按 0
        assertThat(armor.maxFullSetReduction()).isCloseTo(fullSet("NETHERITE"), within(EPS));
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
    @DisplayName("Material 枚举入口与完整盔甲 24 件逐一归类正确（与原版枚举名对应关系核实）")
    void classifyMaterialEnumCoversAllVanillaArmor() {
        Material[] all = {
                Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS,
                Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS,
                Material.CHAINMAIL_HELMET, Material.CHAINMAIL_CHESTPLATE, Material.CHAINMAIL_LEGGINGS, Material.CHAINMAIL_BOOTS,
                Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS,
                Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS,
                Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS,
        };
        assertThat(all).hasSize(24);
        for (Material material : all) {
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
        // 实例 reduction(Map) 与静态内核同口径（v1.2 铁满配 0.48）
        assertThat(calc.reduction(slots("IRON", "IRON", "IRON", "IRON"))).isCloseTo(0.48, within(EPS));
    }
}
