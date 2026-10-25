package com.ft.radsurvival.config;

import java.io.BufferedReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.bukkit.GameMode;
import org.bukkit.World;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * config.yml 的加载 / 类型安全映射 / 校验（plan.md §3）。
 *
 * <p>基于 Configurate {@link YamlConfigurationLoader}。校验逻辑保持纯 Java：
 * 仅引用 Bukkit API 的常量枚举 {@link World.Environment} 与 {@link GameMode}
 * （{@code Enum.valueOf}，无需运行中的服务器），因此单元测试不依赖 Paper 服务端。</p>
 *
 * <p>校验规则（一次校验聚合全部错误；非法值启动即拒服务、reload 时保留旧配置）：</p>
 * <ul>
 *   <li>{@code tick-interval >= 20}：原版玩家受击后 20 tick 无敌帧，窗口内 damage() 被静默吞掉（§1.3）；</li>
 *   <li>{@code reset-on-death} 与 {@code decay-on-death} 互斥；decay-on-death ∈ (0,1) 且仅在 reset 非 true 时合法；</li>
 *   <li>slot-weights / material-factors ∈ [0,1]；夜/伤害倍率 night-multiplier ∈ [0,1]；max-multiplier ≥ 1；</li>
 *   <li>材质键必须属于 {LEATHER, GOLD, CHAINMAIL, IRON, DIAMOND, NETHERITE}；</li>
 *   <li>curve ∈ {linear, sqrt, exp}；</li>
 *   <li>enabled-worlds 非空；dimensions / damage-game-modes 枚举合法；</li>
 *   <li>feedback.bossbar-corrosion / feedback.death-message 必须为布尔值（M3 新增，§1.5）；</li>
 *   <li>feedback.protocol-lib ∈ {auto, true, false}（M3 新增，大小写不敏感，§5.2）。</li>
 * </ul>
 */
public final class PluginConfig {

    /** 伤害结算间隔下限：原版无敌帧 20 tick（§1.3 / §6，PluginConfig 强制校验）。 */
    public static final int MIN_TICK_INTERVAL = 20;

    /** 材质系数允许的键（§1.4）。 */
    public static final Set<String> ALLOWED_MATERIALS = Set.of(
            "LEATHER", "GOLD", "CHAINMAIL", "IRON", "DIAMOND", "NETHERITE");

    /** 部位权重允许的键（§1.4）。 */
    public static final Set<String> ALLOWED_SLOTS = Set.of(
            "helmet", "chestplate", "leggings", "boots");

    private static final Map<String, Double> DEFAULT_SLOT_WEIGHTS = Map.of(
            "helmet", 0.12, "chestplate", 0.46, "leggings", 0.30, "boots", 0.12);

    /** 材质系数默认值（v1.2 全面削弱：下界合金 1.00 → 0.72，取消完全免疫）。 */
    private static final Map<String, Double> DEFAULT_MATERIAL_FACTORS = Map.of(
            "LEATHER", 0.15, "GOLD", 0.30, "CHAINMAIL", 0.30,
            "IRON", 0.48, "DIAMOND", 0.60, "NETHERITE", 0.72);

    /**
     * ProtocolLib 增强开关（M3，§1.5/§5.2：feedback.protocol-lib = auto | true | false）。
     * AUTO = 检测到 ProtocolLib 插件才启用增强（仅原版受伤红闪抑制）；ON 强制启用
     * （缺失时记录警告并降级）；OFF 一律关闭。任何模式下核心功能都与 ProtocolLib 无关。
     */
    public enum ProtocolLibMode {
        AUTO, ON, OFF;

        /**
         * 解析 YAML 标量：Boolean true/false 或字符串 auto/true/false（大小写不敏感）；
         * 非法返回 null 交由校验报错。
         */
        static ProtocolLibMode parse(Object raw) {
            if (raw instanceof Boolean b) {
                return b ? ON : OFF;
            }
            if (raw == null) {
                return null;
            }
            String s = raw.toString().trim().toLowerCase(Locale.ROOT);
            return switch (s) {
                case "auto" -> AUTO;
                case "true" -> ON;
                case "false" -> OFF;
                default -> null;
            };
        }
    }

    /** 腐蚀放大曲线（§1.6，sqrt/exp 为预留非线性手感）。 */
    public enum CorrosionCurve {
        LINEAR, SQRT, EXP;

        /** 大小写不敏感解析；非法返回 null。 */
        static CorrosionCurve parse(String raw) {
            for (CorrosionCurve c : values()) {
                if (c.name().equalsIgnoreCase(raw)) {
                    return c;
                }
            }
            return null;
        }
    }

    // ---------------------------------------------------------------- sections

