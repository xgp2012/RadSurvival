package com.ft.radsurvival.item;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * 抗尘埃药的制作台结构（v1.2 新增；本版改为「三格竖列」）。
 *
 * <p><b>结构定义</b>（玩家站在地面看到的画面，就是<b>竖着叠起来的三格</b>）：</p>
 * <pre>
 *      铁砧      ← 最上面一格：合成时用木棍右键它
 *     发射器     ← 中间一格：本体，它的 UI 就是合成台
 *      铁块      ← 最下面一格：底座
 * </pre>
 *
 * <p>确切口径（实现即定义，避免歧义）：</p>
 * <ol>
 *   <li>核心方块：{@link Material#DISPENSER}（发射器）或 {@link Material#DROPPER}（投掷器）——
 *       两者在中文里一个叫投掷器一个叫发射器，服主常混用，这里同时接受；</li>
 *   <li>核心<b>正上方</b>一格必须是铁砧（{@code Tag.ANVIL}：普通/损坏/上蜡铁砧都算）；</li>
 *   <li>核心<b>正下方</b>一格必须是铁块（{@link Material#IRON_BLOCK}）——
 *       旧版本没要求过下方，只在正下方要求「不是别的容器」时才会抱怨，本版起是硬要求；</li>
 *   <li><b>不再要求周围一圈原木</b>（旧版本要求同层 8 个水平邻居全是 {@code Tag.LOGS}，
 *       本版移除）：四个水平方向放什么都不参与判定；</li>
 *   <li>农作物不放方块上，而是放进核心方块的容器 UI 中心格（见 {@link DustCraftGui}）。</li>
 * </ol>
 *
 * <p><b>为什么改成这样</b>：原木环是一层纯仪式性的门槛——它既不参与消耗也不影响产出，
 * 却让「搭一台制药台」变成搬 9 个方块的体力活，而且必须搭在地面上（否则下方没有水平环）。
 * 改成一根竖列之后：形状一眼可辨、占地只有 1×1、搬动/重建成本极低。</p>
 *
 * <p><b>纯函数优先</b>：{@link #isAnvil(Material)} / {@link #isBase(Material)} /
 * {@link #isCoreContainer(Material)} 只吃 {@link Material}，可脱离服务器单测；
 * {@link #inspect(Block)} 才触方块读取，且<b>只在主线程调用</b>（事件处理器内）。</p>
 */
public final class DustCraftStructure {

    /** 结构校验结果。 */
    public record Result(boolean valid, Block core, Block anvil, List<String> problems) {

        /** 通过。 */
        public boolean ok() {
            return valid;
        }

        /** 人类可读的一句话结论（GUI 标题 / 命令输出用）。 */
        public String summary() {
            if (valid) {
                return "结构完整：铁砧 + 发射器 + 铁块";
            }
            return problems.isEmpty() ? "结构不完整" : String.join("；", problems);
        }
    }

    private DustCraftStructure() {
    }

    // ---------------------------------------------------------------- 纯判定

    /** 是否为可作核心的容器方块（发射器/投掷器）。 */
    public static boolean isCoreContainer(Material material) {
        return material == Material.DISPENSER || material == Material.DROPPER;
    }

    /** 是否为铁砧（含损坏/上蜡变体；1.21.11 的 ANVIL tag 已覆盖三种）。 */
    public static boolean isAnvil(Material material) {
        return material != null && Tag.ANVIL.isTagged(material);
    }

    /** 是否为底座方块（铁块）。 */
    public static boolean isBase(Material material) {
        return material == Material.IRON_BLOCK;
    }

    /**
     * 是否为原木（含木干、去皮木干等，{@code Tag.LOGS} 口径）。
     *
     * <p><b>注意</b>：自「三格竖列」那一版起，结构校验<b>不再调用</b>本方法
     * （原木环要求已整体移除）。保留它只是为了兼容既有测试对「原木口径」的引用，
     * 生产路径中不再有任何原木要求。</p>
     */
    public static boolean isWood(Material material) {
        return material != null && Tag.LOGS.isTagged(material);
    }

    /**
     * 纯逻辑：给定「核心方块类型 + 上方类型 + 下方类型」，判定结构是否成立。
     *
     * <p>结构为竖着三格：<b>上方铁砧 + 核心容器 + 下方铁块</b>，四个水平方向不参与判定。
     * 抽出这一层是为了让结构规则可被单测完全覆盖（MockBukkit 的方块读取能力有限），
     * {@link #inspect(Block)} 只负责把真实方块读成这三种输入。</p>
     *
     * @param core  核心方块类型
     * @param above 正上方类型（应为铁砧）
     * @param below 正下方类型（应为铁块）
     */
    public static List<String> validate(Material core, Material above, Material below) {
        List<String> problems = new ArrayList<>(3);
        if (!isCoreContainer(core)) {
            problems.add("核心方块必须是发射器/投掷器（当前：" + pretty(core) + "）");
        }
        if (!isAnvil(above)) {
            problems.add("核心正上方必须是铁砧（当前：" + pretty(above) + "）");
        }
        if (!isBase(below)) {
            problems.add("核心正下方必须是铁块（当前：" + pretty(below) + "）");
        }
        return problems;
    }

    // ---------------------------------------------------------------- 真实方块

    /**
     * 读取真实方块并校验结构（<b>主线程</b>）。
     *
     * <p>不做任何写入/消耗，纯只读——可以放心在右键事件里高频调用。</p>
     */
    public static Result inspect(Block core) {
        if (core == null) {
            return new Result(false, null, null, List.of("目标方块为空"));
        }
        // 世界已卸载（如插件在 onDisable 后仍被调用、或调试时拿到脱离世界的 Block）：
        // 直接 getRelative 会 NPE，这里提前挡掉并给一条可读原因。
        if (core.getWorld() == null) {
            return new Result(false, null, null, List.of("所在世界未加载，无法判定结构"));
        }
        Block anvil = core.getRelative(BlockFace.UP);
        Block base = core.getRelative(BlockFace.DOWN);
        List<String> problems = validate(core.getType(), anvil.getType(), base.getType());
        return new Result(problems.isEmpty(), core, anvil, List.copyOf(problems));
    }

    /** 核心方块是否是带物品栏的容器实现（发射器/投掷器 BlockState 均为 {@link InventoryHolder}）。 */
    public static boolean hasInventory(Block core) {
        return inventoryOf(core) != null;
    }

    /**
     * 读取核心方块 UI 里的「中心格」物品（第 5 格，即 3×3 的正中）。
     *
     * <p>容器只有 9 格，0..8 映射到 3×3 的左上→右下，中心即索引 4；
     * 无论结构怎么改（原木环已取消），作物位始终是这一格。</p>
     *
     * <p><b>为什么取实时 BlockInventoryHolder</b>：{@code getState()} 返回的是<b>快照</b>，
     * 对快照的 inventory 写入必须再 {@code update()} 才落回真实方块；而生产代码里
     * 「读完中心格 → 写回剩余数量」必须作用在<b>同一份实时容器</b>上，否则第二次写会覆盖第一次。
     * 所以这里统一走 {@link BlockInventoryHolder#getInventory()}（直接是方块自身的容器）。</p>
     */
    public static ItemStack centerItem(Block core) {
        Inventory inventory = inventoryOf(core);
        if (inventory == null || inventory.getSize() < 9) {
            return null;
        }
        return inventory.getItem(CENTER_SLOT);
    }

    /**
     * 取核心方块的<b>实时</b>容器（不是快照）。
     *
     * <p>优先 {@link BlockInventoryHolder}（1.21.11 的发射器/投掷器都实现了它）——
     * 它返回的 Inventory 直接操作方块本身，读写立即生效；
     * 老式 {@code getState()} 整条链路只是<b>兜底</b>（快照 + update 的写法容易丢写）。</p>
     */
    public static Inventory inventoryOf(Block core) {
        if (core == null) {
            return null;
        }
        BlockState state = core.getState();
        if (state instanceof BlockInventoryHolder live) {
            return live.getInventory();
        }
        if (state instanceof InventoryHolder holder) {
            return holder.getInventory();
        }
        return null;
    }

    /** GUI 中心格在容器 storage 里的索引（0-based）。 */
    public static final int CENTER_SLOT = 4;

    /**
     * 容器 UI 里「外围一圈」的 8 个格子索引（= 0..8 去掉 {@link #CENTER_SLOT}）。
     *
     * <p>这 8 格只是容器自带的格子，在本玩法里既不参与结构判定（原木环已取消），
     * 也不影响合成——合成只看中心格 {@link #CENTER_SLOT}。</p>
     */
    public static List<Integer> ringSlots() {
        List<Integer> out = new ArrayList<>(8);
        for (int i = 0; i < 9; i++) {
            if (i != CENTER_SLOT) {
                out.add(i);
            }
        }
        return List.copyOf(out);
    }

    private static String pretty(Material material) {
        return material == null ? "空" : material.name();
    }

    /** GUI 标题里的核心方块名（发射器/投掷器的中文名不同，按实际类型给）。 */
    public static String coreDisplayName(Material material) {
        return material == Material.DROPPER ? "投掷器" : "发射器";
    }
}
