package com.ft.radsurvival.mockbukkit;

import java.util.List;

import org.bukkit.GameMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.command.RadCommand;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /rad 命令层集成测试（plan.md §2 命令、§1.6 管理命令、M2b status/force-check 口径）。
 *
 * <p>伤害/露天链路用 Support 假装配（同主循环测试）；corrosion 与纯读路径走生产装配。</p>
 */
class RadCommandMockBukkitTest {

    private ServerMock server;
    private RadSurvivalPlugin plugin;
    private Support.Fixture fixture;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RadSurvivalPlugin.class);
        fixture = Support.install(plugin, server);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** 抽取 MessageTarget（玩家/控制台）当前全部剩余消息。 */
    private static List<String> drain(org.mockbukkit.mockbukkit.command.MessageTarget target) {
        List<String> out = new java.util.ArrayList<>();
        String m;
        while ((m = target.nextMessage()) != null) {
            out.add(m);
        }
        return out;
    }

    private static String join(List<String> messages) {
        return String.join("\n", messages);
    }

    // ------------------------------------------------------------------ status

    @Test
    @DisplayName("status：阶段/timeFactor 走 DayNightProvider，露天/腐蚀/减免/预计伤害全展示（夜晚硬口径 0.5）")
    void statusShowsFullSnapshot() {
        PlayerMock admin = server.addPlayer("Adm");
        admin.setOp(true);
        admin.getWorld().setTime(13000L);
        fixture.meter().setValue(admin.getUniqueId(), 20.0);

        admin.performCommand("rad status");
        String out = join(drain(admin));
        assertThat(out)
                .contains("RadSurvival status")
                .contains("夜晚")                       // DayNightProvider 阶段名
                .contains("0.500")                      // timeFactor
                .contains("露天（头顶可见天空）")
                .contains("20.00%")                     // 当前腐蚀（管理读数保留两位小数）
                .contains("预计本轮结算");
        // 只读：无任何副作用
        assertThat(fixture.damages).isEmpty();
        assertThat(fixture.events).isEmpty();
        assertThat(fixture.corrosion(admin)).isEqualTo(20.0);
    }

    @Test
    @DisplayName("status 免疫与遮蔽原因可见：下界合金报免疫、exposed=false 报遮蔽")
    void statusExplainsImmuneAndCovered() {
        PlayerMock tank = server.addPlayer("Tank");
        tank.setOp(true);
        Support.equipFullSet(tank, "NETHERITE");
        tank.performCommand("rad status");
        assertThat(join(drain(tank))).contains("完全免疫");

        fixture.exposed.set(false);
        tank.performCommand("rad status");
        assertThat(join(drain(tank))).contains("遮蔽");
    }

    @Test
    @DisplayName("status 控制台无参输出摘要；force-check 控制台必须指定玩家；未知玩家报错")
    void consoleTargetingRules() {
        org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock console = server.getConsoleSender();
        server.dispatchCommand(console, "rad status");
        String summary = join(drain(console));
        assertThat(summary).contains("控制台摘要").contains("tick-interval=").contains("腐蚀：");

        server.dispatchCommand(console, "rad force-check");
        assertThat(join(drain(console))).contains("控制台执行必须指定玩家");

        server.dispatchCommand(console, "rad status Nobody");
        assertThat(join(drain(console))).contains("未找到在线玩家");
    }

    // -------------------------------------------------------------- force-check

    @Test
    @DisplayName("force-check：真实走 DamageApplier 路径——事件广播、掉血、报告实际伤害")
    void forceCheckSettlesThroughSamePath() {
        PlayerMock admin = server.addPlayer("Checker");
        admin.setOp(true);
        Support.resetCombat(fixture, server);

        admin.performCommand("rad force-check");
        String out = join(drain(admin));
        assertThat(out).contains("force-check").contains("本轮实际掉血");
        assertThat(fixture.events).hasSize(1);
        assertThat(fixture.damages).hasSize(1);
        assertThat(admin.getHealth()).isLessThan(20.0);
        assertThat(fixture.corrosion(admin)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("force-check 免疫/跳过原因明确上报（创造模式与下界合金两种）")
    void forceCheckReportsSkipReasons() {
        PlayerMock admin = server.addPlayer("Why");
        admin.setOp(true);

        PlayerMock creative = server.addPlayer("CC");
        creative.setGameMode(GameMode.CREATIVE);
        admin.performCommand("rad force-check CC");
        assertThat(join(drain(admin))).contains("创造/旁观模式无条件免疫");

        PlayerMock tank = server.addPlayer("TT");
        Support.equipFullSet(tank, "NETHERITE");
        admin.performCommand("rad force-check TT");
        assertThat(join(drain(admin))).contains("完全免疫");
        assertThat(fixture.damages).isEmpty();
    }

    // ---------------------------------------------------------------- corrosion

    @Test
    @DisplayName("corrosion get/set/add：钳制、负增量、内存+PDC 同步（admin 权限线）")
    void corrosionCommandsManageAndSync() {
        PlayerMock admin = server.addPlayer("Corr");
        admin.setOp(true);
        PlayerMock victim = server.addPlayer("Victim");

        admin.performCommand("rad corrosion set Victim 150");
        assertThat(join(drain(admin))).contains("100.00").contains("已钳制");
        assertThat(plugin.services().corrosionMeter().get(victim.getUniqueId())).isEqualTo(100.0);
        assertThat(victim.getPersistentDataContainer()
                .get(com.ft.radsurvival.core.CorrosionMeter.CORROSION_PDC_KEY,
                        org.bukkit.persistence.PersistentDataType.DOUBLE))
                .as("set 后同步 PDC").isEqualTo(100.0);
        assertThat(plugin.radiationTask().stateOf(victim.getUniqueId()).getCorrosion())
                .as("set 后同步状态镜像").isEqualTo(100.0);

        admin.performCommand("rad corrosion add Victim -30");
        assertThat(join(drain(admin))).contains("70.00");
        assertThat(plugin.services().corrosionMeter().get(victim.getUniqueId())).isEqualTo(70.0);

        admin.performCommand("rad corrosion get Victim");
        assertThat(join(drain(admin))).contains("70.00");

        admin.performCommand("rad corrosion add Victim -999");
        assertThat(join(drain(admin))).contains("0.00"); // 负增量钳到 0
        assertThat(plugin.services().corrosionMeter().get(victim.getUniqueId())).isZero();
    }

    @Test
    @DisplayName("corrosion 权限与错误处理：无权限拒绝、离线玩家报错、非法数值报错")
    void corrosionGuardrails() {
        PlayerMock mod = server.addPlayer("Mod");
        mod.setOp(true);
        PlayerMock nobody = server.addPlayer("Gone");

        // 非 admin（default op）玩家执行 → 拒绝
        PlayerMock plain = server.addPlayer("Plain");
        plain.performCommand("rad corrosion get Mod");
        assertThat(join(drain(plain))).contains("缺少权限");

        nobody.disconnect();
        mod.performCommand("rad corrosion get Gone");
        assertThat(join(drain(mod))).contains("玩家不在线");

        mod.performCommand("rad corrosion set Mod abc");
        String lastOut = join(drain(mod));
        assertThat(lastOut)
                .doesNotContain("→ 0.00") // 未被当作合法 0 处理
                .contains("非法数值");
    }

    @Test
    @DisplayName("corrosion get 同时展示内存权威与 PDC 落盘值")
    void corrosionGetShowsBothSources() {
        PlayerMock admin = server.addPlayer("Both");
        admin.setOp(true);
        PlayerMock victim = server.addPlayer("Shown");
        plugin.api().setCorrosion(victim, 12.5);
        admin.performCommand("rad corrosion get Shown");
        assertThat(join(drain(admin))).contains("12.50").contains("内存权威").contains("PDC");
    }

    // ------------------------------------------------------------ tab complete

    @Test
    @DisplayName("TabComplete：子命令（含别名）、corrosion 操作/玩家名/数值提示逐级跟进")
    void tabCompletions() {
        PlayerMock admin = server.addPlayer("Tabby");
        admin.setOp(true);
        org.bukkit.command.PluginCommand rad = plugin.getCommand("rad");
        // onTabComplete 为纯函数路由，直接构造 handler 断言（不依赖 mock 的 TabCompleter 存取）
        RadCommand handler = new RadCommand(plugin);
        // 主名 + 别名都可补全（别名在 commandName 之后，保持列表顺序稳定）
        assertThat(handler.onTabComplete(admin, rad, "rad", new String[]{"cor"}))
                .contains("corrosion")
                .allMatch(name -> name.startsWith("cor"));
        assertThat(handler.onTabComplete(admin, rad, "rad", new String[]{"corrosion", ""}))
                .containsExactlyInAnyOrder("get", "set", "add");
        assertThat(handler.onTabComplete(admin, rad, "rad", new String[]{"corrosion", "set", "Ta"}))
                .containsExactly("Tabby");
        List<String> valueHints = handler.onTabComplete(admin, rad, "rad",
                new String[]{"corrosion", "set", "Tabby", ""});
        assertThat(valueHints).contains("0", "50", "100");
        List<String> addHints = handler.onTabComplete(admin, rad, "rad",
                new String[]{"corrosion", "add", "Tabby", "-"});
        assertThat(addHints).contains("-10", "-25", "-50");
        assertThat(handler.onTabComplete(admin, rad, "rad", new String[]{"force-check", "Ta"}))
                .containsExactly("Tabby");
        assertThat(handler.onTabComplete(admin, rad, "rad", new String[]{"reload", ""}))
                .containsExactly("--dry-run");
    }

    // --------------------------------------------------------------- 命令层优化

    @Test
    @DisplayName("help：无参列出子命令；help <子命令> 给出用法与权限；普通玩家看不到 corrosion")
    void helpListsSubcommandsByPermission() {
        org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock console = server.getConsoleSender();
        server.dispatchCommand(console, "rad");
        String out = join(drain(console));
        assertThat(out)
                .contains("RadSurvival 命令")
                .contains("/rad reload")
                .contains("/rad status")
                .contains("/rad force-check")
                .contains("/rad corrosion")
                .contains("help");

        server.dispatchCommand(console, "rad help corrosion");
        assertThat(join(drain(console))).contains("/rad corrosion <get|set|add>").contains("radsurvival.admin");

        PlayerMock plain = server.addPlayer("PlainHelp");
        plain.performCommand("rad help");
        assertThat(join(drain(plain)))
                .contains("/rad status")
                .doesNotContain("/rad corrosion");
    }

    @Test
    @DisplayName("别名路由：st/rl/fc/c 与 dry-run 均可用，等价于主名")
    void aliasesRouteToSameHandlers() {
        PlayerMock admin = server.addPlayer("Alias");
        admin.setOp(true);

        admin.performCommand("rad st");
        assertThat(join(drain(admin))).contains("RadSurvival status");

        admin.performCommand("rad c get Alias");
        assertThat(join(drain(admin))).contains("腐蚀条").contains("内存权威");

        admin.performCommand("rad rl --dry-run");
        assertThat(join(drain(admin))).contains("预检通过").doesNotContain("重载成功");
    }

    @Test
    @DisplayName("未知子命令给出最近似建议，而非静默回落到用法文本")
    void unknownSubcommandSuggestsClosest() {
        PlayerMock admin = server.addPlayer("Typo");
        admin.setOp(true);
        admin.performCommand("rad stats");
        assertThat(join(drain(admin)))
                .contains("未知子命令")
                .contains("你是不是想用")
                .contains("status");
    }
}