    /**
     * 盔甲减免模型（§1.4；v1.2 起扩展）。
     *
     * <p><b>v1.2 语义变更</b>：旧口径允许「材质系数 1.00 → 全套减免恰 1.0 → 完全免疫」，
     * 下界合金因此可以彻底无视阳光。现改为<b>禁止任何材质通向免疫</b>：校验会逐材质算出
     * 满配减免，任一 ≥ 1.0 即报错；同时 {@link #minDamageFraction} 提供最低杀伤保底，
     * 即使将来放宽系数，单轮伤害也不会被压到 0。</p>
     *
     * @param slotWeights       部位权重（helmet/chestplate/leggings/boots → [0,1]）
     * @param materialFactors   材质系数（LEATHER/GOLD/CHAINMAIL/IRON/DIAMOND/NETHERITE → [0,1]）
     * @param minDamageFraction 最低杀伤比例 ∈ (0,1]：finalDamage 不得低于
     *                          {@code base × timeFactor × corrosionMult × minDamageFraction}
     */
    public record ArmorConfig(Map<String, Double> slotWeights,
                              Map<String, Double> materialFactors,
                              double minDamageFraction) {

        /** 某材质的「全套满配」减免（权重和 × 该材质系数，钳 [0,1]）。 */
        public double fullSetReduction(String materialKey) {
            Double factor = materialFactors.get(materialKey.toUpperCase(Locale.ROOT));
            if (factor == null) {
                return 0.0;
            }
            double weightSum = slotWeights.values().stream().mapToDouble(Double::doubleValue).sum();
            return Math.min(1.0, Math.max(0.0, weightSum * factor));
        }

        /** 全部材质里最高的「全套满配」减免（用于日志与 /rad status 摘要）。 */
        public double maxFullSetReduction() {
            return materialFactors.keySet().stream()
                    .mapToDouble(this::fullSetReduction).max().orElse(0.0);
        }
    }

    /**
     * 抗尘埃药档位（v1.2新增，anti_dust.levels 的一项）。
     *
     * @param id             档位键名（refined/strong/improved/crude，配置文件里的键）
     * @param displayName    物品显示名（不含颜色码，颜色由 {@link #color} 决定）
     * @param color          物品名颜色（#RRGGBB）
     * @param corrosionReduction 服药瞬间降低的腐蚀值（百分点 ∈ (0,100]）
     * @param immuneSeconds  辐射免疫时长（秒，≥ 0；0 = 只降腐蚀不免伤）
     * @param items          触发本档位的农作物物品枚举名（大写，按配置文件顺序）
     */
    public record DustLevel(String id,
                            String displayName,
                            String color,
                            double corrosionReduction,
                            int immuneSeconds,
                            List<String> items) {
    }

    /**
     * 抗尘埃药配置（v1.2 新增）。
     *
     * @param enabled         总开关（false = 不注册任何监听器，发射器保持原版行为）
     * @param cooldownSeconds 同一玩家两次服药的最小间隔（秒，≥ 0）
     * @param cures           服药瞬间无条件解除的状态效果名（大写，如 POISON）
     * @param hudImmuneMark   免疫期间 HUD 显示的抗性段文本
     * @param levels          档位表（顺序 = 配置文件顺序 = 由强到弱，取首个匹配）
     */
    public record AntiDustConfig(boolean enabled,
                                 int cooldownSeconds,
                                 List<String> cures,
                                 String hudImmuneMark,
                                 List<DustLevel> levels) {

        /** 某物品命中的档位（无匹配返回 null）。 */
        public DustLevel levelFor(String materialName) {
            if (materialName == null) {
                return null;
            }
            String upper = materialName.toUpperCase(Locale.ROOT);
            for (DustLevel level : levels) {
                if (level.items().contains(upper)) {
                    return level;
                }
            }
            return null;
        }

        /** 按档位 id 找档位（物品 PDC 里存的就是这个 id，未知返回 null）。 */
        public DustLevel levelById(String id) {
            if (id == null) {
                return null;
            }
            for (DustLevel level : levels) {
                if (level.id().equalsIgnoreCase(id)) {
                    return level;
                }
            }
            return null;
        }

        /** 全部被任何一个档位认领的物品名（GUI 校验「这个物品能不能放中心格」）。 */
        public Set<String> acceptedItems() {
            Set<String> out = new TreeSet<>();
            for (DustLevel level : levels) {
                out.addAll(level.items());
            }
            return Collections.unmodifiableSet(out);
        }
    }

    public record CorrosionConfig(
            boolean enabled,
            double dayAccumPerSec,
            double regenPerSec,
            boolean armorAffectsAccum,
            double maxMultiplier,
            CorrosionCurve curve,
            boolean resetOnDeath,
            /** null = 未设置（与 resetOnDeath 互斥，仅在 resetOnDeath=false 时允许出现）。 */
            Double decayOnDeath,
            double stageWarn,
            double stageDanger) {
    }

    /**
     * 反馈表现（§1.5，M3 全量接线）：actionbar 双条、受击粒子/音效、BossBar 常驻腐蚀条、
     * 死亡文案、ProtocolLib 增强开关。阶段跳变播报跟随 sound 开关（title+音效一体）。
     */
    public record FeedbackConfig(
            boolean actionbar,
            boolean particles,
            boolean sound,
            /** true = 腐蚀条改用 BossBar 常驻显示，actionbar 仅显示辐射等级条（M3 新键）。 */
            boolean bossbarCorrosion,
            /** true = 玩家死于辐射结算时替换废土风死亡文案（M3 新键，§2 DamageTagListener）。 */
            boolean deathMessage,
            /** ProtocolLib 增强模式（M3 新键：auto|true|false）。 */
            ProtocolLibMode protocolLib) {
    }

    public record CommandsConfig(String reloadPermission) {
    }

    /**
     * 加载结果：成功则 config 非 null 且 errors 为空；
     * 失败则 config 为 null 且 errors 为全部校验/解析错误明细（供 SEVERE 日志与 reload 回报）。
     */
    public record LoadResult(PluginConfig config, List<String> errors) {

