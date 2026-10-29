package com.ft.radsurvival.item;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.kyori.adventure.text.Component;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Dispenser;
import org.bukkit.block.Dropper;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.config.PluginConfig;

/**
 * 抗尘埃药的合成界面与触发（v1.2 新增）：
 * <b>发射器的容器 UI 本身就是合成台</b>，「木棍右键铁砧」完成合成。
 *
 * <p><b>设计取舍（为什么用「容器 UI」而不是自绘 54 格 GUI）</b>：<br>
 * 自绘 GUI 必须靠 {@code openInventory(自定义 Holder)} 把玩家塞进一个假界面，物品全在内存里，
 * 关服即丢、被踢会丢、也没法被漏斗/红石看见——玩家会觉得「东西悬空」。
 * 而发射器是原版容器：9 格物品栏 <b>在外围一圈原木环 + 中心放作物</b> 这个语境下刚好
 * 与 {@code 3×3} 的形状完全对应（中心 = 索引 4），并且自动获得
 * 「掉线不丢、可被漏斗看见、可 /data 查看」的原版语义。因此本类不替换容器，
 * 只<b>约束</b>它的使用方式。</p>
 *
 * <p><b>交互规则</b>（实现即定义）：</p>
 * <ul>
 *   <li>右键核心方块 → 照常打开它的原版 UI（不需要任何权限，结构不完整也能开）；</li>
 *   <li>中心格（索引 4）只接受「本插件认得的农作物」。放别的物品会被拒绝并退回玩家背包
 *       + 提示「不认识这种作物」——避免玩家把发射器当免费箱子用；</li>
 *   <li>外围 8 格在本玩法里是<b>纯展示位</b>（镜子），玩家往里放的任何东西也会被退回，
 *       并提示「作物请放正中间」；</li>
 *   <li>结构不完整（缺铁砧 / 缺原木）时中心格依旧可放（方便先把作物备好），
 *       但右键铁砧不会产出任何东西，而是给出缺什么的提示。</li>
 * </ul>
 *
 * <p><b>为什么限制「只放农作物」</b>：发射器/投掷器是红石元件，不限制的话它会变成
 * 「免费 9 格私有仓库」，破坏服里的物流设计；限制后它只是一个加工台。</p>
 *
 * <p><b>线程</b>：全部在事件回调（主线程）内完成方块/容器读写。</p>
 */
public final class DustCraftGui implements Listener {

    /** 容器容量（发射器/投掷器固定 9 格）。 */
    public static final int SIZE = 9;

    /** 右键铁砧的「钥匙」物品（不被消耗）。 */
    public static final Material TRIGGER_TOOL = Material.STICK;

    private final RadSurvivalPlugin plugin;
    /**
     * 物品工厂：<b>非 final</b>，因为 reload 会换新配置（新档位表/新颜色/新时长）。
     *
     * <p>旧实现是 final 且只在首次装配时注入，于是 {@code /rad reload} 之后
     * 「判定用新配置、产出用旧配置」——改了档位表的减速/时长/作物归属后，
     * 合成出来的药水 Lore 与 PDC 仍按旧值生成。由
     * {@link com.ft.radsurvival.RadSurvivalPlugin#installAntiDust} 调用 {@link #rebind} 换掉。</p>
     */
    private volatile DustItemFactory items;

    public DustCraftGui(RadSurvivalPlugin plugin, DustItemFactory items) {
        this.plugin = plugin;
        this.items = items;
    }

    /**
     * reload 时换绑新的物品工厂（{@link com.ft.radsurvival.RadSurvivalPlugin#installAntiDust} 调用）。
     *
     * <p>为什么不重建整个监听器：Bukkit 的 HandlerList 不支持按实例注销后再注册新实例
     * 而不留痕迹，换绑一个 volatile 引用是成本最低且不会漏事件的写法。</p>
     */
    public void rebind(DustItemFactory factory) {
        if (factory != null) {
            this.items = factory;
        }
    }

