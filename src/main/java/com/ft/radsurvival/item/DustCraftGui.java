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
    private final Map<UUID, Long> craftGuard = new ConcurrentHashMap<>();

    /** 同一玩家的两次合成视为「同一次右键」的最大时间差（毫秒）。 */
    private static final long CRAFT_DEBOUNCE_MS = 200L;

    /**
     * 判断本次交互是否应当跳过（同一 tick 内的重复派发）。
     *
     * @return true = 这是重复事件，应直接忽略
     */
    private boolean isDuplicate(Player player) {
        long now = System.currentTimeMillis();
        Long last = craftGuard.get(player.getUniqueId());
        if (last != null && now - last < CRAFT_DEBOUNCE_MS) {
            return true;
        }
        craftGuard.put(player.getUniqueId(), now);
        return false;
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
        // 真正会产出时才接管本次交互，避免原版再走一遍「使用物品」逻辑
        event.setCancelled(true);
        // 双手都持木棍时主/副手会各派发一次（已限主手，这里仍是双保险）
        if (isDuplicate(player)) {
            return;
        }
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

        // —— 先尝试把药水放进背包，**成功了才扣作物**。
        //
        //  旧实现是「先扣作物 → 再 addItem → 失败就把整组 center 写回去」，
        //  这条路径有两个真实缺陷：
        //   ① 写回的是**原始整组** stack，等于作物没扣，同时又掉了一瓶药水
        //      → 背包满时可以无限刷药（作物永远不减）；
        //   ② addItem 可能**部分成功**（例如药水最大堆叠 8，背包只有 3 个空位时会
        //      塞进 3 个、把剩下的返回），旧代码仍把整瓶 potion 再 drop 一次 → 复制。
        //  改成「先给后扣」后，失败路径压根不碰中心格，天然没有回滚问题。
        ItemStack potion = items.create(level, 1);
        var overflow = player.getInventory().addItem(potion);
        if (!overflow.isEmpty()) {
            // 背包满：**不扣作物**，只把真正溢出的部分丢在脚下（不是整瓶）
            for (ItemStack rest : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
            player.sendActionBar(note("§e背包已满§8 · §7药水掉在脚下（作物未消耗）"));
            playFail(player);
            return 0;
        }

        // —— 药水已到手，这才扣除 1 个作物
        int remaining = center.getAmount() - 1;
        if (remaining > 0) {
            ItemStack leftover = center.clone();
            leftover.setAmount(remaining);
            inv.setItem(DustCraftStructure.CENTER_SLOT, leftover);
        } else {
            inv.setItem(DustCraftStructure.CENTER_SLOT, null);
        }

        player.sendActionBar(note("§a已制成 §f" + level.displayName()
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
        if (coreOf(event.getDestination()) == null && coreOf(event.getSource()) == null) {
            return;
        }
        PluginConfig cfg = plugin.config();
        if (cfg == null || !cfg.antiDust().enabled()) {
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
