package com.ft.radsurvival.core;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import com.ft.radsurvival.config.PluginConfig;

/**
 * 盔甲减免计算（plan.md §1.4）：减免 = Σ(部位权重 × 材质系数)，空槽位计 0，结果钳 [0,1]。
 *
 * <p>拆分为「纯逻辑内核 + 薄 Bukkit 适配层」：</p>
 * <ul>
 *   <li>纯内核：{@link #reduction(Map, Map, Map)} 只吃「{@link ArmorSlot} → 材质键
 *       （LEATHER/GOLD/CHAINMAIL/IRON/DIAMOND/NETHERITE）」映射与配置的两张数值表；
 *       {@link #classify(String)} 只吃 {@code Material.name()} 字符串——均无需服务器；</li>
 *   <li>适配层：{@link #slotMaterialsOf(ItemStack[])} / {@link #reductionOf(Player)} 从
 *       {@code PlayerInventory#getArmorContents()} 提取 Material 并按前缀归类。</li>
 * </ul>
 *
 * <p>默认权重（v1.2）：头盔 0.12 / 胸甲 0.46 / 护腿 0.30 / 靴子 0.12（合计 1.00）；
 * 默认系数（v1.2 全面削弱）：皮革 0.15、金/锁链 0.30、铁 0.48、钻石 0.60、下界合金 0.72
 * → 全套满配减免分别为 15% / 30% / 48% / 60% / 72%。
 * <b>旧口径「全套下界合金恰 1.0 = 完全免疫」已移除</b>：{@link PluginConfig} 校验会拒绝
 * 任何「权重和 × 系数 ≥ 1.0」的组合；运行期另有 {@link #minDamageFraction} 最低杀伤保底，
 * 双保险确保阳光永远无法被零封。数值合法性（∈[0,1]、材质键白名单）由
 * {@link PluginConfig} 校验兜底，本类不重复校验；未知材质键按 0 处理。</p>
 */
public final class ArmorCalculator {

    /** 四个盔甲部位。{@link #configKey()} 与 config.yml armor.slot-weights 的小写键一致（§3）。 */
    public enum ArmorSlot {
        HELMET("helmet", "_HELMET"),
        CHESTPLATE("chestplate", "_CHESTPLATE"),
        LEGGINGS("leggings", "_LEGGINGS"),
        BOOTS("boots", "_BOOTS");

        private final String configKey;
        private final String nameSuffix;

        ArmorSlot(String configKey, String nameSuffix) {
            this.configKey = configKey;
            this.nameSuffix = nameSuffix;
        }

        /** config.yml 中的部位键（小写）。 */
        public String configKey() {
            return configKey;
        }

        /** 原版 Material 枚举名后缀（如 IRON_HELMET → "_HELMET"）。 */
        public String nameSuffix() {
            return nameSuffix;
        }

        /** 按枚举名后缀识别部位；非盔甲后缀返回 null。 */
        public static ArmorSlot ofItemName(String materialName) {
            for (ArmorSlot slot : values()) {
                if (materialName.endsWith(slot.nameSuffix)) {
                    return slot;
                }
            }
            return null;
        }
    }

    /** 归类结果：部位 + 材质键（§1.4 白名单之一）。 */
    public record ArmorItem(ArmorSlot slot, String materialKey) {
    }

    /** 材质名 → 配置材质键映射（与原版 Material 前缀的对应关系，§1.4）：
     *  LEATHER_→LEATHER、GOLDEN_→GOLD（原版盔甲枚举名是 GOLDEN_ 而非 GOLD_）、
     *  CHAINMAIL_→CHAINMAIL、IRON_→IRON、DIAMOND_→DIAMOND、NETHERITE_→NETHERITE。 */
    private static final Map<String, String> MATERIAL_PREFIXES = Map.of(
            "LEATHER_", "LEATHER",
            "GOLDEN_", "GOLD",
            "CHAINMAIL_", "CHAINMAIL",
            "IRON_", "IRON",
            "DIAMOND_", "DIAMOND",
            "NETHERITE_", "NETHERITE");

    private final Map<String, Double> slotWeights;
    private final Map<String, Double> materialFactors;

    public ArmorCalculator(Map<String, Double> slotWeights, Map<String, Double> materialFactors) {
        this.slotWeights = slotWeights;
        this.materialFactors = materialFactors;
    }

    /** 从类型安全配置装配（armor.slot-weights / armor.material-factors，§3）。 */
    public ArmorCalculator(PluginConfig config) {
        this(config.armor());
    }

    /** 从盔甲配置段装配（v1.2：含 min-damage-fraction 最低杀伤保底）。 */
    public ArmorCalculator(PluginConfig.ArmorConfig armorConfig) {
        this(armorConfig.slotWeights(), armorConfig.materialFactors());
        this.minDamageFraction = armorConfig.minDamageFraction();
    }

    /** 创建实例时的最低杀伤比例兜底默认值（与 config.yml 默认一致）。 */
    private static final double DEFAULT_MIN_DAMAGE_FRACTION = 0.10;

    /** 最低杀伤保底（v1.2）：无论减免多高，单轮伤害不低于基线的该比例。 */
    private double minDamageFraction = DEFAULT_MIN_DAMAGE_FRACTION;

    /** 最低杀伤保底比例（armor.min-damage-fraction）。 */
    public double minDamageFraction() {
        return minDamageFraction;
    }

    // ---------------------------------------------------------------- 纯内核

