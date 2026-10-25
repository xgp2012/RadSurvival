package com.ft.radsurvival.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import com.ft.radsurvival.config.PluginConfig;
import com.ft.radsurvival.config.PluginConfig.DustLevel;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;

/**
 * 抗尘埃药的物品形态（v1.2 新增）。
 *
 * <p><b>为什么不是「新物品」</b>：Minecraft 不允许插件注册全新的物品 ID（数据包同样做不到），
 * 所以抗尘埃药是<b>原版基底 + 自定义标识</b>：基底用 {@link Material#HONEY_BOTTLE}
 * （1.21.11 的蜂蜜瓶自带饮用动画，且空瓶返还机制天然契合「一瓶药」的直觉），
 * 通过 {@code radsurvival:dust_level} 这个 PDC 标签区分档位，档位数据全部来自配置。</p>
 *
 * <p><b>为什么写 {@code DataComponentTypes.CONSUMABLE} 却不用它配效果</b>：该组件负责
 * <b>「喝」这个动作本身</b>——饮用动画、音效、粒子、以及结束时扣除物品。这些如果自己实现，
 * 要处理副手、堆叠、槽位、物品栏满等一堆边界，还很难做对原版手感。所以这里写入
 * {@code CONSUMABLE}（动画 DRINK、时长 1.6s、给回玻璃瓶），但<b>不写任何 consume 效果</b>
 * ——真正的「降腐蚀 + 免疫 + 解毒」由 {@code listener/AntiDustListener} 在
 * {@code PlayerItemConsumeEvent} 里结算。</p>
 *
 * <p><b>冷却怎么拦</b>：冷却中由监听器<b>取消</b> {@code PlayerItemConsumeEvent}。
 * Bukkit 取消该事件不会扣除物品，因此「冷却中喝不掉、也不亏东西」自然成立，
 * 不需要自己写「先判后扣」的易错逻辑。</p>
 *
 * <p><b>为什么不写原版 {@code USE_COOLDOWN}</b>：它的冷却分组是原版共享的，
 * 写进去会让本插件药水与原版食物/药水互相卡冷却——看起来能用、实际坑服主。
 * 冷却由本插件内存态自行管理（{@code anti_dust.cooldown-seconds}）。</p>
 *
 * <p><b>线程</b>：全部主线程（给予物品、构建物品堆、开容器）。</p>
 */
public final class DustItemFactory {

    /** PDC 命名空间（与腐蚀值同源，便于服主统一识别本插件的数据）。 */
    public static final String PDC_NAMESPACE = "radsurvival";
    /** 档位键：值 = 配置里的档位 id（refined/strong/improved/crude）。 */
    public static final String PDC_LEVEL_KEY = "dust_level";

    /** 档位标签（识别一瓶药属于哪一档）。 */
    public static final NamespacedKey LEVEL_KEY = new NamespacedKey(PDC_NAMESPACE, PDC_LEVEL_KEY);

    /** 物品基底（蜂蜜瓶：自带「喝」的语义与空瓶返还，且不参与任何原版配方链）。 */
    public static final Material BASE_MATERIAL = Material.HONEY_BOTTLE;

    /**
     * 自定义模型数据值：仅作为「这是本插件物品」的额外冗余标记，
     * 方便服主用资源包按 1 个固定数值替换贴图（不占用原版模型）。
     */
    public static final int CUSTOM_MODEL_DATA = 7101;

    private final PluginConfig pluginConfig;

    public DustItemFactory(PluginConfig pluginConfig) {
        this.pluginConfig = pluginConfig;
    }

    /** 全部档位（配置顺序 = 由强到弱）。 */
    public List<DustLevel> levels() {
        return pluginConfig.antiDust().levels();
    }