        public boolean isSuccess() {
            return errors.isEmpty();
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    // -------------------------------------------------------------------- fields

    private final Set<String> enabledWorlds;
    private final Set<World.Environment> dimensions;
    private final int tickInterval;
    private final double baseDamage;
    private final Set<GameMode> damageGameModes;
    private final boolean requireOnGround;
    private final double nightMultiplier;
    private final boolean smoothTransition;
    private final boolean transparentBlocksCountAsCover;
    /** 露天扫描异步化（§2 线程模型预留项，本轮落地）：true=段缓存过期后的补扫交异步池，
     *  主线程当轮用上一已知判定（机制与公式完全不变，仅新鲜度滞后 ≤1 结算周期）；
     *  false=全部主线程同步扫描（旧口径）。冷启动首扫两种模式都在主线程保证首轮精确。 */
    private final boolean asyncExposureScan;
    private final ArmorConfig armor;
    private final CorrosionConfig corrosion;
    private final FeedbackConfig feedback;
    private final CommandsConfig commands;
    private final AntiDustConfig antiDust;

    private PluginConfig(Set<String> enabledWorlds, Set<World.Environment> dimensions, int tickInterval,
                         double baseDamage, Set<GameMode> damageGameModes, boolean requireOnGround,
                         double nightMultiplier, boolean smoothTransition, boolean transparentCover,
                         boolean asyncExposureScan,
                         ArmorConfig armor, CorrosionConfig corrosion, FeedbackConfig feedback,
                         CommandsConfig commands, AntiDustConfig antiDust) {
        this.enabledWorlds = enabledWorlds;
        this.dimensions = dimensions;
        this.tickInterval = tickInterval;
        this.baseDamage = baseDamage;
        this.damageGameModes = damageGameModes;
        this.requireOnGround = requireOnGround;
        this.nightMultiplier = nightMultiplier;
        this.smoothTransition = smoothTransition;
        this.transparentBlocksCountAsCover = transparentCover;
        this.asyncExposureScan = asyncExposureScan;
        this.armor = armor;
        this.corrosion = corrosion;
        this.feedback = feedback;
        this.commands = commands;
        this.antiDust = antiDust;
    }

    // ----------------------------------------------------------------- loaders

    /** 从 config.yml 文件加载并校验（不抛异常，错误聚合在结果里）。 */
    public static LoadResult loadFromFile(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return failure("配置文件不存在或不可读: " + path);
        }
        try {
            return parse(YamlConfigurationLoader.builder().path(path).build().load());
        } catch (ConfigurateException | RuntimeException e) {
            return failure("config.yml 解析失败（YAML 语法错误）: " + tail(e));
        }
    }

    /** 从 YAML 文本加载并校验（单元测试主入口）。 */
    public static LoadResult loadFromString(String yaml) {
        try {
            YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                    .source(() -> new BufferedReader(new StringReader(yaml)))
                    .build();
            return parse(loader.load());
        } catch (ConfigurateException | RuntimeException e) {
            return failure("YAML 解析失败: " + tail(e));
        }
    }

    private static String tail(Exception e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        return msg.length() > 400 ? msg.substring(0, 400) + "…" : msg;
    }

    private static LoadResult failure(String error) {
        return new LoadResult(null, List.of(error));
    }

    // ------------------------------------------------------------------ getters

    /** 世界白名单（小写世界名）。 */
    public Set<String> enabledWorlds() {
        return enabledWorlds;
    }

    public Set<World.Environment> dimensions() {
        return dimensions;
    }

    public int tickInterval() {
        return tickInterval;
    }

    public double baseDamage() {
        return baseDamage;
    }

    public Set<GameMode> damageGameModes() {
        return damageGameModes;
    }

    public boolean requireOnGround() {
        return requireOnGround;
    }

    public double nightMultiplier() {
        return nightMultiplier;
    }

    public boolean smoothTransition() {
        return smoothTransition;
    }

    public boolean transparentBlocksCountAsCover() {
        return transparentBlocksCountAsCover;
    }

    /** 露天扫描异步化开关（true=异步补扫 + 主线程粘滞消费；默认 true）。 */
    public boolean asyncExposureScan() {
        return asyncExposureScan;
    }

    public ArmorConfig armor() {
        return armor;
    }

    public CorrosionConfig corrosion() {
        return corrosion;
    }

    public FeedbackConfig feedback() {
        return feedback;
    }

    public CommandsConfig commands() {
        return commands;
    }

    /** 抗尘埃药配置（v1.2 新增）。 */
    public AntiDustConfig antiDust() {
        return antiDust;
    }

    // -------------------------------------------------------- parse & validate

