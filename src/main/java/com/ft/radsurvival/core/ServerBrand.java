package com.ft.radsurvival.core;

import java.util.Locale;

/**
 * 服务端品牌识别与防御性判定（plan.md §7「服务端品牌自适应 Profile」，v1.4）。
 *
 * <p><b>本期范围</b>：只做<b>识别 + 防御</b>，不做按品牌调参的预设体系
 * （{@code profiles.<brand>} / 三层优先级 / 保守旋钮覆写均未实施）。原因见
 * plan.md §7.2：唯一随品牌变化的维度是主线程压力特征，而 M2 后本插件主线程稳态已近零
 * 开销（异步露天补扫），按品牌改 {@code tick-interval} / 缓存 TTL 属于拿实测数据换猜测，
 * 收益为负。</p>
 *
 * <p><b>识别优先级</b>（顺序即安全性排序，先判「跑不跑得起来」再判「是不是某分支」）：</p>
 * <ol>
 *   <li>{@link #FOLIA}：区域化线程模型，{@code BukkitScheduler} 的全局任务在其上直接抛
 *       {@code UnsupportedOperationException}，本插件的主循环与 HUD 任务全部依赖全局调度 →
 *       <b>无法运行</b>，必须禁用；</li>
 *   <li>{@link #SPIGOT}：本插件以 paper-api 编译（{@code DamageSource}/{@code Component} 等
 *       Paper 专属类），纯 Spigot 上 {@code onEnable} 会抛 {@code NoClassDefFoundError} →
 *       <b>无法运行</b>，必须引导换端；</li>
 *   <li>{@link #PAPER} / {@link #LEAF} / {@link #PURPUR}：Paper 系分支，API 同面，
 *       判定/事件/伤害管线<b>不分支化</b>（§7.2）。</li>
 * </ol>
 *
 * <p><b>为什么类探测优先于 {@code server.getName()}</b>：getName() 只是一个可被分支服务端
 * 随意改写的字符串（Leaf/Purpur 就都返回自己的名字），无法证明「Paper 专属 API 真的存在」；
 * 反之，类存在性是编译期事实。因此先由 {@link ClassProbe} 探测，探测阴性时才退回名字。</p>
 *
 * <p><b>纯 Java 约束</b>：本类不引用任何 Bukkit/Paper 类型，只接受 {@code String} 与
 * {@link ClassProbe}，因此单元测试不需要 MockBukkit、也不需要在 Paper 上跑
 * （plan.md §2「core 不依赖 Bukkit 服务做重逻辑」）。</p>
 */
public final class ServerBrand {

    /**
     * 类存在性探测器（生产传 {@code Class::forName}，测试传假实现）。
     *
     * <p>用 {@code Class.forName(name, false, loader)} 语义——只需「类可加载」，
     * 不初始化静态块（避免探测本身触发副作用）。</p>
     */
    @FunctionalInterface
    public interface ClassProbe {
        /** @return 该全限定类名是否存在于当前运行时 */
        boolean exists(String className);
    }

    /** 识别结果（§7.1 归一化枚举）。 */
    public enum Brand {
        PAPER("Paper", null, false),
        LEAF("Leaf", null, false),
        PURPUR("Purpur", null, false),
        SPIGOT("Spigot",
                "本插件以 paper-api 编译（DamageSource / Component 等为 Paper 专属），"
                        + "纯 Spigot 缺少这些类，onEnable 会抛 NoClassDefFoundError", false),
        FOLIA("Folia",
                "Folia 使用区域化线程模型，全局调度器任务（主循环 / HUD / 露天异步补扫）"
                        + "在其上不可用", true),
        UNKNOWN("未知分支", null, false);

        private final String displayName;

        /** 拒绝加载的原因；null = 本分支可以运行。 */
        private final String refusalReason;
        private final boolean folia;

        Brand(String displayName, String refusalReason, boolean folia) {
            this.displayName = displayName;
            this.refusalReason = refusalReason;
            this.folia = folia;
        }

        public String displayName() {
            return displayName;
        }

        /** 本分支能否承载当前实现（false = 应拒绝加载，见 {@link #refusalReason()}）。 */
        public boolean supported() {
            return refusalReason == null;
        }

