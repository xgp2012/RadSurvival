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
 *       键 =「世界名 + 眼睛所在 chunk 段（16^3）」，值为<b>顶遮挡高度</b>：同一区间扫描
 *       结果可对段内任意眼睛高度精确再判定（{@code 露天 ⇔ eyeY > topCover}），消除旧
 *       布尔共享误差（段内低眼位复用高眼位结论会把头顶真实遮挡漏判为露天，反之虚判遮蔽）。
 *       段过期后若启用 {@code async-exposure-scan}（默认 true），补扫派发给
 *       {@link ScanDispatcher}（生产 = Bukkit 异步池，段级在途去重），当轮以粘滞层旧值
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

    /** 缓存键：世界名 + 眼睛所在段的三维坐标（floorDiv，负坐标正确归段）。 */
    public record ColumnKey(String world, int sectionX, int sectionY, int sectionZ) {

        public static ColumnKey of(String world, int blockX, int blockY, int blockZ) {
            return new ColumnKey(world,
                    Math.floorDiv(blockX, SECTION_SIZE),
                    Math.floorDiv(blockY, SECTION_SIZE),
                    Math.floorDiv(blockZ, SECTION_SIZE));
        }
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
     * <p>段粒度只影响扫描起点（多扫不超过 15 格），段内任意 eyeY 由顶遮挡高度精确派生；
     * 豁免维度（下界/末地）恒短路 false 不扫描（§1.1）。</p>
     */
    public boolean isExposed(World.Environment environment, String worldName,
                             int eyeX, int eyeY, int eyeZ, int maxHeight,
                             IntPredicate blockCovers) {
        if (!appliesTo(environment)) {
            return false; // 豁免维度（下界/末地）：无太阳，恒判“非露天”（§1.1）
        }
        ColumnKey key = ColumnKey.of(worldName, eyeX, eyeY, eyeZ);
        Integer freshTop = freshCache.getIfPresent(key);
        if (freshTop != null) {
            return eyeY > freshTop; // 权威命中：段内任意眼位精确判定
        }
        Integer stickyTop = lastKnownCache.getIfPresent(key);
        if (stickyTop != null && asyncDispatcher != null) {
            scheduleAsyncRefresh(key, maxHeight, blockCovers);
            return eyeY > stickyTop; // 补扫在途：沿用上一已知判定（机制不变，新鲜度滞后一轮级）
        }
        int top = topmostCoverY(maxHeight, key.sectionY() * SECTION_SIZE, blockCovers);
        publish(key, top);
        return eyeY > top;
    }

    /** 派发段级异步补扫（在途去重；调度拒绝/扫描异常一律静默，下轮自然重试）。 */
    private void scheduleAsyncRefresh(ColumnKey key, int maxHeight, IntPredicate blockCovers) {
        if (!inFlight.add(key)) {
            return; // 同段已有在途补扫，共享结果
        }
        int sectionBaseY = key.sectionY() * SECTION_SIZE;
        try {
            asyncDispatcher.dispatch(() -> {
                try {
                    int top = topmostCoverY(maxHeight, sectionBaseY, blockCovers);
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