    private static LoadResult parse(ConfigurationNode root) {
        List<String> errors = new ArrayList<>();

        Set<String> worlds = readEnabledWorlds(root.node("enabled-worlds"), errors);
        Set<World.Environment> dims = readDimensions(root.node("dimensions"), errors);

        int tickInterval = requireInt(root.node("tick-interval"), 20, "tick-interval", errors);
        if (tickInterval < MIN_TICK_INTERVAL) {
            errors.add("tick-interval=" + tickInterval + " 非法：伤害结算间隔必须 ≥ " + MIN_TICK_INTERVAL
                    + " tick —— 原版玩家受击后有 20 tick 无敌帧（伤害免疫窗口），间隔低于该值时 damage() 会被"
                    + "静默吞掉，表现为『腐蚀在涨但血不掉』（见 plan.md §1.3）；默认建议 20，实机若边界抖动可设 24");
        }

        double baseDamage = requireDouble(root.node("base-damage"), 1.0, "base-damage", errors);
        if (baseDamage <= 0.0) {
            errors.add("base-damage=" + fmt(baseDamage) + " 非法：基准伤害必须 > 0（半心/s 口径，生存预算见 §1.6）");
        }

        Set<GameMode> gameModes = readGameModes(root.node("damage-game-modes"), errors);
        boolean requireOnGround = requireBool(root.node("require-on-ground"), false, "require-on-ground", errors);

        double nightMultiplier = requireDouble(root.node("night-multiplier"), 0.5, "night-multiplier", errors);
        if (nightMultiplier < 0.0 || nightMultiplier > 1.0) {
            errors.add("night-multiplier=" + fmt(nightMultiplier) + " 非法：夜晚伤害倍率必须 ∈ [0,1]");
        }
        boolean smoothTransition = requireBool(root.node("smooth-transition"), true, "smooth-transition", errors);
        boolean transparentCover = requireBool(root.node("transparent-blocks-count-as-cover"), true,
                "transparent-blocks-count-as-cover", errors);
        boolean asyncExposureScan = requireBool(root.node("async-exposure-scan"), true,
                "async-exposure-scan", errors);

        // armor（§1.4；v1.2 全面削弱 + 禁止免疫）
        Map<String, Double> slotWeights = readFactorMap(
                root.node("armor", "slot-weights"), DEFAULT_SLOT_WEIGHTS, ALLOWED_SLOTS,
                "armor.slot-weights", errors, false);
        Map<String, Double> materialFactors = readFactorMap(
                root.node("armor", "material-factors"), DEFAULT_MATERIAL_FACTORS, ALLOWED_MATERIALS,
                "armor.material-factors", errors, true);
        double minDamageFraction = requireDouble(root.node("armor", "min-damage-fraction"), 0.10,
                "armor.min-damage-fraction", errors);
        if (minDamageFraction <= 0.0 || minDamageFraction > 1.0) {
            errors.add("armor.min-damage-fraction=" + fmt(minDamageFraction)
                    + " 非法：最低杀伤保底必须位于开区间 (0,1]（0.10 = 最强防护下仍承受 10% 阳光伤害）");
        }
        ArmorConfig armor = new ArmorConfig(slotWeights, materialFactors, minDamageFraction);
        // v1.2 硬约束：任何材质都不得通过「系数 1.0」达成完全免疫（下界合金免疫已移除）
        double weightSum = slotWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        // 先校验权重和本身，再校验逐材质乘积——否则权重表写错时会先冒出
        // 「该系数 × 部位权重和(0) = 0」这种指错方向的报错。
        if (weightSum <= 0.0) {
            errors.add("armor.slot-weights 全为 0：至少保留一个部位权重 > 0，否则盔甲完全不起作用");
        }
        // 权重和不得 > 1.0：满配减免被定义为「权重和 × 系数」，权重和 > 1 会让
        // 「按系数判断是否免疫」失去意义（乘出来的乘积可以轻易 ≥ 1），
        // 也会让 /rad status 展示的百分比超过 100%。
        if (weightSum > 1.0 + 1e-9) {
            errors.add("armor.slot-weights 权重和 = " + fmt(weightSum)
                    + " 非法：必须 ≤ 1.0（满配减免 = 权重和 × 材质系数，权重和 >1 会让百分比超过 100%）");
        }
        for (Map.Entry<String, Double> entry : new TreeMap<>(materialFactors).entrySet()) {
            double fullSet = weightSum * entry.getValue();
            if (fullSet >= 1.0 - 1e-9) {
                errors.add("armor.material-factors." + entry.getKey() + "=" + fmt(entry.getValue())
                        + " 非法：该系数 × 部位权重和(" + fmt(weightSum) + ") = " + fmt(fullSet)
                        + " ≥ 1.0 —— v1.2 起禁止任何盔甲材质达成完全免疫（下界合金免疫已移除），"
                        + "请调低该系数（参考默认：皮革 .15 / 金·锁链 .30 / 铁 .48 / 钻石 .60 / 下界合金 .72）");
            }
        }

        // corrosion（§1.6）
        ConfigurationNode c = root.node("corrosion");
        boolean corrEnabled = requireBool(c.node("enabled"), true, "corrosion.enabled", errors);
        double dayAccum = requireDouble(c.node("day-accum-per-sec"), 1.0, "corrosion.day-accum-per-sec", errors);
        if (dayAccum < 0.0) {
            errors.add("corrosion.day-accum-per-sec=" + fmt(dayAccum) + " 非法：累积速率必须 ≥ 0（%/s）");
        }
        double regen = requireDouble(c.node("regen-per-sec"), 1.0, "corrosion.regen-per-sec", errors);
        if (regen < 0.0) {
            errors.add("corrosion.regen-per-sec=" + fmt(regen) + " 非法：恢复速率必须 ≥ 0（%/s）");
        }
        boolean armorAffectsAccum = requireBool(c.node("armor-affects-accum"), true,
                "corrosion.armor-affects-accum", errors);
        double maxMultiplier = requireDouble(c.node("max-multiplier"), 2.0, "corrosion.max-multiplier", errors);
        if (maxMultiplier < 1.0) {
            errors.add("corrosion.max-multiplier=" + fmt(maxMultiplier) + " 非法：伤害倍率上限必须 ≥ 1"
                    + "（腐蚀 100% 时的倍率封顶，基线恒为 ×1.0，§1.3 公式）");
        }
        CorrosionCurve curve = readCurve(c.node("curve"), errors);
        boolean resetOnDeath = requireBool(c.node("reset-on-death"), true, "corrosion.reset-on-death", errors);
        ConfigurationNode decayNode = c.node("decay-on-death");
        Double decayOnDeath = decayNode.virtual()
                ? null
                : toDouble(decayNode.rawScalar(), "corrosion.decay-on-death", errors);
        if (decayOnDeath != null) {
            if (resetOnDeath) {
                errors.add("corrosion.reset-on-death 与 corrosion.decay-on-death 互斥："
                        + "reset-on-death=true（死亡清零）时不得同时设置 decay-on-death=" + fmt(decayOnDeath)
                        + "；要用『死亡按比例保留』请设 reset-on-death: false 后再配 decay-on-death（§3）");
            } else if (decayOnDeath <= 0.0 || decayOnDeath >= 1.0) {
                errors.add("corrosion.decay-on-death=" + fmt(decayOnDeath) + " 非法：必须位于开区间 (0,1)");
            }
        }
        double stageWarn = requireDouble(c.node("stages", "warn"), 50.0, "corrosion.stages.warn", errors);
        double stageDanger = requireDouble(c.node("stages", "danger"), 75.0, "corrosion.stages.danger", errors);
        CorrosionConfig corrosion = new CorrosionConfig(corrEnabled, dayAccum, regen, armorAffectsAccum,
                maxMultiplier, curve, resetOnDeath, decayOnDeath, stageWarn, stageDanger);

        // feedback（§1.5，M3 接线；新增键全部有校验，禁止无校验键）
        ConfigurationNode f = root.node("feedback");
        ProtocolLibMode protocolLib = readProtocolLibMode(f.node("protocol-lib"), errors);
        FeedbackConfig feedback = new FeedbackConfig(
                requireBool(f.node("actionbar"), true, "feedback.actionbar", errors),
                requireBool(f.node("particles"), true, "feedback.particles", errors),
                requireBool(f.node("sound"), true, "feedback.sound", errors),
                requireBool(f.node("bossbar-corrosion"), false, "feedback.bossbar-corrosion", errors),
                requireBool(f.node("death-message"), true, "feedback.death-message", errors),
                protocolLib);

        // commands
        String reloadPermission = requireString(root.node("commands", "reload-permission"),
                "radsurvival.reload", "commands.reload-permission", errors);
        if (reloadPermission.isBlank()) {
            errors.add("commands.reload-permission 不能为空：需要权限节点名（如 radsurvival.reload）");
        }

        // anti_dust（v1.2）
        AntiDustConfig antiDust = readAntiDust(root.node("anti_dust"), errors);

        if (!errors.isEmpty()) {
            return new LoadResult(null, List.copyOf(errors));
        }
        PluginConfig cfg = new PluginConfig(worlds, dims, tickInterval, baseDamage, gameModes, requireOnGround,
                nightMultiplier, smoothTransition, transparentCover, asyncExposureScan, armor, corrosion, feedback,
                new CommandsConfig(reloadPermission), antiDust);
        return new LoadResult(cfg, List.of());
    }