    /** 按 id 找档位（未知 id 返回 null）。 */
    public DustLevel levelById(String id) {
        if (id == null) {
            return null;
        }
        for (DustLevel level : levels()) {
            if (level.id().equalsIgnoreCase(id)) {
                return level;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 构建

    /**
     * 构建一瓶抗尘埃药。
     *
     * @param level  档位
     * @param amount 数量（≥ 1，会按最大堆叠钳制）
     */
    public ItemStack create(DustLevel level, int amount) {
        ItemStack stack = new ItemStack(BASE_MATERIAL, Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();

        // —— 名称：颜色来自配置（#RRGGBB），斜体关闭（否则物品名会继承默认斜体，观感廉价）
        meta.displayName(Component.text(level.displayName(), hex(level.color()))
                .decoration(TextDecoration.ITALIC, false));

        // —— Lore：把「降多少腐蚀 / 免疫多久」直接写清，玩家不用查文档
        List<Component> lore = new ArrayList<>();
        lore.add(line("辐射环境下服用的应急药剂", NamedTextColor.GRAY));
        lore.add(hud("立即降低腐蚀 ", NamedTextColor.GREEN, "−" + trim(level.corrosionReduction()) + "%"));
        lore.add(hud("获得免疫 ", NamedTextColor.AQUA, level.immuneSeconds() + " 秒"));
        lore.add(line("制作者留下的尘封笔记", NamedTextColor.DARK_GRAY));
        // 逐行关闭斜体：物品名与 Lore 默认继承斜体，观感廉价
        meta.lore(lore.stream().map(c -> c.decoration(TextDecoration.ITALIC, false)).toList());

        // —— 档位标识：唯一可信来源（不依赖名称字符串，改语言/改名都不影响识别）
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(LEVEL_KEY, PersistentDataType.STRING, level.id());

        stack.setItemMeta(meta);

        // —— Paper 数据组件：
        //    · MAX_STACK_SIZE 8：一瓶药不该像方块一样堆 64
        //    · CONSUMABLE：交出「喝」的动作（动画/音效/扣物品），效果由监听器结算
        //    · ENCHANTMENT_GLINT_OVERRIDE：微光，让它在物品栏里一眼可辨
        stack.setData(DataComponentTypes.MAX_STACK_SIZE, 8);
        stack.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
                .consumeSeconds(1.6f)
                .animation(ItemUseAnimation.DRINK)
                .sound(Key.key("minecraft:entity.generic.drink"))
                .hasConsumeParticles(false) // 效果结算时由监听器自绘 INSTANT_EFFECT，避免粒子重复
                .build());
        stack.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    /** 便捷：单瓶。 */
    public ItemStack create(DustLevel level) {
        return create(level, 1);
    }

    // ------------------------------------------------------------------ 识别

    /** 该物品是否为抗尘埃药（PDC 标签存在即可，不校验档位是否仍有效）。 */
    public static boolean isDust(ItemStack stack) {
        if (stack == null || stack.getType() != BASE_MATERIAL) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        return meta.getPersistentDataContainer().has(LEVEL_KEY, PersistentDataType.STRING);
    }

    /** 读取档位 id；非抗尘埃药返回 null。 */
    public static String levelIdOf(ItemStack stack) {
        if (!isDust(stack)) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(LEVEL_KEY, PersistentDataType.STRING);
    }

    /**
     * 解析物品所属档位（PDC → 配置）。档位 id 在配置里已不存在（服主删了档位）时返回 null，
     * 此时物品成为「失效药剂」：不可服用、但也不会误判成别的档位。
     */
    public DustLevel levelOf(ItemStack stack) {
        return levelById(levelIdOf(stack));
    }

    // ------------------------------------------------------------------ 小工具

    /** #RRGGBB → Adventure 颜色；非法值退回白色（配置校验已兜底，这里是防御性降级）。 */
    private static TextColor hex(String color) {
        try {
            return TextColor.fromHexString(color);
        } catch (RuntimeException ex) {
            return NamedTextColor.WHITE;
        }
    }

    private static Component line(String text, NamedTextColor fallback) {
        return Component.text(stripLeadingColor(text), fallback);
    }

    /**
     * 极简 § 颜色码剥离：Lore 的第一行允许带 §7/§8 这类传统码（服主习惯），
     * 但 Adventure 组件不接受 § 前缀，故剥离后统一走 NamedTextColor。
     */
    private static String stripLeadingColor(String text) {
        int i = 0;
        while (i + 1 < text.length() && text.charAt(i) == '§') {
            i += 2;
        }
        return text.substring(i);
    }

    private static Component hud(String prefix, NamedTextColor color, String value) {
        return Component.text(prefix, color).append(Component.text(value, NamedTextColor.WHITE));
    }

    /** 数值瘦身（1.0 → 1、12.5 → 12.5）。 */
    private static String trim(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** 供 GUI/命令复用的档位摘要（`精制抗尘埃药 · −50% / 30s`）。 */
    public static String describe(DustLevel level) {
        return level.displayName() + " · −" + trim(level.corrosionReduction())
                + "% / " + level.immuneSeconds() + "s";
    }

    /** 调试/测试读口：本工厂认得的物品基底（大写字面量）。 */
    public static String baseMaterialName() {
        return BASE_MATERIAL.name().toUpperCase(Locale.ROOT);
    }
}
