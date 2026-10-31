package com.ft.radsurvival.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.config.PluginConfig;
import com.ft.radsurvival.core.CorrosionMeter;
import com.ft.radsurvival.core.DayNightProvider;
import com.ft.radsurvival.core.RadiationMath;
import com.ft.radsurvival.core.RadiationServices;
import com.ft.radsurvival.core.RadiationTask;
import com.ft.radsurvival.core.RadiationTask.RoundOutcome;
import com.ft.radsurvival.core.RadiationTask.RoundResult;
import com.ft.radsurvival.core.ServerBrand;
import com.ft.radsurvival.item.DustCraftGui;

/**
 * /rad 子命令路由 + TabComplete（plan.md §2 command/RadCommand.java）。
 *
 * <p>命令层重构（本轮）把「路由 / 别名 / 用法 / 权限 / 补全」收敛到 {@link SubCommand}，
 * 输出统一走 {@link CommandOutput} 的对齐排版；新增 {@code help}、{@code reload --dry-run}、
 * 子命令别名与「最近似命令」提示。功能语义与此前完全一致：</p>
 * <ul>
 *   <li>{@code reload [--dry-run]}：权限取 {@code commands.reload-permission}；非法配置保留旧值。
 *       成功后服务容器整体重建；{@code --dry-run} 只校验磁盘 config.yml 并输出结果，不应用；</li>
 *   <li>{@code status [player]}：只读预览一轮（DayNightProvider 统一口径，无任何副作用）；
 *       console/RCON 无参时输出配置摘要 + 各启用世界昼夜；</li>
 *   <li>{@code force-check [player]}：立即跑一轮<b>真实结算</b>（同一 {@link RadiationTask#settleRound}
 *       → DamageApplier 路径），免疫/过滤跳过时明确报告原因；</li>
 *   <li>{@code corrosion get|set|add <player> [值]}：权限 {@code radsurvival.admin}；
 *       set 钳 [0,100]、add 支持负数同样钳制；写后同步内存态与 Player PDC（§1.6）。</li>
 * </ul>
 * <p>输出文本中的关键定位串（如「控制台摘要」「内存权威」「残差」等）为测试与运维锚点，
 * 调整排版时保留。</p>
 */
public final class RadCommand implements CommandExecutor, TabCompleter {

    /** 腐蚀管理命令权限（§1.6/§3；plugin.yml 已声明 default: op）。 */
    public static final String PERMISSION_ADMIN = "radsurvival.admin";

    /**
     * 能否查看<b>他人</b>的防护细节（盔甲减免 / 最低杀伤 / 预计伤害）。
     *
     * <p>bug hunt P1-4：这些是 PVP 场景下的对手情报，`/rad status &lt;他人&gt;` 又无权限门槛，
     * 因此只有管理员可见；查自己不受限。</p>
     */
    private static boolean canInspectOthers(CommandSender sender) {
        return sender.hasPermission(PERMISSION_ADMIN);
    }

    private static final List<String> CORROSION_OPS = List.of("get", "set", "add");

    /** v1.2 /rad dust 的动作（补全与校验共用）。 */
    private static final List<String> DUST_OPS = List.of("list", "give", "help");
    private static final String DRY_RUN_FLAG = "--dry-run";

    private final RadSurvivalPlugin plugin;

