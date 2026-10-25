package com.ft.radsurvival.command;

import java.util.List;
import java.util.Locale;

/**
 * /rad 子命令注册表（命令层重构）：别名、用法、权限、TabComplete 提示全部集中在此，
 * 路由与补全不再散落各 handle* 方法。
 *
 * <p>排序即 help 展示顺序。别名匹配与 Tab 补全共用 {@link #matches(String)}，
 * 保证「能补全出来的都能执行」。</p>
 */
enum SubCommand {

    RELOAD("reload", List.of("rl"),
            "重载 config.yml（--dry-run 只校验不应用）",
            "/rad reload [--dry-run]", null),

    STATUS("status", List.of("st", "info"),
            "只读预览一轮判定（不结算、不写数据）",
            "/rad status [player]", null),

    CHECK("force-check", List.of("check", "fc"),
            "立即跑一轮真实结算（与主循环同一路径）",
            "/rad force-check [player]", null),

    CORROSION("corrosion", List.of("cor", "c"),
            "腐蚀条查询/设定/增减（值域钳 [0,100]）",
            "/rad corrosion <get|set|add> <player> [值]", RadCommand.PERMISSION_ADMIN),

    DUST("dust", List.of("d", "anti-dust"),
            "抗尘埃药：列出档位 / 发放药水 / 查看制作结构",
            "/rad dust <list|give|help>", RadCommand.PERMISSION_ADMIN),

    HELP("help", List.of("?", "h"),
            "显示本帮助", "/rad help [子命令]", null);

    private final String name;
    private final List<String> aliases;
    private final String summary;
    private final String usage;
    /** 执行所需权限（null = 按功能自身口径，如 reload 走配置项、force-check 无需管理权限）。 */
    private final String permission;

    SubCommand(String name, List<String> aliases, String summary, String usage, String permission) {
        this.name = name;
        this.aliases = aliases;
        this.summary = summary;
        this.usage = usage;
        this.permission = permission;
    }

    String commandName() {
        return name;
    }

    List<String> aliases() {
        return aliases;
    }

    String summary() {
        return summary;
    }

    String usage() {
        return usage;
    }

    String permission() {
        return permission;
    }

    /** 主名 + 全部别名（Tab 补全候选）。 */
    List<String> allNames() {
        List<String> all = new java.util.ArrayList<>(aliases.size() + 1);
        all.add(name);
        all.addAll(aliases);
        return all;
    }

    /** 精确匹配主名或任一别名（大小写不敏感）。 */
    boolean matches(String token) {
        if (token == null) {
            return false;
        }
        String t = token.toLowerCase(Locale.ROOT);
        if (t.equals(name)) {
            return true;
        }
        for (String alias : aliases) {
            if (t.equals(alias)) {
                return true;
            }
        }
        return false;
    }

    /** 解析子命令 token；无匹配返回 null（调用方给用法提示 + 最近似建议）。 */
    static SubCommand parse(String token) {
        for (SubCommand sub : values()) {
            if (sub.matches(token)) {
                return sub;
            }
        }
        return null;
    }

    /** 最近似的子命令名（Levenshtein，仅用于「你是不是想输入…」提示）。 */
    static String suggest(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (SubCommand sub : values()) {
            for (String candidate : sub.allNames()) {
                int d = distance(token.toLowerCase(Locale.ROOT), candidate);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = candidate;
                }
            }
        }
        // 距离 2 以内才提示，避免噪音
        return bestDistance <= 2 ? best : null;
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[b.length()];
    }
}
