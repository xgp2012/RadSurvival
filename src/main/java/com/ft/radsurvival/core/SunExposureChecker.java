package com.ft.radsurvival.core;

import java.time.Duration;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import com.ft.radsurvival.config.PluginConfig;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

/**
 * 露天判定（plan.md §1.1）：从玩家眼睛所在方块逐格向上扫描至 world.getMaxHeight()，
 * 遇不透明方块即判遮蔽（= 天空不可见则安全）。
 *
 * <p>拆分为「纯逻辑内核 + 薄 Bukkit 适配层」：</p>
 * <ul>
 *   <li>纯内核：扫描算法抽象为对「高度 → 是否阻挡」谓词（{@link IntPredicate}）的
 *       <b>自上而下</b>遍历（{@link #topmostCoverY}，首个阻挡即顶遮挡，提前终止）；
 *       单查询语义 {@link #isSkyOpen} 与其等价；方块归类决策 {@link #blockCountsAsCover}
 *       ——均无 Bukkit 服务器依赖；</li>
 *   <li>缓存层（§2 线程模型的异步化落地）：双层 Caffeine——权威层 TTL 60s、容量上限，
 *       键 =「世界名 + 方块列（x/z 的 16 格段）」，值为该列的<b>顶遮挡高度</b>，对列内
 *       任意眼睛高度精确再判定（{@code 露天 ⇔ eyeY > topCover}）。
 *       <b>v1.3 修正</b>：键不再包含 y 段——顶遮挡是整列属性，与眼高无关。旧键含
 *       {@code sectionY} 会让玩家上下移动跨过 16 格段界时整列重扫，且同一列被不同 y 段
 *       各算一次、结果互相覆盖，造成头顶明明没有方块却在两段之间反复切换判定。
 *       段过期后若启用 {@code async-exposure-scan}（默认 true），补扫派发给
 *       {@link ScanDispatcher}（生产 = Bukkit 异步池，列级在途去重），当轮以粘滞层旧值
 *       应答——主线程稳态零方块读取；冷启动仍主线程直算保首轮精确。TTL 时钟经
 *       {@link LongSupplier} 注入，单测可模拟过期与调度；</li>
 *   <li>Bukkit 适配层：{@link #isExposed(World, Location)} 用 {@code BlockData#isOccluding()}
 *       （挡光＝§1.1 的“不透明”等价语义，1.21.11 的 Block 接口无 isOpaque，已 javap 核实）与
 *       {@code Block#isPassable()} 构造谓词。</li>
 * </ul>
 *
 * <p>维度口径（§1.1）：判定入口接收 {@link World.Environment}，仅 dimensions 配置内的维度参与判定；
 * 豁免维度（默认下界/末地）直接返回 false（视为遮蔽安全 → M2b 主循环走“只降不升”恢复分支）。</p>
 *
 * <p>透明方块口径（§6 风险对策）：玻璃等“非不透明但不可通行”的方块默认算遮蔽=安全；
 * 配置 {@code transparent-blocks-count-as-cover=false} 时只有不透明方块算遮蔽（玻璃顶=露天）。</p>
 */
public final class SunExposureChecker {

    /** §1.1：结果缓存 TTL 60s。 */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(60);
    /** 缓存条目上限（防御性封顶，防无界增长；§6 开销对策之一）。 */
    public static final long DEFAULT_MAX_ENTRIES = 8192L;
    /** chunk 段边长：16×16×16（§1.1 按 chunk 段缓存）。 */
    public static final int SECTION_SIZE = 16;
    /**
     * 方块列扫描的起点下界（v1.3）：低于 1.21.11 世界里任何可放置/可站立方块的下限。
     * 取 {@code MIN_BUILD_HEIGHT(0) - 1}：1.21 世界 y 从 -64 起，0 已在其上；更低的方块只可能是
     * 深板岩/基岩，它们若在玩家头顶之上，扫描自然会在更高的位置先遇到真实遮挡。
     * 为负 Y 世界留出的余量不必要也不危险——真正需要的是「不高于眼位」，0 满足。
     */
    private static final int MIN_BUILD_HEIGHT = 0;