    /**
     * 纯内核：减免 = Σ(部位权重 × 材质系数)，缺失部位 / null / 未知材质键均计 0，
     * 结果钳 [0,1]（防御性截断；权重和 ≤ 1 时天然不越界，越界场景也绝不产生负伤害加成）。
     *
     * @param slotMaterials 部位 → 材质键（大写，如 "IRON"）；槽位缺失或值为 null = 空槽
     * @param slotWeights   部位权重表，键为小写（helmet/chestplate/leggings/boots）
     * @param materialFactors 材质系数表，键为大写材质名
     */
    public static double reduction(Map<ArmorSlot, String> slotMaterials,
                                   Map<String, Double> slotWeights,
                                   Map<String, Double> materialFactors) {
        if (slotMaterials == null || slotMaterials.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Map.Entry<ArmorSlot, String> entry : slotMaterials.entrySet()) {
            String materialKey = entry.getValue();
            if (materialKey == null || materialKey.isBlank()) {
                continue; // 空槽位计 0（§1.4）
            }
            Double weight = slotWeights.get(entry.getKey().configKey());
            Double factor = materialFactors.get(materialKey.toUpperCase(Locale.ROOT));
            if (weight == null || factor == null) {
                continue; // 非法/未知键按 0（配置白名单校验由 M1 PluginConfig 兜底）
            }
            sum += weight * factor;
        }
        return RadiationMath.clampUnit(sum);
    }

    /** 内核实例形态：使用装配好的两张配置表。 */
    public double reduction(Map<ArmorSlot, String> slotMaterials) {
        return reduction(slotMaterials, slotWeights, materialFactors);
    }

    /**
     * 纯内核：把 {@code Material.name()} 字符串归类为「部位 + 材质键」。
     * 判定 = 前缀 ∈ {@link #MATERIAL_PREFIXES} <b>且</b> 后缀 ∈ 四个盔甲部位；
     * 两者缺一不可——IRON_SWORD / GOLDEN_APPLE / DIAMOND_HORSE_ARMOR / ELYTRA / CHAIN（锁链方块）
     * 等非盔甲物品一律返回 null（计入 0，§1.4 空槽/非盔甲口径）。
     */
    public static ArmorItem classify(String materialName) {
        if (materialName == null) {
            return null;
        }
        ArmorSlot slot = ArmorSlot.ofItemName(materialName);
        if (slot == null) {
            return null;
        }
        for (Map.Entry<String, String> prefix : MATERIAL_PREFIXES.entrySet()) {
            if (materialName.startsWith(prefix.getKey())) {
                return new ArmorItem(slot, prefix.getValue());
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 适配层

    /** {@link Material} 枚举便捷入口：等价于 classify(material.name())。 */
    public static ArmorItem classify(Material material) {
        return material == null ? null : classify(material.name());
    }

    /**
     * 适配层：从盔甲槽数组（{@code PlayerInventory#getArmorContents()} 返回
     * [boots, leggings, chestplate, helmet]）提取归类结果。
     * 按「物品名后缀 → 部位」映射，不依赖数组顺序；null 槽与 AIR 均跳过（= 空槽计 0）。
     */
    public static Map<ArmorSlot, String> slotMaterialsOf(ItemStack[] armorContents) {
        EnumMap<ArmorSlot, String> out = new EnumMap<>(ArmorSlot.class);
        if (armorContents == null) {
            return out;
        }
        for (ItemStack stack : armorContents) {
            if (stack == null) {
                continue;
            }
            ArmorItem item = classify(stack.getType()); // 非盔甲（如手持物误入）自动忽略
            if (item != null) {
                out.put(item.slot(), item.materialKey());
            }
        }
        return out;
    }

    /** 适配层：玩家当前盔甲槽归类。 */
    public static Map<ArmorSlot, String> slotMaterialsOf(PlayerInventory inventory) {
        return slotMaterialsOf(inventory.getArmorContents());
    }

    /** 适配层：玩家盔甲减免 [0,1]（§1.4；主循环 M2b 每结算周期调用一次）。 */
    public double reductionOf(Player player) {
        return reduction(slotMaterialsOf(player.getInventory()));
    }

    /** 适配层：直接对 armorContents 数组算减免（便于测试与复用）。 */
    public double reductionOf(ItemStack[] armorContents) {
        return reduction(slotMaterialsOf(armorContents));
    }

    // ------------------------------------------------- v1.2 最低杀伤保底

    /**
     * 把公式伤害抬到「最低杀伤保底」（v1.2 新增，取消完全免疫后的配套约束）。
     *
     * <p>语义：{@code finalDamage = max(公式伤害, 基线 × minDamageFraction)}，其中
     * 「基线」= {@code base × timeFactor × corrosionMult}（即尚未乘 (1−减免) 的部分）。
     * 这样即使服主把系数调到接近免疫，阳光也永远保有一份杀伤力。</p>
     *
     * <p>纯函数，无钳制副作用：{@code baseline <= 0}（如夜晚因子为 0）时原样返回，
     * 不会凭空造出伤害。</p>
     *
     * @param damage   已乘过 (1−减免) 的公式伤害
     * @param baseline 未乘减免的基线（base × timeFactor × corrosionMult）
     */
    public static double applyFloor(double damage, double baseline, double minDamageFraction) {
        if (baseline <= 0.0) {
            return damage;
        }
        double floor = baseline * RadiationMath.clampUnit(minDamageFraction);
        return Math.max(damage, floor);
    }

    /** 实例形态：使用装配进来的 min-damage-fraction。 */
    public double floor(double damage, double baseline) {
        return applyFloor(damage, baseline, minDamageFraction);
    }
}
