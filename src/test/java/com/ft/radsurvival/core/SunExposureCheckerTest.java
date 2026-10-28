package com.ft.radsurvival.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntPredicate;

import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ft.radsurvival.core.SunExposureChecker.ColumnKey;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SunExposureChecker 纯逻辑测试（plan.md §1.1/§6，M2a）：扫描内核（露天/室内/半柱遮挡/
 * maxHeight 边界/提前终止与扫描区间）、透明方块两种配置分支、维度豁免、
 * Caffeine 段键缓存命中与 TTL 过期（注入假时钟）。无 Bukkit 服务器依赖。
 */
class SunExposureCheckerTest {

    private static final String WORLD = "world";
    private static final int MAX_HEIGHT = 320; // 主世界 maxHeight（§1.1 扫描上界）

    // ---------------------------------------------------------------- 扫描内核

    @Test
    @DisplayName("内核扫描：全程无阻挡=露天；任一高度遇阻挡=遮蔽")
    void kernelOpenAndClosed() {
        assertThat(SunExposureChecker.isSkyOpen(64, MAX_HEIGHT, y -> false)).isTrue();
        assertThat(SunExposureChecker.isSkyOpen(64, MAX_HEIGHT, y -> y == 100)).isFalse(); // 单块遮挡
        assertThat(SunExposureChecker.isSkyOpen(64, MAX_HEIGHT, y -> true)).isFalse();     // 全遮挡
    }

    @Test
    @DisplayName("内核扫描：起点=眼睛高度（眼睛下缘的方块不参与判定）")
    void kernelStartsAtEyeBlock() {
        // 仅在眼睛下方 y=50 有阻挡：从 y=64 起扫 → 不受影响，仍露天
        IntPredicate blockerBelow = y -> y == 50;
        assertThat(SunExposureChecker.isSkyOpen(64, MAX_HEIGHT, blockerBelow)).isTrue();
        // 眼睛所在方块本身被挡（y=64）：字面口径「从眼睛所在方块起」→ 判遮蔽
        assertThat(SunExposureChecker.isSkyOpen(64, MAX_HEIGHT, y -> y == 64)).isFalse();
    }

    @Test
    @DisplayName("半柱遮挡：稀疏阻挡（每隔 3 格一块）仍判遮蔽；自上而下提前终止=顶遮挡之下不再查询")
    void kernelSparseBlockerAndEarlyExit() {
        AtomicInteger queries = new AtomicInteger();
        IntPredicate sparse = y -> {
            queries.incrementAndGet();
            return y % 3 == 0; // 320 以下首个（自顶向下）可被整除的是 318
        };
        assertThat(SunExposureChecker.isSkyOpen(64, MAX_HEIGHT, sparse)).isFalse();
        assertThat(queries.get()).isEqualTo(319 - 318 + 1); // 恰查到顶遮挡（318）即停，共 2 格
    }

    @Test
    @DisplayName("maxHeight 边界：startY ≥ top 直接露天（零查询）；top−1 单格决定结果")
    void kernelMaxHeightBoundary() {
        AtomicInteger queries = new AtomicInteger();
        // 眼睛已在扫描上界之上/齐平：区间为空 → 露天，且不查询任何高度
        assertThat(SunExposureChecker.isSkyOpen(MAX_HEIGHT, MAX_HEIGHT, y -> {
            queries.incrementAndGet();
            return true;
        })).isTrue();
        assertThat(SunExposureChecker.isSkyOpen(MAX_HEIGHT + 5, MAX_HEIGHT, y -> true)).isTrue();
        assertThat(queries.get()).isZero();
        // 最高一格（top−1）：阻挡 → 遮蔽；空 → 露天
        assertThat(SunExposureChecker.isSkyOpen(MAX_HEIGHT - 1, MAX_HEIGHT, y -> true)).isFalse();
        assertThat(SunExposureChecker.isSkyOpen(MAX_HEIGHT - 1, MAX_HEIGHT, y -> false)).isTrue();
    }

    // ---------------------------------------------------------------- 方块归类