    /**
     * 缓存键：世界名 + 方块列（x/z 的 16 格段）。
     *
     * <p><b>键里刻意不含 y 段（v1.3 修正）</b>：露天判定的扫描结果是<b>整列</b>的顶遮挡高度，
     * 与眼睛所在高度无关——{@code eyeY > topCover} 对列内任意眼位都精确成立。
     * 旧实现把 {@code sectionY} 也编进键，造成两个实际后果：</p>
     * <ol>
     *   <li><b>重复扫描</b>：玩家上下移动跨过 16 格段界（跳起、阶梯、下蹲）即换键 → 重新
     *       自上而下扫一遍。默认眼高 1.62 = 第 4 段、眼高 1.27 = 第 3 段，蹲下站起就会在
     *       两个键之间反复整列重扫；在地面附近上下移动则几乎每轮都在扫；
     *   <li><b>段间结果互相覆盖</b>：同一列被不同 y 段各扫一次，各自写入「从该段底起算的顶遮挡」。
     *       扫描起点随段底抬高而变（{@code startYInclusive = 16k + 1}），若列内该区间<b>没有</b>
     *       遮挡，各段算出的哨兵值互不相同、且都大于真实顶遮挡 → 在同一列上反复切换判定，
     *       表现为「贴着建筑边缘/进出门时判定时有时无」。
     * </ol>
     *
     * <p>去 y 段后，一列只扫一次、TTL 内零扫描，且同列所有眼位共享<b>同一个</b>顶遮挡高度，
     * 判定不再抖动。缓存规模由「每 16³ 段一条」降到「每 16×16 列一条」。</p>
     */
    public record ColumnKey(String world, int sectionX, int sectionZ) {

        public static ColumnKey of(String world, int blockX, int blockZ) {
            return new ColumnKey(world,
                    Math.floorDiv(blockX, SECTION_SIZE),
                    Math.floorDiv(blockZ, SECTION_SIZE));
        }

        /**
         * 兼容旧调用（x/y/z 三元组）：<b>y 被忽略</b>——露天判定按方块列缓存，与眼高无关。
         *
         * @deprecated 用 {@link #of(String, int, int)}；保留仅为不打断既有调用点。
         */
        @Deprecated
        public static ColumnKey of(String world, int blockX, int blockY, int blockZ) {
            return of(world, blockX, blockZ);
        }
    }

    /**
     * 列级扫描起点。<b>必须从世界底部（或至少低于任何可站立高度）起算</b>，不能复用眼高
     * 所在的 16 格段底：段底之上的判据 {@code eyeY > topCover} 只有在 topCover 是<b>整列</b>
     * 顶遮挡时才成立。用段底会漏掉「段底以下、眼位以上」其实并不存在的方块——无遮挡列因此
     * 得到一个偏高的哨兵值，段内低眼位会被误判为遮蔽（漏判危险，比误判安全更糟）。
     */
    static int columnScanStartY() {
        return MIN_BUILD_HEIGHT - 1;
    }

    // ---------------------------------------------------------------- 纯内核

    /**
     * 纯内核扫描（自上而下）：从 {@code topYExclusive−1} 逐格向下查到 {@code startYInclusive}（含），
     * 返回首个阻挡方块的高度 = 区间内<b>顶遮挡高度</b>；全程无阻挡返回
     * {@code startYInclusive−1}（哨兵值，恒低于区间内任何眼睛高度 → 判露天）。
     *
     * <p>口径（§1.1「眼睛所在方块起，含该格」）：顶遮挡 ≥ eyeY 即遮蔽（含 eyeY 本格），
     * 严格低于 eyeY 的方块不参与判定。</p>
     *
     * <p>与自下而上扫描的取舍：单点查询语义相同，但「顶遮挡高度」可以作为段级缓存值——
     * 自下而上遇首个阻挡即停在布尔结果，段内其它眼位复用该布尔会产生误判（旧实现的共享误差）；
     * 自上而下的首个阻挡恰是对判定最重要的顶遮挡，段内一次扫描即可对任意 eyeY 精确回答，
     * 且遮蔽场景仍是提前终止（§6 开销对策保留）。</p>
     */
    public static int topmostCoverY(int topYExclusive, int startYInclusive, IntPredicate blockCovers) {
        for (int y = topYExclusive - 1; y >= startYInclusive; y--) {
            if (blockCovers.test(y)) {
                return y;
            }
        }
        return startYInclusive - 1;
    }