        /** 是否为区域化线程的服务端（全局任务不可用）。 */
        public boolean folia() {
            return folia;
        }

        /** 拒绝加载的原因（仅 {@link #supported()} 为 false 时非 null）。 */
        public String refusalReason() {
            return refusalReason;
        }
    }

    /** 识别来源（透明化展示用，§7.3「/rad status 增 profile 与来源展示」）。 */
    public enum Source {
        CLASS_PROBE("类探测"),
        SERVER_NAME("server.getName()"),
        FALLBACK("兜底");

        private final String displayName;

        Source(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** 识别结论。 */
    public record Detection(Brand brand, Source source, String rawName) {

        /** 单行摘要：{@code Paper（类探测）} 或 {@code 未知分支（兜底，getName=NullServer）}。 */
        public String describe() {
            StringBuilder sb = new StringBuilder(brand.displayName());
            sb.append("（").append(source.displayName());
            if (source == Source.SERVER_NAME && rawName != null && !rawName.isBlank()) {
                sb.append("：getName=").append(rawName);
            }
            sb.append("）");
            return sb.toString();
        }
    }

    // —— 探测用全限定类名（都是编译期常量字符串，避免对缺失类产生硬链接；
    //    绝不要写成 XxxYyy.class 字面量，那会让本类在被探测类缺失时自身 NoClassDefFoundError）
    /** Folia 专属：{@code io.papermc.paper.threadedregions.RegionizedServer}。 */
    static final String FOLIA_CLASS = "io.papermc.paper.threadedregions.RegionizedServer";
    /** Paper 系专属：{@code DamageSource} 是本插件直接依赖的类，它的存在即代表「能跑」。 */
    static final String PAPER_CLASS = "org.bukkit.damage.DamageSource";

    private ServerBrand() {
    }

    /**
     * 识别当前服务端品牌。
     *
     * @param serverName {@code Bukkit.getServer().getName()} 的返回值（可为 null）
     * @param probe      类探测器
     * @return 识别结论（永不返回 null）
     */
    public static Detection detect(String serverName, ClassProbe probe) {
        // ① Folia 先判：纯 Spigot 也可能没改名，但 Folia 一定会带 RegionizedServer
        if (probe != null && probe.exists(FOLIA_CLASS)) {
            return new Detection(Brand.FOLIA, Source.CLASS_PROBE, serverName);
        }
        // ② 名字唯一命中：Folia/Leaf/Purpur 的名字不会被别的分支占用
        String lower = serverName == null ? "" : serverName.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("folia")) {
            return new Detection(Brand.FOLIA, Source.SERVER_NAME, serverName);
        }
        if (lower.contains("purpur")) {
            return new Detection(Brand.PURPUR, Source.SERVER_NAME, serverName);
        }
        if (lower.contains("leaf")) {
            return new Detection(Brand.LEAF, Source.SERVER_NAME, serverName);
        }
        // ③ Paper 判定以「能不能加载 Paper 专属类」为准：Paper 及其分支都被 getName 报成
        //    自己的名字，但它们必然带 DamageSource；纯 Spigot 没有
        if (probe != null && probe.exists(PAPER_CLASS)) {
            return new Detection(Brand.PAPER, Source.CLASS_PROBE, serverName);
        }
        // ④ 名字兜底：此时类探测说「没有 Paper API」，只有名字能给出额外信息
        if (lower.contains("spigot")) {
            return new Detection(Brand.SPIGOT, Source.SERVER_NAME, serverName);
        }
        if (lower.contains("paper")) {
            // 名字自称 Paper 但加载不到 DamageSource：类探测为阴性说明实际缺 Paper API，
            // 按 UNKNOWN 走（宁可让管理员看到「未知分支」也不要谎报支持）
            return new Detection(Brand.UNKNOWN, Source.SERVER_NAME, serverName);
        }
        return new Detection(Brand.UNKNOWN, Source.FALLBACK, serverName);
    }

    /** 生产用类探测器：只加载不初始化。 */
    public static ClassProbe defaultProbe() {
        return className -> {
            try {
                Class.forName(className, false, ServerBrand.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        };
    }

    /** 从 {@code server.getName()} + 默认探测器识别。 */
    public static Detection detectCurrent(String serverName) {
        return detect(serverName, defaultProbe());
    }
}