    // ------------------------------------------------------------ read helpers

    private static Set<String> readEnabledWorlds(ConfigurationNode node, List<String> errors) {
        Set<String> out = new TreeSet<>();
        if (!node.virtual()) {
            for (Object o : readScalarList(node, "enabled-worlds", errors)) {
                String name = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
                if (!name.isEmpty()) {
                    out.add(name);
                }
            }
        }
        if (out.isEmpty()) {
            errors.add("enabled-worlds 不能为空：至少填写一个生效世界名（与 dimensions 取交集，§3）");
        }
        return Collections.unmodifiableSet(out);
    }

    private static Set<World.Environment> readDimensions(ConfigurationNode node, List<String> errors) {
        Set<World.Environment> out = EnumSet.noneOf(World.Environment.class);
        List<Object> raws = node.virtual()
                ? List.of("NORMAL")
                : readScalarList(node, "dimensions", errors);
        for (Object o : raws) {
            String name = String.valueOf(o).trim().toUpperCase(Locale.ROOT);
            try {
                out.add(World.Environment.valueOf(name));
            } catch (IllegalArgumentException ex) {
                errors.add("dimensions=\"" + o + "\" 非法：必须是 NORMAL / NETHER / THE_END 之一"
                        + "（仅主世界有太阳，下界/末地默认豁免，§1.1）");
            }
        }
        if (out.isEmpty()) {
            out.add(World.Environment.NORMAL);
        }
        return Collections.unmodifiableSet(out);
    }