    /**
     * 纯内核单点查询（等价形态）：从 startY（含）到 topYExclusive 全程无阻挡 = 露天。
     * 保留此入口供直接判定与测试；缓存路径用 {@link #topmostCoverY}（结果可按 eyeY 细分）。
     */
    public static boolean isSkyOpen(int startY, int topYExclusive, IntPredicate blockCovers) {
        return topmostCoverY(topYExclusive, startY, blockCovers) < startY;
    }

    /**
     * 纯内核方块归类：该方块是否算“遮蔽”。
     * occluding（石头/泥土等挡光“不透明”方块，§1.1 遇之即判遮蔽）恒算；
     * “不挡光但不可通行”（玻璃、树叶等透明屏障方块）仅在
     * {@code transparentCountAsCover=true}（默认）时算（§6：玻璃顶默认=室内安全）；
     * 空气/水等可通行方块恒不算。
     *
     * <p>适配层以 {@code block.getBlockData().isOccluding()} 与 {@code !block.isPassable()}
     * 分别作为前两参数——只用稳定 Bukkit API，规避透明方块判定 API 的版本差异。</p>
     */
    public static boolean blockCountsAsCover(boolean occluding, boolean nonPassableBarrier,
                                             boolean transparentCountAsCover) {
        if (occluding) {
            return true;
        }
        return nonPassableBarrier && transparentCountAsCover;
    }

    // ---------------------------------------------------------------- 实例/缓存

    /**
     * 异步补扫调度器（§2 线程模型落地缝）：生产装配传
     * {@code BukkitScheduler#runTaskAsynchronously}；单测注入直连/队列执行器。
     * null = 全同步旧口径（{@code async-exposure-scan: false}）。
     */
    @FunctionalInterface
    public interface ScanDispatcher {
        void dispatch(Runnable scanTask);
    }

    private final Set<World.Environment> dimensions;
    private final boolean transparentCountAsCover;
    /** 权威段缓存：顶遮挡高度，TTL 60s（§1.1）。 */
    private final Cache<ColumnKey, Integer> freshCache;
    /** 粘滞层：无过期、容量封顶——仅作异步补扫在途时的当轮观测值来源，
     *  不参与 TTL 语义（杜绝「过期瞬间退化为主线程扫」或误走恢复分支）。 */
    private final Cache<ColumnKey, Integer> lastKnownCache;
    /** 段级在途补扫去重（同段多玩家/查询共享一次扫描）。 */
    private final java.util.concurrent.ConcurrentHashMap.KeySetView<ColumnKey, Boolean> inFlight =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** null = 同步模式（旧口径）。 */
    private final ScanDispatcher asyncDispatcher;

    /** 生产装配：dimensions 白名单 + 透明方块口径，默认 60s TTL / 系统时钟 / 8192 条上限（同步模式）。 */
    public SunExposureChecker(Set<World.Environment> dimensions, boolean transparentCountAsCover) {
        this(dimensions, transparentCountAsCover, null, DEFAULT_TTL, DEFAULT_MAX_ENTRIES);
    }

    /** 从类型安全配置装配（dimensions / transparent-blocks-count-as-cover，§3；同步口径，测试兼容）。 */
    public SunExposureChecker(PluginConfig config) {
        this(config.dimensions(), config.transparentBlocksCountAsCover(), null, DEFAULT_TTL, DEFAULT_MAX_ENTRIES);
    }