    /**
     * 防重复触发：记录「玩家 → 上次合成的时间戳」。
     *
     * <p><b>为什么需要</b>：{@link PlayerInteractEvent} 在双手都拿着木棍时，
     * 主手与副手会<b>各派发一次</b> RIGHT_CLICK_BLOCK（两次都是 {@code getHand()==HAND}
     * 只是 {@code getItem()} 不同）。若不去重，一次右键会瞬间合成两瓶、
     * 白扣两个作物——玩家视角就是「莫名其妙多花了一个农作物」。</p>
     *
     * <p>用「时间窗」而不是「记住上一只手」：时间窗对任何重复派发路径都成立，
     * 不依赖具体是主手/副手还是别的插件重复 callEvent。窗口取 4 tick（200ms），
     * 远小于人手连续两次右键的间隔（正常连点 ≥ 100ms 但不会落在同一 tick 的同一方块上，
     * 实测双击间隔通常 > 250ms），因此不会误伤真实连点。</p>
     */
    private final Map<UUID, String> craftGuard = new ConcurrentHashMap<>();

    /** 同一玩家的两次合成视为「同一次右键」的最大时间差（毫秒）。 */
    private static final long CRAFT_DEBOUNCE_MS = 150L;

    /**
     * 判断本次交互是否应当跳过（同一 tick 内的重复派发）。
     *
     * <p><b>bug hunt P2-4 修复</b>：口径从「200ms 内都算重复」收紧为
     * 「同一 tick 或 150ms 内，且<b>同一个铁砧</b>」。原实现只看时间、不看目标方块，
     * 且调用点在 {@code setCancelled(true)} 之后——玩家对 A 铁砧右键后 150ms 内再点 B 铁砧，
     * 事件会被取消而合成不发生，表现为「点了没反应」。现在：
     * <ul>
     *   <li>引入方块坐标：换了目标方块就不是重复（真实连点/换台操作不受影响）；</li>
     *   <li>窗口取 {@link #CRAFT_DEBOUNCE_MS}，覆盖原版同 tick 的双手双派发，
     *       又不会吞掉人手双击（原注释自己承认实测双击 &gt; 250ms 与 200ms 窗口过于接近）；</li>
     *   <li>调用点已提前到 {@code setCancelled} 之前，重复事件不再被无谓地取消掉。</li>
     * </ul>
     *
     * @param player 触发玩家
     * @param anvil  本次点到的铁砧方块
     * @return true = 这是重复事件，应直接忽略（且不应取消事件）
     */
    private boolean isDuplicate(Player player, Block anvil) {
        long now = System.currentTimeMillis();
        String here = anvil.getX() + "," + anvil.getY() + "," + anvil.getZ();
        String previous = craftGuard.put(player.getUniqueId(), now + "@" + here);
        if (previous == null) {
            return false;
        }
        int at = previous.indexOf('@');
        if (at <= 0) {
            return false; // 旧格式/脏数据：无从比对，按不重复处理
        }
        if (!here.equals(previous.substring(at + 1))) {
            return false; // 换了方块 → 不是同一次右键
        }
        try {
            return now - Long.parseLong(previous.substring(0, at)) < CRAFT_DEBOUNCE_MS;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    // ------------------------------------------------------------ 触发：木棍右键铁砧

    /**
     * 木棍右键铁砧 → 若铁砧正下方是核心方块，则尝试合成。
     *
     * <p>为什么挂在铁砧上而不是发射器上：需求原文是「用木棍右键一下铁砧」。
     * 优先 LOWEST 且不取消事件——普通右键铁砧本来就没做什么，不取消以保留原版手感
     * （也不影响其他插件监听）。</p>
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAnvilInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null || !DustCraftStructure.isAnvil(clicked.getType())) {
            return;
        }
        // 只认主手：副手也持木棍时原版会各派发一次，若都处理会一次右键出两瓶。
        // 潜行同样跳过——原版潜行右键是「对着方块使用物品」，接管它会吞掉
        // 其他插件（铁砧修复/展示架）的交互，属于不该碰的领域。
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || event.getPlayer().isSneaking()) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = event.getItem();
        if (hand == null || hand.getType() != TRIGGER_TOOL) {
            return; // 只有木棍才是钥匙（其余物品保持原版行为）
        }
        Block core = clicked.getRelative(org.bukkit.block.BlockFace.DOWN);
        if (!DustCraftStructure.isCoreContainer(core.getType())) {
            return; // 铁砧下面不是发射器：与原版一致，什么也不做
        }
        PluginConfig cfg = plugin.config();
        if (cfg == null || !cfg.antiDust().enabled()) {
            return; // 开关关闭：不接管，保持原版
        }
        // 结构不完整时**不取消事件**：让原版/其他插件继续处理这次右键，
        // 我们只给出提示。旧实现在这里无条件 setCancelled(true)，等于对着
        // 「发射器 + 铁砧」这个组合永久霸占了木棍右键。
        if (!DustCraftStructure.inspect(core).ok()) {
            craft(player, core, clicked); // 会打出结构诊断（不消耗任何东西）
            return;
        }
        // bug hunt P2-4：判重必须发生在 setCancelled **之前**——
        // 否则重复事件会被取消、合成却不发生，玩家看到「点了没反应」，
        // 而且原版的「使用物品」逻辑也被无谓吞掉。
        if (isDuplicate(player, clicked)) {
            return;
        }
        // 真正会产出时才接管本次交互，避免原版再走一遍「使用物品」逻辑
        event.setCancelled(true);
        craft(player, core, clicked);
    }

    // -------------------------------------------------------------- 合成主流程

    /**
     * 尝试一次合成（单轮、可失败）。
     *
     * <p>顺序刻意如此：<b>先校验结构 → 再校验作物 → 最后才动物品</b>。
     * 任何一步失败都不消耗任何东西（失败不惩罚，符合「加工台」的直觉）。</p>
     *
     * @return 成功产出的药水数量（0 = 失败）
     */
    public int craft(Player player, Block core, Block anvil) {
        // 防御性：本方法是 public（命令层与测试也会直接调），不能假设调用方都传了有效方块
        if (player == null || core == null || anvil == null) {
            return 0;
        }
        PluginConfig cfg = plugin.config();
        if (cfg == null || !cfg.antiDust().enabled()) {
            return 0;
        }
        DustCraftStructure.Result structure = DustCraftStructure.inspect(core);
        if (!structure.ok()) {
            player.sendActionBar(note("§c结构不完整§8 · §7" + structure.summary()));
            playFail(player);
            return 0;
        }

        if (!DustCraftStructure.hasInventory(core)) {
            player.sendActionBar(note("§c这台设备的物品栏不可用§8 · §7换个位置重建"));
            playFail(player);
            return 0;
        }
        // 必须用实时容器（BlockInventoryHolder），不能用 getState() 快照——
        // 「读中心格 → 写回剩余数量」要作用在同一份实时 inventory 上
        Inventory inv = DustCraftStructure.inventoryOf(core);
        if (inv == null || inv.getSize() < SIZE) {
            player.sendActionBar(note("§c容器结构异常§8 · §7请重新放置发射器"));
            playFail(player);
            return 0;
        }
        ItemStack center = inv.getItem(DustCraftStructure.CENTER_SLOT);
        if (center == null || center.getType() == Material.AIR) {
            player.sendActionBar(note("§c中心格是空的§8 · §7把农作物放进发射器正中间"));
            playFail(player);
            return 0;
        }
        PluginConfig.DustLevel level = cfg.antiDust().levelFor(center.getType().name());
        if (level == null) {
            player.sendActionBar(note("§c" + cn(center.getType()) + " 不是有效的抗尘埃原料§8 · §7试试小麦、南瓜、下界疣"));
            playFail(player);
            return 0;
        }

        // —— 先确认「有空位」才动手：交付与支付必须原子成立（bug hunt P0-1）。
        //
        //  历史两次都栽在同一处：
        //   ① 初版「先扣作物 → 再 addItem → 失败把整组写回」：写回的是原始整组，
        //      等于作物没扣又掉出一瓶 → 背包满时无限刷药；
        //   ② 二次修正「先给后扣，溢出就丢地上并 return 0」：地上那一瓶**已经是产出**，
        //      但 return 0 跳过了扣作物 → 刷药路径原样保留，只是换了个分支
        //      （既有测试 fullInventoryDoesNotConsumeCrop 还把它断言成了正确行为）。
        //
        //  现在的口径很硬：**要么作物 −1 且药水到手，要么两者都不变**。
        //  因此先精确预判「放不放得下」，放不下就整单失败、不产出任何东西；
        //  放得下才 addItem，此时 addItem 必然全部收下（同 tick 内没有第三方抢位）。
        ItemStack potion = items.create(level, 1);
        if (!hasRoomFor(player.getInventory(), potion)) {
            // 整单失败：不产出、不扣作物（「失败不惩罚」的加工台语义）
            player.sendActionBar(note("§c背包已满§8 · §7腾出一个空位后再来（作物未消耗）"));
            playFail(player);
            return 0;
        }
        // addItem 在 hasSpaceFor 通过后必然全收；overflow 分支仅作防御性兜底，
        // 一旦真的发生（第三方插件在同 tick 抢占），必须把药水如实落地——
        // 因为此刻已经「交付」，下面的扣作物不能再被跳过。
        var overflow = player.getInventory().addItem(potion);
        boolean dropped = false;
        for (ItemStack rest : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
            dropped = true;
        }

        // —— 交付已成立，无条件扣除 1 个作物（产出与支付同生共死）
        int remaining = center.getAmount() - 1;
        if (remaining > 0) {
            ItemStack leftover = center.clone();
            leftover.setAmount(remaining);
            inv.setItem(DustCraftStructure.CENTER_SLOT, leftover);
        } else {
            inv.setItem(DustCraftStructure.CENTER_SLOT, null);
        }

        player.sendActionBar(note((dropped ? "§e背包已满，药水落在脚下§8 · §7" : "§a已制成 §f")
                + level.displayName()
                + " §8· §7−" + trim(level.corrosionReduction()) + "% / "
                + level.immuneSeconds() + "s"));

        // —— 反馈：铁砧被敲 + 药水调配声（不给 title，避免打断操作）
        //    ⚠️ 音效/粒子是「已经发完物品之后」的锦上添花，必须整体包在 try 里：
        //    铁砧所在区块若在合成瞬间被卸载（玩家跨区块、插件卸载世界），
        //    playSound(anvil.getLocation())/spawnParticle 会抛异常，进而让
        //    PlayerInteractEvent 的回调炸掉——物品已经进背包了，此时抛异常只会污染日志
        //    并可能让其他监听器收不到事件。宁可不播特效，不可炸主流程。
        try {
            if (cfg.feedback().sound()) {
                player.playSound(anvil.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.2f);
                player.playSound(player.getLocation(), Sound.ITEM_BOTTLE_FILL, 0.7f, 0.9f);
            }
            if (cfg.feedback().particles()) {
                anvil.getWorld().spawnParticle(org.bukkit.Particle.WHITE_SMOKE,
                        anvil.getLocation().add(0.5, 1.0, 0.5), 8, 0.25, 0.15, 0.25, 0.01);
            }
        } catch (RuntimeException ignored) {
            // 世界/区块已不可用：静默跳过特效（不影响已完成的合成）
        }
        return 1;
    }

    /**
     * 背包能否<b>完整</b>收下这一叠物品（bug hunt P0-1 的判定内核）。
     *
     * <p>为什么不用现成的 {@code Inventory#hasSpaceFor}：1.21.11 的 paper-api 里
     * {@code Inventory} 与 {@code PlayerInventory} <b>都没有</b>该方法（javap 核实，
     * 只有 {@code addItem} / {@code firstEmpty}），所以这里自己算，口径与
     * {@code addItem} 的落位规则一致：<b>能并进同类未满堆的算容量，空位按最大堆叠算</b>。</p>
     *
     * <p>纯函数、只读，不修改背包；{@code null}/AIR 槽位一律算空位。</p>
     *
     * @param inventory 目标背包（玩家主背包，调用方已确认非空）
     * @param stack     要放入的物品（数量一般 1，但按通用实现写）
     * @return 容量是否足够完整收下
     */
    static boolean hasRoomFor(Inventory inventory, ItemStack stack) {
        if (inventory == null || stack == null || stack.getType() == Material.AIR) {
            return true;
        }
        int maxStack = Math.max(1, stack.getMaxStackSize());
        int remaining = stack.getAmount();
        int freeSlots = 0;
        for (ItemStack slot : inventory.getStorageContents()) {
            if (slot == null || slot.getType() == Material.AIR) {
                freeSlots++; // 空位稍后按最大堆叠计入
                continue;
            }
            if (slot.isSimilar(stack)) {
                int room = maxStack - slot.getAmount();
                if (room > 0) {
                    int take = Math.min(room, remaining);
                    remaining -= take;
                    if (remaining <= 0) {
                        return true;
                    }
                }
            }
        }
        // 剩下的只能占空位：每空位最多 maxStack 个
        return remaining <= freeSlots * maxStack;
    }

    // ------------------------------------------------------------ 容器使用约束

    /**
     * 防止把核心方块当仓库：中心格只收「认得的作物」，外围 8 格一律退回。
     *
     * <p><b>为什么不能只看 {@code getCursor()}</b>：放物品进格子有多种途径，
     * 光标的 {@code getCursor()} 只在普通左/右键（PICKUP_*）时才有意义：</p>
     * <ul>
     *   <li>{@link ClickType#SWAP_OFFHAND}（F 键）——物品来自<b>副手</b>，光标为空；</li>
     *   <li>{@link ClickType#NUMBER_KEY}（数字键）——物品来自<b>快捷栏</b>，光标为空；</li>
     *   <li>{@link ClickType#SHIFT_LEFT/RIGHT}——物品来自玩家背包，光标为空。</li>
     * </ul>
     * <p>旧实现只校验光标，这三种途径全部漏检：玩家能把整组钻石按 F 键塞进中心格，
     * 之后结构成立时 {@code craft()} 发现不是作物 → 只是提示失败，物品留在容器里，
     * 相当于把发射器变成了 9 格免费仓库（正是本类注释声称要防的事）。
     * 现在按 {@link ClickType} 分流取「真正要进去的那个物品」来校验。</p>
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        Block core = coreOf(event.getInventory());
        if (core == null || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        PluginConfig cfg = plugin.config();
        if (cfg == null || !cfg.antiDust().enabled()) {
            return; // 开关关闭：完全退回原版行为
        }
        // bug hunt P1-1：本方法只处理「写入方向」的拦截（下方每个取消点都对应一次写入）。
        // 结构不成立（或读不出）时它不是制药台，立刻退回原版行为——否则全服发射器
        // 都会被禁止放东西。取出方向本来就在后面按 incoming == null 放行。
        if (!restrictable(core)) {
            return;
        }
        int raw = event.getRawSlot();

        // —— 情形 A：点击发生在「玩家背包侧」。
        //    这里的威胁是 SHIFT 转移（会把背包物品自动送进容器）与
        //    「热键交换到容器格」。两者都必须按「要转移的是什么」校验。
        if (raw >= SIZE) {
            if (!event.isShiftClick() && event.getClick() != ClickType.NUMBER_KEY) {
                return; // 普通点击背包：原版照常
            }
            ItemStack candidate = event.isShiftClick()
                    ? event.getCurrentItem()
                    : hotbarItem(event, player);
            if (candidate == null || candidate.getType() == Material.AIR) {
                return;
            }
            // SHIFT 会找「第一个可放的位置」；对 9 格容器来说可能落到外围，也可能落到中心。
            // 本玩法里只有中心格有合法内容，于是统一按「必须是作物」把关，
            // 是作物就允许（原版会自己挪到合适的格子），否则整体取消。
            if (cfg.antiDust().levelFor(candidate.getType().name()) == null) {
                event.setCancelled(true);
                player.sendActionBar(note("§c" + cn(candidate.getType())
                        + " 不能放进制药台§8 · §7这里只收农作物"));
                playFail(player);
            }
            return;
        }
        if (raw < 0) {
            return; // 容器外（如点击界面空白）：不干预
        }

        // —— 情形 B：点击发生在「容器侧」。
        if (raw != DustCraftStructure.CENTER_SLOT) {
            // 外围 8 格（含 GUI 里的地板/提示位）→ 只允许取出，不允许放入
            ItemStack incoming = incomingItem(event, player);
            if (incoming != null && incoming.getType() != Material.AIR) {
                event.setCancelled(true);
                player.sendActionBar(note("§7外围八格只是摆放示意§8 · §7作物请放 §f正中间"));
                playFail(player);
            }
            return;
        }
        // 中心格：只接受合法作物（同样要覆盖光标 / 数字键 / 副手三种来源）
        ItemStack incoming = incomingItem(event, player);
        if (incoming == null || incoming.getType() == Material.AIR) {
            return; // 取出方向：放行
        }
        if (cfg.antiDust().levelFor(incoming.getType().name()) == null) {
            event.setCancelled(true);
            player.sendActionBar(note("§c" + cn(incoming.getType())
                    + " 不能用来制作抗尘埃药§8 · §7中心格只收农作物"));
            playFail(player);
        }
    }

    /**
     * 点击后「将要进入该格」的物品（可能是光标、快捷栏、或副手）。
     *
     * <p>只用于「校验是否允许放进去」，不用于判断取出方向。</p>
     */
    private static ItemStack incomingItem(InventoryClickEvent event, Player player) {
        ClickType click = event.getClick();
        if (click == ClickType.NUMBER_KEY) {
            return hotbarItem(event, player);
        }
        if (click == ClickType.SWAP_OFFHAND) {
            return player.getInventory().getItemInOffHand();
        }
        return event.getCursor();
    }

    /** 数字键（NUMBER_KEY）点击时对应的快捷栏物品。 */
    private static ItemStack hotbarItem(InventoryClickEvent event, Player player) {
        int hotbar = event.getHotbarButton();
        if (hotbar < 0 || hotbar >= 9) {
            return null;
        }
        return player.getInventory().getItem(hotbar);
    }

    /** 拖拽进中心格同样要校验（否则可以绕过点击限制一次拖一堆进去）。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        Block core = coreOf(event.getInventory());
        if (core == null || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        PluginConfig cfg = plugin.config();
        if (cfg == null || !cfg.antiDust().enabled()) {
            return;
        }
        if (!restrictable(core)) {
            return; // bug hunt P1-1：拖拽必然是写入方向，非制药台直接退回原版行为
        }
        boolean touchesCore = event.getRawSlots().stream().anyMatch(slot -> slot >= 0 && slot < SIZE);
        if (!touchesCore) {
            return;
        }
        ItemStack dragged = event.getOldCursor();
        if (dragged == null || dragged.getType() == Material.AIR) {
            return; // 拖拽必然是放入方向；没有光标物品就不该有拖拽，防御性放行
        }
        boolean centerOnly = event.getRawSlots().stream()
                .allMatch(slot -> slot == DustCraftStructure.CENTER_SLOT);
        if (centerOnly && cfg.antiDust().levelFor(dragged.getType().name()) != null) {
            return; // 合法作物拖进中心格：放行
        }
        event.setCancelled(true);
        // 分清提示语：拖到外围 vs 拖的不是作物
        if (!centerOnly) {
            player.sendActionBar(note("§7一次只能把作物放进 §f正中间那一格"));
        } else {
            player.sendActionBar(note("§c" + cn(dragged.getType())
                    + " 不能用来制作抗尘埃药§8 · §7中心格只收农作物"));
        }
        playFail(player);
    }

    /**
     * 阻止漏斗/投掷器把物品塞进核心方块（否则「只收作物」的限制可被自动化绕过，
     * 且会让「结构 = 加工台」的语义变得模糊）。
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        // 「取走」方向同样要拦：否则漏斗能把中心格作物/外围展示物抽走，
        // 玩家会以为药水制作台「吃掉了东西」。只有玩家手动操作才允许取。
        Block dest = coreOf(event.getDestination());
        Block source = coreOf(event.getSource());
        if (dest == null && source == null) {
            return;
        }
        PluginConfig cfg = plugin.config();
        if (cfg == null || !cfg.antiDust().enabled()) {
            return;
        }
        // bug hunt P1-1：两端都必须**各自**是成立的制药台才拦。
        // 例如「漏斗 → 普通发射器」：dest 只是一个发射器，结构不成立，
        // 不能因为它是 dispenser 类型就把玩家的漏斗物流掐断。
        if (!restrictable(dest) && !restrictable(source)) {
            return;
        }
        event.setCancelled(true);
    }

    /** 玩家退出：清掉防重复记录与冷却，避免 UUID 表无界增长。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        craftGuard.remove(event.getPlayer().getUniqueId());
    }

    // ---------------------------------------------------------------- 小工具

    /**
     * 该 Inventory 是否属于「抗尘埃药核心方块」。
     *
     * <p>只认 {@link Dispenser}/{@link Dropper} 的 BlockState 持有的 inventory——
     * 这样玩家自己背包里的同名 9 格界面不会被误判。</p>
     *
     * <p><b>刻意<b>不</b>在此校验结构完整性</b>（bug hunt P1-1 的修复边界）：本方法同时服务于
     * 三类调用，它们的结构要求并不相同——</p>
     * <ul>
     *   <li>{@code craft()}：结构不完整也必须能放作物（{@link #openFor} 的注释明确承诺
     *       「方便先把作物备好」），所以它只用「是不是核心容器」这一层；</li>
     *   <li><b>写入约束</b>（禁止往里放非作物）：这才是真正需要结构成立的地方。
     *       若对普通发射器也禁止，等于把全服每一台发射器都变成「只收农作物」的加工台，
     *       玩家再也没法用它做红石元件或普通容器——所以写入方向另用
     *       {@link #restrictable} 再筛一次。</li>
     * </ul>
     */
    private Block coreOf(Inventory inventory) {
        if (inventory == null || inventory.getType() != InventoryType.DISPENSER) {
            return null;
        }
        InventoryHolder holder = inventory.getHolder();
        if (!(holder instanceof Dispenser) && !(holder instanceof Dropper)) {
            return null;
        }
        return holder instanceof org.bukkit.block.BlockState state ? state.getBlock() : null;
    }

    /**
     * 是否应当对该容器的<b>写入</b>施加制药台约束（bug hunt P1-1）。
     *
     * <p>两种情形都放行（= 不应约束）：</p>
     * <ol>
     *   <li><b>结构未成立</b>：没有铁砧/原木环 → 它只是一台普通发射器，立刻退回原版行为；</li>
     *   <li><b>结构暂时不可读</b>（区块卸载、世界不可用 → {@code inspect} 内部兜底返回不成立）：
     *       同样放行。此处宁可放过一次非法写入，也不让一台正常的发射器在区块边界上
     *       忽然「卡住不让放东西」——后者对服主的表现是「插件坏了一半」。</li>
     * </ol>
     */
    private static boolean restrictable(Block core) {
        if (core == null) {
            return false;
        }
        try {
            return DustCraftStructure.inspect(core).ok();
        } catch (RuntimeException ex) {
            return false; // 结构读不出来就不约束，绝不因此抛穿事件回调
        }
    }

    /** 打开核心方块的原版 UI（结构不完整也允许，方便预放作物）。 */
    public void openFor(Player player, Block core) {
        Inventory inv = DustCraftStructure.inventoryOf(core);
        if (inv != null) {
            player.openInventory(inv);
        }
    }

    /** 把作物退回玩家（背包满则掉落），避免「物品凭空消失」。 */
    public static void giveBack(Player player, ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return;
        }
        var overflow = player.getInventory().addItem(stack);
        for (ItemStack rest : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    private static Component note(String text) {
        return deserialize(text);
    }

    private static Component deserialize(String legacy) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection().deserialize(legacy);
    }

    private static void playFail(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.4f, 0.7f);
    }

    private static String cn(Material material) {
        return material.name();
    }

    private static String trim(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** 供命令层复用的结构说明（/rad dust 输出）。 */
    public static List<String> structureHelp() {
        return List.of(
                "在平地上放一台 发射器/投掷器",
                "在它正上方那格压一个 铁砧",
                "在它同层的 8 个邻居位置各放一个 原木（可混树种）",
                "右键发射器，把农作物放进界面 正中间 那一格",
                "手持 木棍 右键铁砧 → 产出对应档位的抗尘埃药");
    }
}