    private static Set<GameMode> readGameModes(ConfigurationNode node, List<String> errors) {
        Set<GameMode> out = EnumSet.noneOf(GameMode.class);
        if (node.virtual()) {
            return Collections.unmodifiableSet(EnumSet.of(GameMode.SURVIVAL, GameMode.ADVENTURE));
        }
        List<Object> raws = readScalarList(node, "damage-game-modes", errors);
        if (raws.isEmpty()) {
            errors.add("damage-game-modes 不能为空：至少列出 SURVIVAL / ADVENTURE 之一");
        }
        for (Object o : raws) {
            String name = String.valueOf(o).trim().toUpperCase(Locale.ROOT);
            GameMode mode;
            try {
                mode = GameMode.valueOf(name);
            } catch (IllegalArgumentException ex) {
                errors.add("damage-game-modes=\"" + o + "\" 非法：必须是 SURVIVAL / ADVENTURE / CREATIVE / SPECTATOR");
                continue;
            }
            if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
                errors.add("damage-game-modes 包含 " + mode + "：创造/旁观无条件免疫（§1.3），"
                        + "不应也无法配置进结算列表");
                continue;
            }
            out.add(mode);
        }
        if (out.isEmpty()) {
            out.add(GameMode.SURVIVAL);
        }
        return Collections.unmodifiableSet(out);
    }

    private static CorrosionCurve readCurve(ConfigurationNode node, List<String> errors) {
        String raw = requireString(node, "linear", "corrosion.curve", errors);
        CorrosionCurve curve = CorrosionCurve.parse(raw.trim());
        if (curve == null) {
            errors.add("corrosion.curve=\"" + raw + "\" 非法：可选 linear | sqrt | exp（非线性曲线为预留手感，§1.6）");
            return CorrosionCurve.LINEAR;
        }
        return curve;
    }

    // --------------------------------------------------------- anti_dust (v1.2)