    /**
     * 生产异步装配（§3 {@code async-exposure-scan: true}）：TTL 过期后的补扫经
     * {@code dispatcher}（Bukkit 异步池）执行，主线程当轮用粘滞值。
     */
    public SunExposureChecker(PluginConfig config, ScanDispatcher dispatcher) {
        this(config.dimensions(), config.transparentBlocksCountAsCover(), dispatcher,
                DEFAULT_TTL, DEFAULT_MAX_ENTRIES);
    }

    /**
     * 可注入时钟/调度器的构造：{@code nanoClock} 提供纳秒级当前时刻（适配 Caffeine
     * {@link Ticker}），单测用可变假时钟模拟 TTL 过期；传 null 用系统时钟。
     * ttl / maxEntries / dispatcher 均开放注入便于测试。
     */
    public SunExposureChecker(Set<World.Environment> dimensions, boolean transparentCountAsCover,
                              ScanDispatcher dispatcher, Duration ttl, long maxEntries) {
        this(dimensions, transparentCountAsCover, dispatcher, ttl, maxEntries, null);
    }

    /** 全参构造：另可注入假时钟（nanoClock，纳秒）。 */
    public SunExposureChecker(Set<World.Environment> dimensions, boolean transparentCountAsCover,
                              ScanDispatcher dispatcher, Duration ttl, long maxEntries,
                              LongSupplier nanoClock) {
        this.dimensions = Set.copyOf(dimensions);
        this.transparentCountAsCover = transparentCountAsCover;
        this.asyncDispatcher = dispatcher;
        // Caffeine.newBuilder() 返回 Caffeine<Object,Object>（不可直接收窄泛型），
        // 用 var 保持其类型，最终 build() 按赋值目标 Cache<ColumnKey,Integer> 推断。
        var builder = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxEntries);
        var sticky = Caffeine.newBuilder()
                .maximumSize(maxEntries); // 粘滞层不设过期，仅容量封顶
        if (nanoClock != null) {
            Ticker ticker = nanoClock::getAsLong;
            builder = builder.ticker(ticker);
            sticky = sticky.ticker(ticker);
        }
        this.freshCache = builder.build();
        this.lastKnownCache = sticky.build();
    }

    /** 异步补扫是否启用（装配/测试读口）。 */
    public boolean isAsyncRefreshEnabled() {
        return asyncDispatcher != null;
    }

    /** 该维度是否参与露天判定（dimensions 白名单，§1.1；默认仅 NORMAL）。 */
    public boolean appliesTo(World.Environment environment) {
        return dimensions.contains(environment);
    }

    /** 权威缓存条目数（监控/测试用；Caffeine 估算值）。 */
    public long cachedEntries() {
        freshCache.cleanUp();
        return freshCache.estimatedSize();
    }

    /** 在途补扫段数（排障/测试读口）。 */
    public int pendingRefreshes() {
        return inFlight.size();
    }

    /** 清空缓存（/rad reload、世界卸载或 onDisable 时调用，§2）。 */
    public void invalidateAll() {
        freshCache.invalidateAll();
        lastKnownCache.invalidateAll();
        inFlight.clear();
    }

    /**
     * 缓存 + 内核入口（除枚举参数外无 Bukkit 依赖，单测直接调用）。三层读取：
     * <ol>
     *   <li><b>权威命中</b>（TTL 60s 内）：直接对真实眼位精确判定（§1.1）；</li>
     *   <li><b>异步补扫在途</b>（{@code async-exposure-scan: true} 且段过期但有粘滞值）：
     *       当轮返回上一已知判定（≤1 结算周期新鲜度滞后），段级去重派发一次异步重扫，
     *       主线程零方块读取——本方法在 Bukkit 异步池线程被安全调用（只读已加载区块，
     *       异常不外抛）；</li>
     *   <li><b>冷启动 / 同步模式</b>：主线程直算——首轮判定精确度与旧口径完全一致，
     *       玩法零取舍。</li>
     * </ol>
     *
     * <p>段粒度只影响 x/z，扫描从 {@link #columnScanStartY()}（世界底部之下）起算整列，
     * 因此得到的是<b>整列</b>顶遮挡，对任意 eyeY 都精确；豁免维度（下界/末地）恒短路 false 不扫描（§1.1）。</p>
     */
    public boolean isExposed(World.Environment environment, String worldName,
                             int eyeX, int eyeY, int eyeZ, int maxHeight,
                             IntPredicate blockCovers) {
        if (!appliesTo(environment)) {
            return false; // 豁免维度（下界/末地）：无太阳，恒判“非露天”（§1.1）
        }
        ColumnKey key = ColumnKey.of(worldName, eyeX, eyeZ);
        Integer freshTop = freshCache.getIfPresent(key);
        if (freshTop != null) {
            return eyeY > freshTop; // 权威命中：列内任意眼位精确判定（与眼高无关）
        }
        Integer stickyTop = lastKnownCache.getIfPresent(key);
        if (stickyTop != null && asyncDispatcher != null) {
            scheduleAsyncRefresh(key, maxHeight, blockCovers);
            return eyeY > stickyTop; // 补扫在途：沿用上一已知判定（机制不变，新鲜度滞后一轮级）
        }
        int top = topmostCoverY(maxHeight, columnScanStartY(), blockCovers);
        publish(key, top);
        return eyeY > top;
    }

    /** 派发列级异步补扫（在途去重；调度拒绝/扫描异常一律静默，下轮自然重试）。 */
    private void scheduleAsyncRefresh(ColumnKey key, int maxHeight, IntPredicate blockCovers) {
        if (!inFlight.add(key)) {
            return; // 同列已有在途补扫，共享结果
        }
        try {
            asyncDispatcher.dispatch(() -> {
                try {
                    int top = topmostCoverY(maxHeight, columnScanStartY(), blockCovers);
                    publish(key, top);
                } catch (RuntimeException | Error ex) {
                    // 异步补扫异常（如区块恰被卸载触发 Paper 异步加载防护）：不发布，
                    // 主线程下轮继续用粘滞值并重试——绝不反噬结算主线
                } finally {
                    inFlight.remove(key);
                }
            });
        } catch (RuntimeException ex) {
            inFlight.remove(key); // 调度器关闭（插件禁用中）等：撤销在途标记
        }
    }

    /** 双写：权威层 + 粘滞层（异步线程调用，Caffeine 线程安全）。 */
    private void publish(ColumnKey key, int topCover) {
        freshCache.put(key, topCover);
        lastKnownCache.put(key, topCover);
    }

    /**
     * 薄 Bukkit 适配：以眼睛坐标 {@code eyeLocation} 所在方块为起点向上扫到
     * {@code world.getMaxHeight()}，方块是否阻挡由 {@link #blockCountsAsCover} 依据
     * {@code BlockData#isOccluding()/Block#isPassable()} 与配置口径决定（§1.1/§6）。
     */
    public boolean isExposed(World world, Location eyeLocation) {
        // —— 主线程守护（判定链路强化修复）：Bukkit 的方块/区块读取只允许在主线程，
        //    异步读取会触发 Paper 的「Asynchronous chunk access」致命异常。
        //    这里做最后一道防线：非主线程一律按「露天」保守应答（宁多掉血，不误判安全区），
        //    由下一轮主线程判定纠正。生产路径本就只在主线程调用（RadiationTask / 命令）。
        if (!org.bukkit.Bukkit.isPrimaryThread()) {
            return true;
        }
        int x = eyeLocation.getBlockX();
        int z = eyeLocation.getBlockZ();
        return isExposed(world.getEnvironment(), world.getName(),
                x, eyeLocation.getBlockY(), z, world.getMaxHeight(),
                y -> {
                    Block block = world.getBlockAt(x, y, z);
                    return blockCountsAsCover(block.getBlockData().isOccluding(),
                            !block.isPassable(), transparentCountAsCover);
                });
    }
}