    @Test
    @DisplayName("透明方块两种配置分支：occluding 恒遮蔽；玻璃(不挡光但不可通行)默认算遮蔽=false 时算露天；空气恒不遮蔽")
    void blockCoverageConfigBranches() {
        // 石头类：occluding=true → 无论配置都遮蔽
        assertThat(SunExposureChecker.blockCountsAsCover(true, true, true)).isTrue();
        assertThat(SunExposureChecker.blockCountsAsCover(true, true, false)).isTrue();
        // 玻璃类：不挡光 + 不可通行 → 配置 true 遮蔽（默认，安全）；false 不遮蔽（算露天）
        assertThat(SunExposureChecker.blockCountsAsCover(false, true, true)).isTrue();
        assertThat(SunExposureChecker.blockCountsAsCover(false, true, false)).isFalse();
        // 空气/水：可通行 → 恒不遮蔽
        assertThat(SunExposureChecker.blockCountsAsCover(false, false, true)).isFalse();
        assertThat(SunExposureChecker.blockCountsAsCover(false, false, false)).isFalse();
    }

    // ---------------------------------------------------------------- 维度豁免

    @Test
    @DisplayName("dimensions 豁免（默认仅 NORMAL）：下界/末地入口直接 false 且不触发扫描")
    void dimensionExemptionSkipsScan() {
        AtomicInteger scans = new AtomicInteger();
        SunExposureChecker checker = new SunExposureChecker(
                Set.of(World.Environment.NORMAL), true, null, Duration.ofSeconds(60), 128, () -> 0L);
        assertThat(checker.appliesTo(World.Environment.NORMAL)).isTrue();
        assertThat(checker.appliesTo(World.Environment.NETHER)).isFalse();
        assertThat(checker.appliesTo(World.Environment.THE_END)).isFalse();

        boolean netherExposed = checker.isExposed(World.Environment.NETHER, "world_nether",
                0, 64, 0, 128, y -> {
                    scans.incrementAndGet();
                    return false;
                });
        assertThat(netherExposed).isFalse();
        assertThat(scans.get()).isZero(); // 豁免维度不扫描

        // 配置放开 NETHER 后参与判定
        SunExposureChecker withNether = new SunExposureChecker(
                Set.of(World.Environment.NORMAL, World.Environment.NETHER), true,
                null, Duration.ofSeconds(60), 128, () -> 0L);
        assertThat(withNether.isExposed(World.Environment.NETHER, "world_nether",
                0, 64, 0, 128, y -> false)).isTrue();
    }

    // ---------------------------------------------------------------- 段键

    @Test
    @DisplayName("ColumnKey：按方块列（x/z 的 16 格段）归档，floorDiv 负坐标正确；y 不参与（v1.3 修正）")
    void columnKeySectioning() {
        assertThat(ColumnKey.of(WORLD, 0, 0)).isEqualTo(ColumnKey.of(WORLD, 15, 15)); // 同列共享
        assertThat(ColumnKey.of(WORLD, 16, 0)).isNotEqualTo(ColumnKey.of(WORLD, 0, 0)); // x 跨段
        assertThat(ColumnKey.of(WORLD, -1, 0)).isNotEqualTo(ColumnKey.of(WORLD, 0, 0)); // 负坐标独立列
        assertThat(ColumnKey.of(WORLD, -16, -16)).isEqualTo(ColumnKey.of(WORLD, -1, -1)); // 负坐标均归 (-1,-1)
        assertThat(ColumnKey.of("world_the_end", 0, 0)).isNotEqualTo(ColumnKey.of(WORLD, 0, 0));
        assertThat(ColumnKey.of(WORLD, 0, 0)).isNotEqualTo(ColumnKey.of(WORLD, 0, 16)); // z 跨段
        // v1.3 回归钉子：y 不参与键。旧实现把 sectionY 编进键，63/64 分属两段 → 两键，
        // 玩家蹲下/站起跨过段界就整列重扫，且两段各自算出不同的哨兵值导致判定来回切换。
        assertThat(ColumnKey.of(WORLD, 0, 63, 0)).isEqualTo(ColumnKey.of(WORLD, 0, 64, 0));
        assertThat(ColumnKey.of(WORLD, 0, 1, 0)).isEqualTo(ColumnKey.of(WORLD, 0, 300, 0));
    }

    // ---------------------------------------------------------------- 缓存行为

    /** 构造注入假时钟的检查器：nanoClock 与 ttl/容量可控（同步模式）。 */
    private static SunExposureChecker cached(AtomicLong clock, boolean transparentCover) {
        return new SunExposureChecker(Set.of(World.Environment.NORMAL), transparentCover,
                null, Duration.ofSeconds(60), 64, clock::get);
    }

