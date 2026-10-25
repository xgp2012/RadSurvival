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
            "helmet", 0.20, "chestplate", 0.40, "leggings", 0.30, "boots", 0.10);

    private static final Map<String, Double> DEFAULT_MATERIAL_FACTORS = Map.of(
            "LEATHER", 0.25, "GOLD", 0.45, "CHAINMAIL", 0.45,
            "IRON", 0.70, "DIAMOND", 0.85, "NETHERITE", 1.00);

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

    public record ArmorConfig(Map<String, Double> slotWeights, Map<String, Double> materialFactors) {
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

    private PluginConfig(Set<String> enabledWorlds, Set<World.Environment> dimensions, int tickInterval,
                         double baseDamage, Set<GameMode> damageGameModes, boolean requireOnGround,
                         double nightMultiplier, boolean smoothTransition, boolean transparentCover,
                         boolean asyncExposureScan,
                         ArmorConfig armor, CorrosionConfig corrosion, FeedbackConfig feedback,
                         CommandsConfig commands) {
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

        // armor（§1.4）
        Map<String, Double> slotWeights = readFactorMap(
                root.node("armor", "slot-weights"), DEFAULT_SLOT_WEIGHTS, ALLOWED_SLOTS,
                "armor.slot-weights", errors, false);
        Map<String, Double> materialFactors = readFactorMap(
                root.node("armor", "material-factors"), DEFAULT_MATERIAL_FACTORS, ALLOWED_MATERIALS,
                "armor.material-factors", errors, true);
        ArmorConfig armor = new ArmorConfig(slotWeights, materialFactors);

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

        if (!errors.isEmpty()) {
            return new LoadResult(null, List.copyOf(errors));
        }
        PluginConfig cfg = new PluginConfig(worlds, dims, tickInterval, baseDamage, gameModes, requireOnGround,
                nightMultiplier, smoothTransition, transparentCover, asyncExposureScan, armor, corrosion, feedback,
                new CommandsConfig(reloadPermission));
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