    public RadCommand(RadSurvivalPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ 路由

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender, label, null);
            return true;
        }
        SubCommand sub = SubCommand.parse(args[0]);
        if (sub == null) {
            unknownSub(sender, label, args[0]);
            return true;
        }
        switch (sub) {
            case RELOAD -> handleReload(sender, args);
            case STATUS -> handleStatus(sender, args);
            case CHECK -> handleForceCheck(sender, args);
            case CORROSION -> handleCorrosion(sender, args);
            case DUST -> handleDust(sender, args);
            case HELP -> sendHelp(sender, label, args.length >= 2 ? args[1] : null);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(visibleSubNames(sender), args[0]);
        }
        SubCommand sub = SubCommand.parse(args[0]);
        if (sub == null) {
            return List.of();
        }
        return switch (sub) {
            case RELOAD -> args.length == 2 ? filter(List.of(DRY_RUN_FLAG), args[1]) : List.of();
            case STATUS, CHECK -> args.length == 2 ? filter(onlinePlayerNames(), args[1]) : List.of();
            case HELP -> args.length == 2 ? filter(visibleSubNames(sender), args[1]) : List.of();
            case CORROSION -> tabCorrosion(sender, args);
            case DUST -> tabDust(sender, args);
        };
    }

    /** dust 分层补全：list|give|help → 档位 id → 玩家。 */
    private List<String> tabDust(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return filter(DUST_OPS, args[1]);
        }
        String op = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 3) {
            return op.equals("give") ? filter(dustLevelIds(), args[2]) : List.of();
        }
        if (args.length == 4) {
            // bug hunt P2-5：只有 give 消费玩家名参数；list/help 给出玩家名补全
            // 会让玩家以为「/rad dust list <玩家>」可用，实际第四个参数被忽略。
            return op.equals("give") ? filter(onlinePlayerNames(), args[3]) : List.of();
        }
        return List.of();
    }

    /** 配置里的档位 id（未就绪或档位表为空时退回内置四档，保证补全不会空手）。 */
    private List<String> dustLevelIds() {
        PluginConfig cfg = plugin.config();
        // bug hunt P2-5：levels 为空（服主写了 `levels: []`）时也要退化到内置表，
        // 否则补全给出空列表，与执行路径的「永远失败」互相印证，看不出是配置问题。
        if (cfg == null || cfg.antiDust().levels().isEmpty()) {
            return List.of("refined", "strong", "improved", "crude");
        }
        return cfg.antiDust().levels().stream().map(PluginConfig.DustLevel::id).toList();
    }

    /** corrosion 分层补全：op → 玩家 → 数值提示。 */
    private List<String> tabCorrosion(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return filter(CORROSION_OPS, args[1]);
        }
        if (args.length == 3) {
            return filter(onlinePlayerNames(), args[2]);
        }
        if (args.length == 4) {
            // 已填部分数值时给「围绕当前值」的快捷档位，而非固定几个魔数
            String op = args[1].toLowerCase(Locale.ROOT);
            double base = 0.0;
            Player target = Bukkit.getPlayer(args[2]);
            if (target != null) {
                base = plugin.api().getCorrosion(target);
            }
            List<String> hints = new ArrayList<>();
            if (op.equals("set")) {
                hints.addAll(List.of("0", "25", "50", "75", "100"));
                hints.add(fmt(Math.round(base)));
            } else if (op.equals("add")) {
                hints.addAll(List.of("10", "-10", "25", "-25", "50", "-50"));
            }
            return filter(hints.stream().distinct().toList(), args[3]);
        }
        return List.of();
    }

    /** 当前发送者可见的子命令名（按权限过滤，避免向普通玩家提示管理命令）。 */
    private List<String> visibleSubNames(CommandSender sender) {
        List<String> names = new ArrayList<>();
        for (SubCommand sub : SubCommand.values()) {
            if (sub == SubCommand.CORROSION && !sender.hasPermission(PERMISSION_ADMIN)) {
                continue;
            }
            if (sub == SubCommand.DUST && !sender.hasPermission(PERMISSION_ADMIN)) {
                continue;
            }
            names.addAll(sub.allNames());
        }
        return names;
    }

    // ------------------------------------------------------------------ reload

    /** /rad reload [--dry-run]：权限取配置（console 天然持有）；--dry-run 只校验不应用。 */
    private void handleReload(CommandSender sender, String[] args) {
        PluginConfig cfg = plugin.config();
        if (cfg == null) {
            notReady(sender);
            return;
        }
        String permission = cfg.commands().reloadPermission();
        if (!sender.hasPermission(permission)) {
            sender.sendMessage("§c缺少权限：" + permission);
            return;
        }
        boolean dryRun = args.length >= 2 && args[1].equalsIgnoreCase(DRY_RUN_FLAG);
        if (dryRun) {
            PluginConfig.LoadResult preview = PluginConfig.loadFromFile(plugin.configFile());
            if (preview.hasErrors()) {
                sender.sendMessage("§c预检失败：config.yml 非法（未应用，服务仍按旧配置运行）。错误明细：");
                preview.errors().forEach(error -> sender.sendMessage("  §c- " + error));
            } else {
                sender.sendMessage("§aconfig.yml 预检通过（未应用）。执行 §f/rad reload§a 使其生效。");
            }
            return;
        }
        List<String> errors = plugin.reloadConfigFromDisk();
        if (errors.isEmpty()) {
            sender.sendMessage("§aconfig.yml 重载成功，新配置已生效（服务容器与主循环已按新配置重建，在线玩家腐蚀经 PDC 无缝续接）。");
        } else {
            sender.sendMessage("§cconfig.yml 非法，已保留旧配置。错误明细：");
            errors.forEach(error -> sender.sendMessage("  §c- " + error));
        }
    }

    // ------------------------------------------------------------------ status

    /** /rad status [player]：全链路口径预览（DayNightProvider + 露天/腐蚀/减免/预计伤害，只读）。 */
    private void handleStatus(CommandSender sender, String[] args) {
        RadiationServices services = plugin.services();
        if (services == null) {
            notReady(sender);
            return;
        }
        PluginConfig cfg = services.config();
        if (!(sender instanceof Player) && args.length < 2) {
            sendConsoleSummary(sender, cfg, services);
            return;
        }
        Player target = resolveTarget(sender, args);
        if (target == null) {
            return;
        }
        RoundOutcome preview = services.radiationTask().previewRound(target);
        World world = target.getWorld();
        boolean enabledHere = cfg.enabledWorlds().contains(world.getName().toLowerCase(Locale.ROOT));
        PluginConfig.CorrosionConfig cor = cfg.corrosion();

        CommandOutput out = CommandOutput.of(sender,
                "目标玩家", "世界", "生效维度", "昼夜阶段", "露天判定", "盔甲减免", "最低杀伤",
                "抗尘免疫", "当前腐蚀", "本轮结果", "关键配置");
        out.header("RadSurvival status");

        out.section("会话");
        out.kv("目标玩家", "§e" + target.getName() + " §8(§7" + target.getGameMode() + "§8)");
        out.kv("世界", "§e" + world.getName() + " §8(" + world.getEnvironment() + ") "
                + (enabledHere ? "§a白名单内" : "§c不在白名单"));
        out.kv("生效维度", "§e" + cfg.dimensions());

        // bug hunt P1-4：盔甲减免 / 最低杀伤 / 预计伤害属于「他人防护情报」。
        // 非管理员查询他人时隐藏这三项——PVP 服上这等于免费侦察对手装备，
        // 而 /rad status 本身没有任何权限门槛。
        boolean canSeeEquipment = canInspectOthers(sender) || sender == target;

        out.section("判定");
        out.kv("昼夜阶段", "§e" + preview.phase().displayName()
                + " §8timeFactor=§e" + CommandOutput.fixed(preview.timeFactor(), 3)
                + "§8（smooth=" + cfg.smoothTransition() + "）");
        out.kv("露天判定", preview.exposed() ? "§e露天（头顶可见天空）" : "§a遮蔽/豁免维度（安全）");
        int dustSeconds = services.dustImmunity().remainingSeconds(target.getUniqueId());
        // v1.2：免疫在窗口内会盖掉等级条（与 HUD 的 ◆抗尘 同口径），
        // 否则玩家在免疫中看到「▲▲▲▲▲」会以为马上要掉血。
        out.kv("辐射等级", dustSeconds > 0
                ? "§b" + cfg.antiDust().hudImmuneMark() + " §8（免疫窗口内，本轮零伤害、腐蚀不累积）"
                : preview.exposed()
                    ? CommandOutput.exposureBar(preview.timeFactor())
                        + " §8（HUD 同口径，按 timeFactor 取 ▲）"
                    : "§a● 安全 §8（遮蔽/豁免维度）");
        // v1.2：护甲不再有「完全免疫」分支，改为展示「满配上限」与「最低杀伤保底」
        // 注意 CommandOutput.percent 收「比例」（0.72 → "72%"），不要再乘 100
        out.kv("盔甲减免", canSeeEquipment
                ? "§e" + CommandOutput.percent(preview.armorReduction())
                    + " §8（最强材质满配上限 " + CommandOutput.percent(cfg.armor().maxFullSetReduction()) + "）"
                : "§8— §7（他人装备细节仅管理员可见）");
        out.kv("最低杀伤", canSeeEquipment
                ? "§e" + CommandOutput.percent(cfg.armor().minDamageFraction())
                    + " §8（护甲再厚也保底吃这一份）"
                : "§8— §7（他人装备细节仅管理员可见）");
        out.kv("抗尘免疫", dustSeconds > 0
                ? "§b" + dustSeconds + "s §8（" + cfg.antiDust().hudImmuneMark() + "）"
                : "§8—");

        out.section("腐蚀");
        out.kv("当前腐蚀", CommandOutput.corrosionBar(preview.corrosionAfter(),
                        cor.stageWarn(), cor.stageDanger())
                + "§8（倍率 ×" + CommandOutput.fixed(corrosionMult(preview.corrosionAfter(), cfg), 3)
                + "，曲线 " + cor.curve().name().toLowerCase(Locale.ROOT) + "）");

        out.section("本轮");
        out.verdict(verdictMark(preview),
                "§f" + preview.result().describe() + " §8| §f伤害 "
                        + (canSeeEquipment ? damageText(preview) : "§8—")
                        + (canSeeEquipment && preview.result() == RoundResult.DAMAGED
                            ? " §8（预计本轮结算，未落地）" : ""));

        out.section("关键配置");
        out.kv("关键配置", "§7tick=" + cfg.tickInterval()
                + " §8base=" + CommandOutput.fmt(cfg.baseDamage())
                + " §8night×" + CommandOutput.fmt(cfg.nightMultiplier())
                + " §8onGround=" + cfg.requireOnGround()
                + " §8腐蚀=" + (cor.enabled() ? "开" : "关")
                + " §8accum=" + CommandOutput.fmt(cor.dayAccumPerSec()) + "%/s"
                + " §8regen=" + CommandOutput.fmt(cor.regenPerSec()) + "%/s"
                + " §8max×" + CommandOutput.fmt(cor.maxMultiplier())
                + (cor.decayOnDeath() == null ? "" : " §8decay=" + CommandOutput.fmt(cor.decayOnDeath())));
    }

    /**
     * 控制台摘要从简输出：配置快照 + 各启用世界昼夜（只读，不触发结算）。
     * 键名左对齐成两列，便于后台 grep/肉眼扫读。
     */
    private void sendConsoleSummary(CommandSender sender, PluginConfig cfg, RadiationServices services) {
        PluginConfig.CorrosionConfig cor = cfg.corrosion();
        CommandOutput out = CommandOutput.of(sender,
                "config", "platform", "corrosion", "online", "worlds", "tick-interval", "base-damage",
                "night-multiplier", "require-on-ground", "smooth-transition", "enabled-worlds",
                "dimensions", "async-scan");

        out.header("RadSurvival status · 控制台摘要");
        out.kv("config", "§7" + services.describeFlags());
        // v1.4 §7：品牌识别结论 + 识别来源（透明化；无 profiles 预设，故不展示「生效键/覆写键」）
        ServerBrand.Detection det = plugin.brand();
        out.kv("platform", det == null
                ? "§8— §7（未识别或插件未启用）"
                : "§e" + det.describe()
                        + (det.brand() == ServerBrand.Brand.UNKNOWN ? " §7（未实测分支，按 Paper 同面运行）" : ""));
        out.kv("tick-interval", "§e" + cfg.tickInterval()
                + " §8（tick-interval= 结算间隔，≥20 = 无敌帧下限，§1.3）");
        out.kv("base-damage", "§e" + CommandOutput.fmt(cfg.baseDamage())
                + " §8/轮  §7night-multiplier=" + CommandOutput.fmt(cfg.nightMultiplier())
                + " §7smooth-transition=" + cfg.smoothTransition()
                + " §7require-on-ground=" + cfg.requireOnGround());
        out.kv("worlds", "§7" + cfg.enabledWorlds() + " §8@ " + cfg.dimensions());
        out.kv("async-scan", "§7" + (cfg.asyncExposureScan() ? "已启用（段级异步补扫）" : "关闭（全同步）"));
        out.kv("corrosion", "§7腐蚀：" + (cor.enabled() ? "开" : "关")
                + " §8accum=" + CommandOutput.fmt(cor.dayAccumPerSec()) + "%/s"
                + " §8regen=" + CommandOutput.fmt(cor.regenPerSec()) + "%/s"
                + " §8armor-affects-accum=" + cor.armorAffectsAccum()
                + " §8max×" + CommandOutput.fmt(cor.maxMultiplier())
                + " §8curve=" + cor.curve().name().toLowerCase(Locale.ROOT)
                + " §8stages=" + CommandOutput.fmt(cor.stageWarn()) + "/" + CommandOutput.fmt(cor.stageDanger()));
        out.kv("online", "§e" + Bukkit.getOnlinePlayers().size() + " §7名（离线不结算、腐蚀冻结）");

        List<World> worlds = Bukkit.getWorlds().stream()
                .filter(world -> cfg.enabledWorlds().contains(world.getName().toLowerCase(Locale.ROOT)))
                .toList();
        if (worlds.isEmpty()) {
            out.line("当前无已加载且在白名单内的世界（白名单：" + cfg.enabledWorlds() + "）。");
            return;
        }
        out.section("各世界昼夜");
        for (World world : worlds) {
            long time = world.getTime();
            DayNightProvider.Phase phase = DayNightProvider.phaseOfDayTime(time);
            double timeFactor = DayNightProvider.timeFactorOfDayTime(
                    time, cfg.nightMultiplier(), cfg.smoothTransition());
            out.line("§8" + world.getName() + " §7(" + world.getEnvironment() + ") §8time=" + time
                    + " §e" + phase.displayName() + " §8timeFactor=" + CommandOutput.fixed(timeFactor, 3));
        }
    }

    // -------------------------------------------------------------- force-check

    /** /rad force-check [player]：立即对目标跑一轮真实结算（与主循环同一路径）。 */
    private void handleForceCheck(CommandSender sender, String[] args) {
        RadiationServices services = plugin.services();
        if (services == null) {
            notReady(sender);
            return;
        }
        // bug hunt P1-3：force-check 走的是**真实结算**（步进腐蚀 + 广播事件 + 真实掉血 + 写 PDC），
        // 不是只读预览。若对所有人开放，玩家连按命令就能把结算节律从「每 tick-interval」
        // 变成「每次按键」，腐蚀推进速度完全由他自己决定。因此要求管理权限——
        // 与 corrosion set/add 同级，因为它们是同一类「改写玩家辐射状态」的操作。
        // 普通玩家需要自查时用 /rad status（只读、零副作用），help 里已如此指引。
        if (!sender.hasPermission(PERMISSION_ADMIN)) {
            sender.sendMessage("§c缺少权限：" + PERMISSION_ADMIN
                    + "§7（§8/rad force-check 会真实结算并改写腐蚀，属管理操作；自查请用 §f/rad status§7）");
            return;
        }
        Player target = resolveTarget(sender, args);
        if (target == null) {
            return;
        }
        RoundOutcome outcome = services.radiationTask().settleRound(target);

        CommandOutput out = CommandOutput.of(sender, "目标玩家", "露天判定", "盔甲减免", "腐蚀", "结算结论");
        out.header("/rad force-check：" + target.getName());
        out.kv("露天判定", outcome.exposed() ? "§a露天" : "§7遮蔽/豁免维度");
        out.kv("盔甲减免", "§e" + CommandOutput.percent(outcome.armorReduction()));
        out.kv("腐蚀", "§e" + CommandOutput.fixed(outcome.corrosionBefore(), 1) + "% → "
                + CommandOutput.fixed(outcome.corrosionAfter(), 1) + "%");
        out.kv("结算结论", "§e" + outcome.result().describe());
        out.verdict(verdictMark(outcome), settlementText(outcome));
    }

    /** 结算附加说明（实际掉血 / 预计 / 被事件拦下三者之一）。 */
    private static String settlementText(RoundOutcome outcome) {
        if (outcome.result() == RoundResult.DAMAGED) {
            return outcome.damageApplied()
                    ? "§a本轮实际掉血 §e" + CommandOutput.fixed(outcome.damage(), 3) + "§a 点（damage() 已按稳定 API 结算）"
                    : "§f本轮预计掉血 §e" + CommandOutput.fixed(outcome.damage(), 3) + "§f 点";
        }
        if (outcome.result() == RoundResult.CANCELLED_BY_EVENT) {
            return "§e本轮伤害被 RadiationExposureEvent 监听器取消/覆写为 ≤0，未掉血。";
        }
        return "§7本轮未产生伤害（" + outcome.phase().displayName() + "）。";
    }

    // ---------------------------------------------------------------- corrosion

    /** /rad corrosion get|set|add <player> [值]（权限 radsurvival.admin，§1.6 管理命令）。 */
    private void handleCorrosion(CommandSender sender, String[] args) {
        RadiationServices services = plugin.services();
        if (services == null) {
            notReady(sender);
            return;
        }
        if (!sender.hasPermission(PERMISSION_ADMIN)) {
            sender.sendMessage("§c缺少权限：" + PERMISSION_ADMIN);
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§7用法: /rad corrosion <get|set|add> <player> [值]（set/add 结果钳 [0,100]）");
            return;
        }
        String op = args[1].toLowerCase(Locale.ROOT);
        if (!CORROSION_OPS.contains(op)) {
            sender.sendMessage("§c未知操作：" + args[1] + "（可用 get / set / add）");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§7用法: /rad corrosion " + op + " <player> [值]");
            return;
        }
        Player target = Bukkit.getPlayer(args[2]);
        if (target == null) {
            sender.sendMessage("§c玩家不在线，无法操作腐蚀条（离线冻结值随 player.dat 持久化，重新上线后可见）：" + args[2]);
            return;
        }
        PluginConfig.CorrosionConfig cor = services.config().corrosion();
        switch (op) {
            case "get" -> {
                double value = plugin.api().getCorrosion(target);
                Double pdcRaw = target.getPersistentDataContainer()
                        .get(CorrosionMeter.CORROSION_PDC_KEY, PersistentDataType.DOUBLE);
                CommandOutput out = CommandOutput.of(sender, "内存权威", "PDC 落盘");
                out.header("腐蚀条 · " + target.getName());
                out.kv("内存权威", CommandOutput.corrosionBar(value, cor.stageWarn(), cor.stageDanger())
                        + "§8（倍率 ×" + CommandOutput.fixed(
                                corrosionMult(value, services.config()), 3) + "）");
                out.kv("PDC 落盘", (pdcRaw == null
                        ? "§7未写入（默认 0.0）"
                        : CommandOutput.corrosionBar(pdcRaw, cor.stageWarn(), cor.stageDanger())));
                out.line("key " + CorrosionMeter.PDC_KEY_STRING + " · 内存权威为结算口径，PDC 随 player.dat 落盘");
            }
            case "set", "add" -> {
                if (args.length < 4) {
                    sender.sendMessage("§c缺少数值参数：/rad corrosion " + op + " " + args[2] + " <数值>");
                    return;
                }
                double delta;
                try {
                    delta = Double.parseDouble(args[3].trim());
                } catch (NumberFormatException ex) {
                    sender.sendMessage("§c非法数值（期望可解析的 double，add 可为负）：" + args[3]);
                    return;
                }
                if (Double.isNaN(delta) || Double.isInfinite(delta)) {
                    sender.sendMessage("§c非法数值（不允许 NaN/Infinity）：" + args[3]);
                    return;
                }
                double before = plugin.api().getCorrosion(target);
                double after = op.equals("set")
                        ? plugin.api().setCorrosion(target, delta)   // 内部钳 [0,100] + PDC + 镜像
                        : plugin.api().addCorrosion(target, delta);  // add 支持负数，同样钳制
                boolean clamped = op.equals("set")
                        ? Math.abs(delta - after) > 1e-9
                        : Math.abs(before + delta - after) > 1e-9;

                CommandOutput out = CommandOutput.of(sender, "变更", "结果");
                out.header("腐蚀条 · " + target.getName());
                out.kv("变更", "§7" + op + " §e" + (op.equals("add") && delta >= 0 ? "+" : "")
                        + CommandOutput.fmt(delta) + " §8（" + CommandOutput.fixed(before, 2)
                        + "% → §f" + CommandOutput.fixed(after, 2) + "%§8）");
                out.kv("结果", CommandOutput.corrosionBar(after, cor.stageWarn(), cor.stageDanger())
                        + (clamped ? " §c（已钳制）" : ""));
                out.line("已同步内存态 / 状态镜像 / PDC：" + CorrosionMeter.PDC_KEY_STRING);
            }
            default -> sender.sendMessage("§c未知操作：" + args[1]);
        }
    }

    // ------------------------------------------------------------------- help

    /**
     * /rad help [子命令]：子命令列表按发送者权限裁剪；附带当前生效的关键配置一行，
     * 让管理员不看 config.yml 也能确认环境（沙箱/正式服差异常见坑）。
     */
    private void sendHelp(CommandSender sender, String label, String topic) {
        if (topic != null && !topic.isBlank()) {
            SubCommand sub = SubCommand.parse(topic);
            if (sub == null) {
                unknownSub(sender, label, topic);
                return;
            }
            sender.sendMessage("§6—— " + sub.usage() + " ——");
            sender.sendMessage("  §7" + sub.summary());
            sender.sendMessage("  §7权限：§f"
                    + (sub.permission() != null ? sub.permission() : permissionHint(sub)));
            return;
        }
        List<SubCommand> visible = new ArrayList<>();
        for (SubCommand sub : SubCommand.values()) {
            if (sub == SubCommand.CORROSION && !sender.hasPermission(PERMISSION_ADMIN)) {
                continue;
            }
            if (sub == SubCommand.DUST && !sender.hasPermission(PERMISSION_ADMIN)) {
                continue;
            }
            visible.add(sub);
        }
        int width = 0;
        for (SubCommand sub : visible) {
            width = Math.max(width, CommandOutput.displayWidth(sub.commandName()));
        }
        sender.sendMessage("§6—— RadSurvival 命令（/" + label + "）——");
        for (SubCommand sub : visible) {
            String name = sub.commandName();
            String padding = " ".repeat(Math.max(0, width - CommandOutput.displayWidth(name)));
            sender.sendMessage("  §e/" + label + " " + name + padding + " §8" + sub.summary());
        }
        sender.sendMessage("  §7/§e" + label + " §7[子命令] --help 查看用法；子命令支持简写 "
                + "§8(" + aliasHint() + ")");
        PluginConfig cfg = plugin.config();
        if (cfg != null) {
            sender.sendMessage("  §7当前：§ftick=" + cfg.tickInterval()
                    + " §7base=" + CommandOutput.fmt(cfg.baseDamage())
                    + " §7night×" + CommandOutput.fmt(cfg.nightMultiplier())
                    + " §7腐蚀=" + (cfg.corrosion().enabled() ? "开" : "关")
                    + " §7世界=" + cfg.enabledWorlds());
        }
    }

    private static String permissionHint(SubCommand sub) {
        return switch (sub) {
            case RELOAD -> "commands.reload-permission（默认 radsurvival.reload）";
            case CHECK -> "无需管理权限（操作自身角色）";
            default -> "radsurvival.use（默认所有玩家）";
        };
    }

    private static String aliasHint() {
        List<String> pairs = new ArrayList<>();
        for (SubCommand sub : SubCommand.values()) {
            if (!sub.aliases().isEmpty()) {
                pairs.add(String.join("/", sub.aliases()) + " → " + sub.commandName());
            }
        }
        return String.join("，", pairs);
    }

    // -------------------------------------------------------------------- dust

    /**
     * /rad dust &lt;list|give|help&gt;（v1.2 新增）。
     *
     * <p>三个动作的分工：{@code list} 看当前配置的档位表与作物归属（服主调参时的核对手段），
     * {@code give} 直接把药水发给玩家（省去搭结构的调试成本），{@code help} 讲清结构怎么摆。</p>
     */
    private void handleDust(CommandSender sender, String[] args) {
        PluginConfig cfg = plugin.config();
        if (cfg == null) {
            notReady(sender);
            return;
        }
        if (!sender.hasPermission(PERMISSION_ADMIN)) {
            sender.sendMessage("§c缺少权限：" + PERMISSION_ADMIN);
            return;
        }
        String op = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (op) {
            case "help" -> {
                sender.sendMessage("§6—— 抗尘埃药制作结构 ——");
                for (String line : DustCraftGui.structureHelp()) {
                    sender.sendMessage("  §7· §f" + line);
                }
                sender.sendMessage("  §8核心方块也接受投掷器；铁砧可用损坏/上蜡变体");
            }
            case "give" -> giveDust(sender, cfg, args);
            case "list" -> listDust(sender, cfg);
            default -> sender.sendMessage("§7用法: /rad dust <list|give|help>");
        }
    }

    /** 列出档位表（按配置顺序 = 由强到弱）与认领的作物。 */
    private void listDust(CommandSender sender, PluginConfig cfg) {
        PluginConfig.AntiDustConfig dust = cfg.antiDust();
        if (!dust.enabled()) {
            sender.sendMessage("§7抗尘埃药当前为 §c关闭 §7状态（anti_dust.enabled=false）");
            return;
        }
        sender.sendMessage("§6—— 抗尘埃药档位（由强到弱，取首个匹配）——");
        for (PluginConfig.DustLevel level : dust.levels()) {
            sender.sendMessage("  §f" + level.displayName() + " §8[" + level.id() + "]"
                    + " §7−" + CommandOutput.fmt(level.corrosionReduction()) + "% §8/"
                    + " §b" + level.immuneSeconds() + "s §8· §7作物 "
                    + String.join(", ", level.items()));
        }
        sender.sendMessage("  §7冷却 §f" + dust.cooldownSeconds() + "s"
                + " §7解毒 §f" + String.join(", ", dust.cures())
                + " §7HUD §f" + dust.hudImmuneMark());
    }

    /** 发放药水：/rad dust give &lt;level&gt; [player]，level 支持档位 id 或物品名。 */
    private void giveDust(CommandSender sender, PluginConfig cfg, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§7用法: /rad dust give <档位id> [player]");
            return;
        }
        if (!cfg.antiDust().enabled()) {
            sender.sendMessage("§c抗尘埃药未启用（anti_dust.enabled=false）");
            return;
        }
        PluginConfig.DustLevel level = cfg.antiDust().levelById(args[2]);
        if (level == null) {
            // 容错：允许用品名/作物名来找档位（服主临时发放时更顺手）
            level = cfg.antiDust().levelFor(args[2].toUpperCase(Locale.ROOT));
        }
        if (level == null) {
            sender.sendMessage("§c未知档位：" + args[2] + " §7可选："
                    + String.join("/", cfg.antiDust().levels().stream()
                            .map(PluginConfig.DustLevel::id).toList()));
            return;
        }
        Player target = resolveTargetForward(sender, args);
        if (target == null) {
            return;
        }
        com.ft.radsurvival.item.DustItemFactory factory = plugin.dustItems();
        if (factory == null) {
            sender.sendMessage("§c物品工厂未装配（插件可能尚未启用完成）");
            return;
        }
        var overflow = target.getInventory().addItem(factory.create(level, 1));
        for (var rest : overflow.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), rest);
        }
        sender.sendMessage("§a已发放 §f" + level.displayName() + " §7→ §f" + target.getName());
        if (!sender.equals(target)) {
            target.sendMessage("§8[§a抗尘§8] §7你获得了一瓶 §f" + level.displayName());
        }
    }

    /** dust give 的目标解析：玩家名在第 3 位而非第 1 位。 */
    private Player resolveTargetForward(CommandSender sender, String[] args) {
        if (args.length >= 4) {
            Player target = Bukkit.getPlayer(args[3]);
            if (target == null) {
                sender.sendMessage("§c未找到在线玩家：" + args[3]);
                return null;
            }
            return target;
        }
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage("§c控制台执行必须指定玩家：/rad dust give <档位id> <player>");
        return null;
    }

    /** 未知子命令：给用法 + 最近似建议（避免「打错一个字全无反馈」）。 */
    private void unknownSub(CommandSender sender, String label, String token) {
        String suggestion = SubCommand.suggest(token);
        sender.sendMessage("§c未知子命令：" + token
                + (suggestion == null ? "" : " §7（你是不是想用 §e" + suggestion + "§7？）"));
        sender.sendMessage("§7用法: /" + label + " help");
    }

    // ------------------------------------------------------------------- util

    /** 目标解析：显式玩家名优先；console 必须显式指定。 */
    private Player resolveTarget(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            Player target = Bukkit.getPlayer(args[1]); // 精确名 → 在线前缀匹配
            if (target == null) {
                sender.sendMessage("§c未找到在线玩家：" + args[1]);
                return null;
            }
            return target;
        }
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage("§c控制台执行必须指定玩家：/rad " + args[0].toLowerCase(Locale.ROOT) + " <player>");
        return null;
    }

    private static void notReady(CommandSender sender) {
        sender.sendMessage("§c插件未处于启用状态（配置非法或服务已停用）。");
    }

    /** 状态徽标：✔ 已结算 / ◆ 抗尘免疫 / ● 安全 / ▲ 未结算（v1.2：◆ 现在专指抗尘埃药窗口）。 */
    private static String verdictMark(RoundOutcome outcome) {
        return switch (outcome.result()) {
            case DAMAGED -> "§a✔";
            case IMMUNE -> "§b◆";
            case COVERED -> "§a●";
            case CANCELLED_BY_EVENT -> "§c✖";
            default -> "§7▲";
        };
    }

    private static String damageText(RoundOutcome preview) {
        return switch (preview.result()) {
            case DAMAGED -> "§e" + CommandOutput.fixed(preview.damage(), 3) + " §7点（预计本轮结算，未落地）";
            case IMMUNE -> "§a0.000 §7（免疫：抗尘埃药窗口内，v1.2）";
            case COVERED -> "§a0.000 §7（遮蔽/豁免维度不受伤）";
            case ZERO_DAMAGE -> "§a0.000 §7（公式伤害 <= 0）";
            default -> "§7—  §7（本轮不结算）";
        };
    }

    private static double corrosionMult(double corrosion, PluginConfig cfg) {
        return cfg.corrosion().enabled()
                ? RadiationMath.corrosionMultiplier(
                        corrosion, cfg.corrosion().maxMultiplier(), cfg.corrosion().curve())
                : 1.0;
    }

    private static List<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(p))
                .collect(Collectors.toList());
    }

    /** 表格/说明里使用的紧凑数字（保留整数无尾零）。 */
    private static String fmt(double value) {
        return CommandOutput.fmt(value);
    }
}
