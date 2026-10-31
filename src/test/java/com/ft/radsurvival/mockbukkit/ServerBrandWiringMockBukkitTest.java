package com.ft.radsurvival.mockbukkit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import com.ft.radsurvival.RadSurvivalPlugin;
import com.ft.radsurvival.core.ServerBrand;

/**
 * §7 防御分支与 {@code onEnable} 的接线测试（v1.4）。
 *
 * <p>纯单元测试（{@link com.ft.radsurvival.core.ServerBrandTest}）只能证明判定逻辑对，
 * 证明不了「{@code onEnable} 真的在最早的时机调用了它」。本类补上这一环：</p>
 * <ul>
 *   <li>正常（MockBukkit 跑在带 paper-api 的 classpath 上）时必须照常启用、且写入 brand；</li>
 *   <li>brand 必须在服务装配<b>之前</b>写入——否则 {@code /rad status} 与启动日志拿不到。</li>
 * </ul>
 *
 * <p><b>为什么不模拟 Spigot 环境来测拒绝分支</b>：MockBukkit 不能真的移除 paper-api 里的
 * {@code DamageSource}，而 {@link RadSurvivalPlugin#guardServerBrand()} 用的是默认探测器
 * （{@code Class::forName}）。伪造该场景需要给生产代码开一个测试专用 setter，
 * 为一条三行的 if 引入长期可写状态不划算——判定逻辑本身已由 ServerBrandTest 覆盖
 * （含 Folia 类优先、Spigot 拒绝、探针返回 false 三个方向），此处只钉住接线。</p>
 */
class ServerBrandWiringMockBukkitTest {

    private ServerMock server;
    private RadSurvivalPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(RadSurvivalPlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("正常服务端：仍照常启用，不被防御分支误杀")
    void pluginStillEnablesOnSupportedServer() {
        assertThat(plugin.isEnabled())
                .as("Paper 系（含 MockBukkit 的 DamageSource classpath）不应被拒绝加载")
                .isTrue();
        assertThat(plugin.services()).as("服务容器应正常装配").isNotNull();
        assertThat(plugin.config()).isNotNull();
    }

    @Test
    @DisplayName("brand 在 onEnable 中写入，且为可运行分支")
    void brandIsRecordedDuringEnable() {
        ServerBrand.Detection det = plugin.brand();

        assertThat(det).as("onEnable 必须写入识别结论，否则 status/日志无内容可显示").isNotNull();
        assertThat(det.brand().supported()).isTrue();
    }

    @Test
    @DisplayName("brand 不随 reload 变化（同一 JVM 内品牌恒定，避免 status 来源漂移）")
    void reloadKeepsSameBrand() {
        ServerBrand.Detection before = plugin.brand();

        plugin.reloadConfigFromDisk();

        assertThat(plugin.brand()).isSameAs(before);
    }
}