    /**
     * anti_dust 段读取与校验（v1.2）。
     *
     * <p>缺省（节点不存在）时走内置默认表，保证「老配置文件升级后抗尘埃药仍可用」；
     * 显式写了但内容非法则逐条报错并拒绝启动（与其他段同一口径：禁止无校验键）。</p>
     */
    private static AntiDustConfig readAntiDust(ConfigurationNode node, List<String> errors) {
        if (node.virtual()) {
            return DEFAULT_ANTI_DUST;
        }
        boolean enabled = requireBool(node.node("enabled"), true, "anti_dust.enabled", errors);
        int cooldown = requireInt(node.node("cooldown-seconds"), 20, "anti_dust.cooldown-seconds", errors);
        if (cooldown < 0) {
            errors.add("anti_dust.cooldown-seconds=" + cooldown + " 非法：冷却秒数必须 ≥ 0（0 = 无冷却）");
        }
        List<String> cures = new ArrayList<>();
        ConfigurationNode curesNode = node.node("cures");
        if (!curesNode.virtual()) {
            for (Object raw : readScalarList(curesNode, "anti_dust.cures", errors)) {
                String name = String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
                if (!isKnownEffect(name)) {
                    // 注意措辞：这里只校验「是不是一个合法的标识符名」，**没有**校验该效果是否真实存在
                    // （PotionEffectType 的有效性只能靠运行期 Registry 解析）。别把错误信息写成
                    // 「未知状态效果」，否则服主会以为写错名字也会被拦下——实际不会。
                    errors.add("anti_dust.cures 含非法状态效果名 \"" + raw + "\"：只允许大写标识符"
                            + "（如 POISON / HUNGER / NAUSEA；拼错的名字不会被拦下，只会运行时静默跳过）");
                    continue;
                }
                cures.add(name);
            }
        }
        String mark = requireString(node.node("hud-immume-mark"), "◆抗尘", "anti_dust.hud-immume-mark", errors);
        if (mark.isBlank()) {
            errors.add("anti_dust.hud-immume-mark 不能为空：免疫期间 HUD 需要一段可辨识的抗性文本");
        }

        List<DustLevel> levels = new ArrayList<>();
        Set<String> claimed = new TreeSet<>();
        ConfigurationNode levelsNode = node.node("levels");
        if (levelsNode.virtual()) {
            return new AntiDustConfig(enabled, Math.max(0, cooldown), List.copyOf(cures),
                    mark.isBlank() ? "◆抗尘" : mark, DEFAULT_ANTI_DUST.levels());
        }
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : levelsNode.childrenMap().entrySet()) {
            String id = String.valueOf(entry.getKey()).trim();
            ConfigurationNode level = entry.getValue();
            String label = "anti_dust.levels." + id;

            String displayName = requireString(level.node("display-name"), "", label + ".display-name", errors);
            if (displayName.isBlank()) {
                errors.add(label + ".display-name 不能为空：这是玩家在物品栏里看到的名字");
            }
            String color = requireString(level.node("color"), "#FFFFFF", label + ".color", errors);
            if (!HEX_COLOR.matcher(color).matches()) {
                errors.add(label + ".color=\"" + color + "\" 非法：必须是 #RRGGBB（如 #63D19B）");
            }
            double reduction = requireDouble(level.node("reduction"), 0.0, label + ".reduction", errors);
            if (reduction <= 0.0 || reduction > 100.0) {
                errors.add(label + ".reduction=" + fmt(reduction)
                        + " 非法：服药降低的腐蚀值必须位于 (0,100] 百分点区间");
            }
            int immune = requireInt(level.node("immune-seconds"), 0, label + ".immune-seconds", errors);
            if (immune < 0) {
                errors.add(label + ".immune-seconds=" + immune + " 非法：免疫时长必须 ≥ 0（0 = 只降腐蚀不免伤）");
            }
            List<String> items = new ArrayList<>();
            for (Object raw : readScalarList(level.node("items"), label + ".items", errors)) {
                String name = String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
                if (!isKnownMaterial(name)) {
                    errors.add(label + ".items 含未知物品 \"" + raw + "\"：必须是合法 Material 枚举名");
                    continue;
                }
                if (!claimed.add(name)) {
                    errors.add(label + ".items 中的 " + name + " 已被更靠前的档位认领："
                            + "同一作物只能属于一个档位（档位顺序 = 由强到弱，取首个匹配）");
                    continue;
                }
                items.add(name);
            }
            if (items.isEmpty()) {
                errors.add(label + ".items 不能为空：该档位至少需要一种农作物");
            }
            levels.add(new DustLevel(id, displayName, color, reduction, Math.max(0, immune), List.copyOf(items)));
        }
        if (levels.isEmpty()) {
            errors.add("anti_dust.levels 不能为空：至少配置一个档位，否则抗尘埃药无法制作");
        }
        return new AntiDustConfig(enabled, Math.max(0, cooldown), List.copyOf(cures),
                mark.isBlank() ? "◆抗尘" : mark, List.copyOf(levels));
    }

    /** #RRGGBB 十六进制颜色（校验用）。 */
    private static final java.util.regex.Pattern HEX_COLOR =
            java.util.regex.Pattern.compile("^#[0-9A-Fa-f]{6}$");

    /**
     * 状态效果名合法性（无需运行中服务器：{@code PotionEffectType} 是注册表条目常量，
     * 用 {@code Registry} 查询需要服务器；此处退化为「非空 + 全大写标识符」的可编译期校验，
     * 真正的有效性由运行期 {@code Registry.EFFECT} 解析兜底，解析失败仅记警告不拒载）。
     */
    private static boolean isKnownEffect(String name) {
        return IDENTIFIER.matcher(name).matches();
    }

    /** 物品枚举名合法性（枚举常量查找，无需服务器）。 */
    private static boolean isKnownMaterial(String name) {
        for (org.bukkit.Material m : org.bukkit.Material.values()) {
            if (m.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 大写标识符（PI_GRES/UNDEAD 这类带下划线/数字的也可）。 */
    private static final java.util.regex.Pattern IDENTIFIER =
            java.util.regex.Pattern.compile("^[A-Z][A-Z0-9_]*$");

    /** 缺省（配置里没有 anti_dust 段）时使用的内置默认值，与 resources/config.yml 保持一致。 */
    private static final AntiDustConfig DEFAULT_ANTI_DUST = new AntiDustConfig(
            true, 20,
            List.of("POISON", "HUNGER", "NAUSEA", "WEAKNESS", "SLOWNESS"),
            "◆抗尘",
            List.of(
                    new DustLevel("refined", "精制抗尘埃药", "#8FE3C4", 50.0, 30,
                            List.of("GOLDEN_CARROT", "CHORUS_FRUIT", "GLOW_BERRIES", "ENCHANTED_GOLDEN_APPLE")),
                    new DustLevel("strong", "强效抗尘埃药", "#63D19B", 35.0, 20,
                            List.of("NETHER_WART", "CRIMSON_FUNGUS", "WARPED_FUNGUS", "SWEET_BERRIES")),
                    new DustLevel("improved", "改良抗尘埃药", "#C8B560", 25.0, 15,
                            List.of("PUMPKIN", "MELON_SLICE", "SUGAR_CANE", "COCOA_BEANS",
                                    "BROWN_MUSHROOM", "RED_MUSHROOM")),
                    new DustLevel("crude", "粗制抗尘埃药", "#9AA35A", 15.0, 10,
                            List.of("WHEAT", "CARROT", "POTATO", "BEETROOT", "KELP", "DRIED_KELP",
                                    "BAMBOO", "CACTUS"))));

    /** feedback.protocol-lib 校验（M3 新键）：auto | true | false，缺省 AUTO（§1.5/§5.2）。 */
    private static ProtocolLibMode readProtocolLibMode(ConfigurationNode node, List<String> errors) {
        if (node.virtual()) {
            return ProtocolLibMode.AUTO;
        }
        ProtocolLibMode mode = ProtocolLibMode.parse(node.rawScalar());
        if (mode == null) {
            errors.add("feedback.protocol-lib=\"" + node.rawScalar() + "\" 非法：只允许 auto / true / false"
                    + "（auto=检测到 ProtocolLib 才启用红闪抑制增强；true=强制启用，缺失记录警告并降级；"
                    + "false=关闭增强。任何取值下核心功能均不依赖 ProtocolLib，§1.5/§5.2）");
            return ProtocolLibMode.AUTO;
        }
        return mode;
    }

    /** 读取权重/系数映射：键白名单 + 值 ∈ [0,1]；缺省键取计划默认值。 */
    private static Map<String, Double> readFactorMap(ConfigurationNode node, Map<String, Double> defaults,
                                                     Set<String> allowedKeys, String label,
                                                     List<String> errors, boolean upperCaseKeys) {
        Map<String, Double> out = new TreeMap<>(defaults);
        if (node.virtual()) {
            return Collections.unmodifiableMap(out);
        }
        // 结构性校验（v1.2）：本方法只吃「键 → 数值」的映射。若服主把整段写成列表或裸标量
        // （例如把 `slot-weights: {…}` 误改成多行标量、或把 inline map 换成 `- helmet: 0.1`），
        // 旧实现会因为 childrenMap() 为空而**静默套用默认值**——配置看起来生效了，实际完全没读进去。
        // 这种「静默忽略」比报错更难查，所以这里显式拒绝。
        if (node.childrenMap().isEmpty()) {
            if (!node.childrenList().isEmpty()) {
                errors.add(label + " 结构非法：期望 {键: 数值} 映射，实际是列表");
            } else if (node.rawScalar() != null) {
                errors.add(label + " 结构非法：期望 {键: 数值} 映射，实际是单个值 \""
                        + node.rawScalar() + "\"");
            } else {
                errors.add(label + " 结构非法：期望 {键: 数值} 映射，实际为空节点");
            }
            return Collections.unmodifiableMap(out);
        }
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
            String keyRaw = String.valueOf(entry.getKey()).trim();
            String key = upperCaseKeys
                    ? keyRaw.toUpperCase(Locale.ROOT)
                    : keyRaw.toLowerCase(Locale.ROOT);
            if (!allowedKeys.contains(key)) {
                errors.add(label + " 非法键 \"" + keyRaw + "\"：只允许 "
                        + String.join(" / ", new TreeSet<>(allowedKeys)) + "（大小写不敏感）");
                continue;
            }
            Double value = toDouble(entry.getValue().rawScalar(), label + "." + keyRaw, errors);
            if (value == null) {
                continue;
            }
            if (value < 0.0 || value > 1.0) {
                errors.add(label + "." + keyRaw + "=" + fmt(value) + " 非法：部位权重/材质系数必须 ∈ [0,1]（§1.4）");
                continue;
            }
            out.put(key, value);
        }
        // 缺键提示：不算错误（默认值仍然可用），但必须让服主知道「你以为改了、其实没写」
        // 注意：out 是 defaults 的副本，所以必须对照「配置文件里实际出现的键」而不是 out 的键集
        Set<String> present = new TreeSet<>();
        for (Object raw : node.childrenMap().keySet()) {
            String k = String.valueOf(raw).trim();
            present.add(upperCaseKeys ? k.toUpperCase(Locale.ROOT) : k.toLowerCase(Locale.ROOT));
        }
        for (String allowed : new TreeSet<>(allowedKeys)) {
            if (!present.contains(allowed)) {
                errors.add(label + " 缺少键 \"" + allowed + "\"：将使用默认值（若这是笔误，请检查拼写）");
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** 节点标量 → List；标量节点视为单元素列表；空列表返回空；映射结构报错。 */
    private static List<Object> readScalarList(ConfigurationNode node, String label, List<String> errors) {
        List<? extends ConfigurationNode> list = node.childrenList();
        if (!list.isEmpty()) {
            List<Object> out = new ArrayList<>(list.size());
            for (ConfigurationNode child : list) {
                Object scalar = child.rawScalar();
                if (scalar == null) {
                    errors.add(label + " 存在非量列表元素（只接受字符串/数字标量）");
                } else {
                    out.add(scalar);
                }
            }
            return out;
        }
        Object scalar = node.rawScalar();
        if (scalar != null) {
            return List.of(scalar);
        }
        if (node.childrenMap().isEmpty()) {
            return List.of(); // 合法的空列表 []
        }
        errors.add(label + " 结构非法：期望标量或列表，实际为映射");
        return List.of();
    }

    private static Double toDouble(Object raw, String label, List<String> errors) {
        if (raw == null) {
            errors.add(label + "：期望数值，但未取到标量");
            return null;
        }
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(raw.toString().trim());
        } catch (NumberFormatException ex) {
            errors.add(label + "：期望数值，实际为 \"" + raw + "\"");
            return null;
        }
    }

    private static double requireDouble(ConfigurationNode node, double def, String label, List<String> errors) {
        if (node.virtual()) {
            return def;
        }
        Double v = toDouble(node.rawScalar(), label, errors);
        return v == null ? def : v;
    }

    private static int requireInt(ConfigurationNode node, int def, String label, List<String> errors) {
        if (node.virtual()) {
            return def;
        }
        Double v = toDouble(node.rawScalar(), label, errors);
        if (v == null) {
            return def;
        }
        if (v != Math.rint(v) || v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            errors.add(label + "=" + fmt(v) + " 非法：必须为整数 tick 数");
            return def;
        }
        return v.intValue();
    }

    private static boolean requireBool(ConfigurationNode node, boolean def, String label, List<String> errors) {
        if (node.virtual()) {
            return def;
        }
        Object raw = node.rawScalar();
        if (raw instanceof Boolean b) {
            return b;
        }
        String s = raw == null ? "" : raw.toString().trim().toLowerCase(Locale.ROOT);
        if (s.equals("true")) {
            return true;
        }
        if (s.equals("false")) {
            return false;
        }
        errors.add(label + "：期望布尔值 true/false，实际为 \"" + raw + "\"");
        return def;
    }

    private static String requireString(ConfigurationNode node, String def, String label, List<String> errors) {
        if (node.virtual()) {
            return def;
        }
        Object raw = node.rawScalar();
        if (raw == null) {
            errors.add(label + "：期望字符串，但未取到标量");
            return def;
        }
        return raw.toString().trim();
    }

    /** 去掉多余小数尾零的可读数字格式（用于错误信息）。 */
    private static String fmt(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }
}