    @Test
    @DisplayName("缓存命中：同段第二次查询复用结果（谓词不再被调用），即使段内精确坐标不同（§1.1 段粒度口径）")
    void cacheHitWithinTtl() {
        AtomicLong clock = new AtomicLong(0L);
        SunExposureChecker checker = cached(clock, true);
        AtomicInteger scans = new AtomicInteger();
        IntPredicate open = y -> {
            scans.incrementAndGet();
            return false;
        };
        assertThat(checker.isExposed(World.Environment.NORMAL, WORLD, 1, 65, 1, MAX_HEIGHT, open)).isTrue();
        int firstScanCount = scans.get();
        assertThat(firstScanCount).isPositive();
        // TTL 内（59s）同段再查：不扫描（顶遮挡哨兵派生结果，眼位不同也复用同一次扫描）
        clock.set(Duration.ofSeconds(59).toNanos());
        assertThat(checker.isExposed(World.Environment.NORMAL, WORLD, 10, 68, 2, MAX_HEIGHT, open)).isTrue();
        assertThat(scans.get()).isEqualTo(firstScanCount);
    }

    @Test
    @DisplayName("段内精确度（判定链路强化）：缓存存顶遮挡高度，同列不同 eyeY 各自正确，且仍只扫描一次")
    void sectionValueResolvesPerEyeHeight() {
        AtomicLong clock = new AtomicLong(0L);
        SunExposureChecker checker = cached(clock, true);
        AtomicInteger scans = new AtomicInteger();
        IntPredicate cover66 = y -> {
            scans.incrementAndGet();
            return y == 66; // 顶遮挡在 66：眼位 ≥67 露天，≤66 被它遮住
        };
        // 首查 eyeY=70：扫到顶遮挡 66，70>66 → 露天
        assertThat(checker.isExposed(World.Environment.NORMAL, WORLD, 0, 70, 0, MAX_HEIGHT, cover66)).isTrue();
        int first = scans.get();
        // 同段 eyeY=65（缓存命中不再扫描）：66 ≥ 65 → 遮蔽——旧布尔共享实现会错误沿用“露天”
        assertThat(checker.isExposed(World.Environment.NORMAL, WORLD, 1, 65, 0, MAX_HEIGHT, cover66)).isFalse();
        assertThat(scans.get()).isEqualTo(first);
        // 同段 eyeY=67：67 > 66 → 露天（同样零重扫）
        assertThat(checker.isExposed(World.Environment.NORMAL, WORLD, 2, 67, 0, MAX_HEIGHT, cover66)).isTrue();
        assertThat(scans.get()).isEqualTo(first);
    }

    @Test
    @DisplayName("TTL 过期：60s 假时钟推进到 61s 后重扫；结果随谓词变化而刷新")
    void cacheExpiresAfterTtl() {
        AtomicLong clock = new AtomicLong(0L);
        SunExposureChecker checker = cached(clock, true);
        AtomicInteger scans = new AtomicInteger();
        // 首轮：露天
        assertThat(checker.isExposed(World.Environment.NORMAL, WORLD, 0, 64, 0, MAX_HEIGHT, y -> {
            scans.incrementAndGet();
            return false;
        })).isTrue();
        assertThat(scans.get()).isEqualTo(MAX_HEIGHT - (0 - 1));
        // 60.001s 后：条目过期 → 用「遮挡」谓词重扫 → 遮蔽，且自上而下只查到首个（顶）阻挡 70 即停
        clock.set(Duration.ofSeconds(61).toNanos());
        assertThat(checker.isExposed(World.Environment.NORMAL, WORLD, 0, 64, 0, MAX_HEIGHT, y -> {
            scans.incrementAndGet();
            return y == 70;
        })).isFalse();
        assertThat(scans.get()).isEqualTo((MAX_HEIGHT - (0 - 1)) + (319 - 70 + 1));
    }

