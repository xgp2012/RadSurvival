package com.ft.radsurvival.mockbukkit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.api.RadiationExposureEvent;
import com.ft.radsurvival.core.CorrosionMeter;
import com.ft.radsurvival.core.RadiationServices;
import com.ft.radsurvival.core.RadiationTask;

/**
 * MockBukkit 集成测试共用夹具（M2b）。
 *
 * <p><b>注入缝（§5.2 口径，生产代码零改动）</b>：MockBukkit 4.116.3 未实现
 * {@code BlockData#isOccluding}（露天扫描会抛 UnimplementedOperationException）与
 * {@code LivingEntityMock#damage(double, DamageSource)}（真实掉血 API），
 * 本夹具经 {@link RadiationServices} 测试构造注入：
 * 露天 = 布尔开关；伤害 = 记录调用后改走已实现的 {@code damage(double)} 简单重载——
 * 血量仍真实下降。过滤链/腐蚀步进/免疫短路/事件广播/取消覆写/PDC/命令全部走生产代码。</p>
 */
final class Support {

    private Support() {
    }

    /** 仓库真实 config.yml 原文（M1 校验口径的权威数值源）。 */
    static String repoConfigYaml() {
        try {
            return Files.readString(Path.of("src", "main", "resources", "config.yml"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 成对文本替换生成变体配置（先仓库原文，再 replace(old→new)×n）。 */
    static String configYamlWith(String... replacePairs) {
        String yaml = repoConfigYaml();
        if (replacePairs.length % 2 != 0) {
            throw new IllegalArgumentException("替换必须成对");
        }
        for (int i = 0; i < replacePairs.length; i += 2) {
            String next = yaml.replace(replacePairs[i], replacePairs[i + 1]);
            if (next.equals(yaml) && !yaml.contains(replacePairs[i])) {
                throw new IllegalStateException("配置变体锚点未命中: " + replacePairs[i]);
            }
            yaml = next;
        }
        return yaml;
    }

    /**
     * 变体配置生效通道：写入插件数据目录 config.yml → reloadConfigFromDisk()。
     * （MockBukkit 的 loadWithConfig 只喂 Bukkit getConfig() 缓存，本插件走 Configurate
     * 直读磁盘文件、saveDefaultConfig 会补写默认——实测变体不生效，故统一用「落盘+reload」，
     * 顺带把 M1 的 reload 校验与服务重建链路一起跑进测试。）
     */
    static void applyConfigVariant(RadSurvivalPlugin plugin, String yaml) {
        try {
            Files.writeString(plugin.configFile(), yaml);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> errors = plugin.reloadConfigFromDisk();
        if (!errors.isEmpty()) {
            throw new IllegalStateException("变体配置未通过校验: " + String.join(" | ", errors));
        }
    }

    /** 一次被注入的伤害调用。 */
    record AppliedDamage(String playerName, double amount) {
    }

    /**
     * 装配夹具：假露天开关 + 记录型伤害执行器 + 事件捕获监听。
     * 安装后即替换插件服务容器并启动任务（{@link RadSurvivalPlugin#replaceServices}）。
     */
    static final class Fixture {
        /** 露天开关（所有玩家同值；默认 true=露天）。 */
        final java.util.concurrent.atomic.AtomicBoolean exposed =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        final List<AppliedDamage> damages = new ArrayList<>();
        final List<RadiationExposureEvent> events = new ArrayList<>();
        RadiationServices services;

        RadiationTask task() {
            return services.radiationTask();
        }

        CorrosionMeter meter() {
            return services.corrosionMeter();
        }

        double corrosion(PlayerMock player) {
            return meter().get(player.getUniqueId());
        }

        double lastDamageAmount() {
            return damages.isEmpty() ? 0.0 : damages.get(damages.size() - 1).amount();
        }
    }

    /** 安装假服务夹具（露天=可开关布尔；伤害=记录 + damage(double) 真实扣血）。 */
    static Fixture install(RadSurvivalPlugin plugin, ServerMock server) {
        Fixture f = new Fixture();
        f.services = new RadiationServices(plugin, plugin.config(),
                player -> f.exposed.get(),
                (player, amount) -> {
                    f.damages.add(new AppliedDamage(player.getName(), amount));
                    player.damage(amount); // MockBukkit 已实现的简单重载：血量真实下降
                });
        plugin.replaceServices(f.services);
        f.services.start();
        server.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(ignoreCancelled = false)
            public void capture(RadiationExposureEvent event) {
                f.events.add(event);
            }
        }, plugin);
        return f;
    }

    /** 全部在线玩家的血量初值 20（防上轮残留），并清事件/伤害捕获。 */
    static void resetCombat(Fixture f, ServerMock server) {
        server.getOnlinePlayers().forEach(p -> {
            p.setHealth(20.0);
            p.setNoDamageTicks(0);
        });
        f.damages.clear();
        f.events.clear();
    }

    /** 全套装甲入位（材质枚举名参数化，如 NETHERITE / DIAMOND）。 */
    static void equipFullSet(PlayerMock player, String material) {
        player.getInventory().setHelmet(new org.bukkit.inventory.ItemStack(
                org.bukkit.Material.getMaterial(material + "_HELMET")));
        player.getInventory().setChestplate(new org.bukkit.inventory.ItemStack(
                org.bukkit.Material.getMaterial(material + "_CHESTPLATE")));
        player.getInventory().setLeggings(new org.bukkit.inventory.ItemStack(
                org.bukkit.Material.getMaterial(material + "_LEGGINGS")));
        player.getInventory().setBoots(new org.bukkit.inventory.ItemStack(
                org.bukkit.Material.getMaterial(material + "_BOOTS")));
    }
}