    @Test
    @DisplayName("缓存隔离：不同列/不同世界的键互不污染；invalidateAll 清空")
    void cacheIsolationAndInvalidate() {
        AtomicLong clock = new AtomicLong(0L);
        SunExposureChecker checker = cached(clock, true);
        AtomicInteger scans = new AtomicInteger();
        IntPredicate counting = y -> {
            scans.incrementAndGet();
            return false;
        };
        checker.isExposed(World.Environment.NORMAL, WORLD, 0, 64, 0, MAX_HEIGHT, counting);
        int perScan = scans.get();
        // x 跨列 → 新键重扫
        checker.isExposed(World.Environment.NORMAL, WORLD, 16, 64, 0, MAX_HEIGHT, counting);
        assertThat(scans.get()).isEqualTo(2 * perScan);
        // 另一世界同坐标 → 新键
        checker.isExposed(World.Environment.NORMAL, "world2", 0, 64, 0, MAX_HEIGHT, counting);
        assertThat(scans.get()).isEqualTo(3 * perScan);
        // v1.3 回归钉子：同列不同眼高（哪怕跨过 16 格段界）不产生新条目、不重扫
        checker.isExposed(World.Environment.NORMAL, WORLD, 3, 71, 3, MAX_HEIGHT, counting);
        assertThat(scans.get()).as("同列换眼高不重扫").isEqualTo(3 * perScan);
        assertThat(checker.cachedEntries()).isEqualTo(3);
        checker.invalidateAll();
        assertThat(checker.cachedEntries()).isZero();
    }

    @Test
    @DisplayName("容量上限：maximumSize 生效，条目数被钳制（§6 防无界增长）")
    void cacheRespectsMaximumSize() {
        AtomicLong clock = new AtomicLong(0L);
        SunExposureChecker checker = new SunExposureChecker(Set.of(World.Environment.NORMAL), true,
                null, Duration.ofSeconds(60), 2, clock::get);
        checker.isExposed(World.Environment.NORMAL, WORLD, 0, 64, 0, MAX_HEIGHT, y -> false);
        checker.isExposed(World.Environment.NORMAL, WORLD, 16, 64, 0, MAX_HEIGHT, y -> false);
        checker.isExposed(World.Environment.NORMAL, WORLD, 32, 64, 0, MAX_HEIGHT, y -> false);
        assertThat(checker.cachedEntries()).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("生产默认构造：60s TTL/8192 上限可装配，透明方块口径透传（不验证时钟，仅冒烟）")
    void productionConstructorSmoke() {
        SunExposureChecker checker = new SunExposureChecker(Set.of(World.Environment.NORMAL), false);
        assertThat(checker.isAsyncRefreshEnabled())
                .as("不带 dispatcher 的两参构造 = 同步旧口径").isFalse();
        AtomicInteger scans = new AtomicInteger();
        // transparentCover=false：玻璃谓词（不挡光但不可通行）不判遮蔽 → 露天
        boolean exposed = checker.isExposed(World.Environment.NORMAL, WORLD, 100, 100, 100, 200, y -> {
            scans.incrementAndGet();
            return SunExposureChecker.blockCountsAsCover(false, y == 150, false); // 仅 150 处一块玻璃
        });
        assertThat(exposed).isTrue();
        // 无遮挡列：整列从 columnScanStartY() 扫到 maxHeight（v1.3：不再按眼高所在段底起算，
        // 否则段底以下被跳过、哨兵值偏高，段内低眼位会被误判为遮蔽）
        assertThat(scans.get())
                .as("玻璃不算遮蔽（transparentCountAsCover=false）→ 整列扫到底，无提前终止")
                .isEqualTo(200 - (0 - 1));
    }

    // ---------------------------------------------------------------- v1.3 判定稳定性回归

    @Test
    @DisplayName("v1.3 回归：玩家在列内上下移动（跨 16 格段界）不再整列重扫，判定也不抖动")
    void verticalMovementDoesNotRescanOrFlip() {
        AtomicLong clock = new AtomicLong(0L);
        SunExposureChecker checker = cached(clock, true);
        AtomicInteger scans = new AtomicInteger();
        // 真实的「屋檐外站台」场景：整列无遮挡 → 任何眼高都必须判露天
        IntPredicate open = y -> {
            scans.incrementAndGet();
            return false;
        };
        // 蹲下(眼高 63)、站起(眼高 64)、跳起(眼高 80)、再落回(眼高 63)；63/64 恰好跨 y 段界(3/4)
        for (int eyeY : new int[] {63, 64, 80, 63, 64}) {
            assertThat(checker.isExposed(World.Environment.NORMAL, WORLD, 5, eyeY, 5, MAX_HEIGHT, open))
                    .as("列内无遮挡时眼高 %d 必须判露天", eyeY).isTrue();
        }
        // 旧实现：63→段3、64→段4 两个键各扫一次，且两次扫描起点不同（16*3+1=49 / 16*4+1=65）
        int columnScan = MAX_HEIGHT - (0 - 1);
        assertThat(scans.get())
                .as("v1.3：同一方块列只扫一次（旧实现跨 y 段会重扫，蹲起即可触发）")
                .isEqualTo(columnScan);
    }

    @Test
    @DisplayName("v1.3 回归：扫描必须是整列——段底以下/眼位以下的空白不能把低眼位误判为遮蔽")
    void columnScanCoversBelowEyeHeight() {
        AtomicLong clock = new AtomicLong(0L);
        SunExposureChecker checker = cached(clock, true);
        // 整列无遮挡。高眼位先查询会写入该列的顶遮挡哨兵值；
        // 若哨兵按「眼高所在段底」算，段内更低的眼位就会 getTopCover >= eyeY → 误判遮蔽（漏判危险）
        assertThat(checker.isExposed(World.Environment.NORMAL, WORLD, 0, 200, 0, MAX_HEIGHT, y -> false))
                .isTrue();
        // 同列低眼位（低 130 格，远超一个段）：仍必须露天
        assertThat(checker.isExposed(World.Environment.NORMAL, WORLD, 0, 70, 0, MAX_HEIGHT, y -> false))
                .as("低眼位不得因高眼位的扫描起点而误判遮蔽").isTrue();
        // 反过来：真实顶遮挡 100 时，低于它的眼位必须遮蔽、高于它必须露天（同列、零重扫）
        SunExposureChecker other = cached(clock, true);
        IntPredicate cover100 = y -> y == 100;
        assertThat(other.isExposed(World.Environment.NORMAL, WORLD, 0, 70, 0, MAX_HEIGHT, cover100)).isFalse();
        assertThat(other.isExposed(World.Environment.NORMAL, WORLD, 0, 150, 0, MAX_HEIGHT, cover100)).isTrue();
        assertThat(other.isExposed(World.Environment.NORMAL, WORLD, 0, 100, 0, MAX_HEIGHT, cover100))
                .as("眼位与遮挡同格 = 遮蔽（含眼睛所在格，§1.1）").isFalse();
    }

    @Test
    @DisplayName("v1.3 回归：缓存条目数按方块列计——同一列任意眼高只占 1 条，不再按 y 段重复占位")
    void cacheEntriesAreColumnScoped() {
        AtomicLong clock = new AtomicLong(0L);
        // 容量放大到 64 条，避免 maximumSize 淘汰干扰「到底占了几条」的观测
        SunExposureChecker checker = cached(clock, true);
        // 同一列、5 个跨 3 个 y 段的眼睛高度（段 3/4/5/18）：旧实现会占 4 条（16³ 段粒度），新实现 1 条
        for (int eyeY : new int[] {63, 64, 79, 80, 300}) {
            assertThat(checker.isExposed(World.Environment.NORMAL, WORLD, 0, eyeY, 0, MAX_HEIGHT, y -> false))
                    .isTrue();
        }
        assertThat(checker.cachedEntries())
                .as("v1.3：缓存以方块列为粒度，跨 y 段不新增条目（旧实现 = 每 16³ 段一条）")
                .isEqualTo(1L);
        // 相邻列（x 或 z 跨 16 格段）各自一条，互不合并
        assertThat(checker.isExposed(World.Environment.NORMAL, WORLD, 16, 64, 0, MAX_HEIGHT, y -> false)).isTrue();
        assertThat(checker.isExposed(World.Environment.NORMAL, WORLD, 0, 64, 16, MAX_HEIGHT, y -> false)).isTrue();
        assertThat(checker.cachedEntries())
                .as("跨段 x / 跨段 z 各是一条独立列")
                .isEqualTo(3L);
    }

    // ---------------------------------------------------------------- 异步补扫管线（§2 线程模型）

    @Test
    @DisplayName("异步管线：冷启动主线程直算零派发；TTL 过期当轮返粘滞旧值+段级去重派发一次；刷新后新值精确")
    void asyncRefillPipelineWithStickyValue() {
        AtomicLong clock = new AtomicLong(0L);
        List<Runnable> queue = new ArrayList<>();
        SunExposureChecker checker = new SunExposureChecker(
                Set.of(World.Environment.NORMAL), true, queue::add,
                Duration.ofSeconds(60), 64, clock::get);
        assertThat(checker.isAsyncRefreshEnabled()).isTrue();

        AtomicInteger scans = new AtomicInteger();
        IntPredicate open = y -> {
            scans.incrementAndGet();
            return false;
        };
        // 冷启动（无粘滞值）：主线程直算，首轮精确，且零异步派发
        assertThat(checker.isExposed(World.Environment.NORMAL, WORLD, 0, 64, 0, MAX_HEIGHT, open)).isTrue();
        int coldScans = scans.get();
        assertThat(coldScans).isPositive();
        assertThat(queue).as("冷启动不走异步").isEmpty();

        // 61s：权威层过期 + 环境变化（70 封顶）。前两次查询（同段）都返回粘滞旧值（露天），
        // 主线程不再扫描（谓词调用数不变），且段级去重只派发一个任务
        clock.set(Duration.ofSeconds(61).toNanos());
        IntPredicate nowCovered = y -> {
            scans.incrementAndGet();
            return y == 70;
        };
        assertThat(checker.isExposed(World.Environment.NORMAL, WORLD, 0, 64, 0, MAX_HEIGHT, nowCovered)).isTrue();
        assertThat(checker.isExposed(World.Environment.NORMAL, WORLD, 1, 64, 0, MAX_HEIGHT, nowCovered)).isTrue();
        assertThat(scans.get()).isEqualTo(coldScans); // 主线程零方块读取（扫描只发生在任务里）
        assertThat(queue).as("同段在途去重").hasSize(1);
        assertThat(checker.pendingRefreshes()).isEqualTo(1);

        // 执行异步任务（生产=Bukkit 异步池线程）→ 双写权威+粘滞层，在途清零
        queue.forEach(Runnable::run);
        queue.clear();
        assertThat(checker.pendingRefreshes()).isZero();

        // 刷新后可见新判定：眼 64 < 顶遮挡 70 → 遮蔽；且权威命中零再派发
        assertThat(checker.isExposed(World.Environment.NORMAL, WORLD, 0, 64, 0, MAX_HEIGHT, nowCovered)).isFalse();
        assertThat(queue).isEmpty();
        // 段内精确度在刷新后同样成立：眼 80 > 70 → 露天
        assertThat(checker.isExposed(World.Environment.NORMAL, WORLD, 0, 80, 0, MAX_HEIGHT, nowCovered)).isTrue();
    }

    @Test
    @DisplayName("异步补扫异常不发布不崩：任务抛错→在途释放，下轮仍可用粘滞值重试")
    void asyncScanFailureIsContained() {
        AtomicLong clock = new AtomicLong(0L);
        List<Runnable> queue = new ArrayList<>();
        SunExposureChecker checker = new SunExposureChecker(
                Set.of(World.Environment.NORMAL), true, queue::add,
                Duration.ofSeconds(60), 64, clock::get);
        // 冷启动建立粘滞值（露天）
        assertThat(checker.isExposed(World.Environment.NORMAL, WORLD, 0, 64, 0, MAX_HEIGHT, y -> false)).isTrue();
        clock.set(Duration.ofSeconds(61).toNanos());
        // 抛错的补扫（模拟区块恰被卸载触发 Paper 异步防护）
        assertThat(checker.isExposed(World.Environment.NORMAL, WORLD, 0, 64, 0, MAX_HEIGHT, y -> {
            throw new IllegalStateException("Asynchronous ChunkLoad");
        })).isTrue(); // 粘滞旧值应答
        assertThat(queue).hasSize(1);
        queue.get(0).run(); // 任务内部吞异常，不外抛
        assertThat(queue).hasSize(1);
        queue.clear();
        assertThat(checker.pendingRefreshes()).as("异常也要释放在途标记").isZero();
        // 下轮：仍无权威值 → 继续粘滞 + 重试派发（自愈）
        assertThat(checker.isExposed(World.Environment.NORMAL, WORLD, 0, 64, 0, MAX_HEIGHT, y -> false)).isTrue();
        assertThat(queue).hasSize(1);
    }
}
