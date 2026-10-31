package com.ft.radsurvival.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Spigot 拒绝分支的**隔离类加载**验证（v1.4，plan.md §7.4）。
 *
 * <p><b>为什么需要这个测试</b>：Spigot 官方从不发布预编译 jar（需 BuildTools 自行构建，
 * 且其源码仓库对外返回 HTTP 451），因此「在真 Spigot 上跑一次」这条路在当前环境走不通。
 * 但 Spigot 分支的判据非常单纯——<b>{@code org.bukkit.damage.DamageSource} 加载不到</b>，
 * 其余都是名字匹配。于是可以用一个屏蔽该类的 {@link ClassLoader} 精确复现这个条件。</p>
 *
 * <p><b>它验证什么 / 不验证什么</b>：</p>
 * <ul>
 *   <li>✅ 验证「类不可加载时 {@code detect} 给出 SPIGOT + supported=false」——
 *       这正是生产 {@code defaultProbe()} 的调用契约；</li>
 *   <li>❌ <b>不验证</b>「{@code onEnable} 在真 Spigot 上真的会执行到拒绝分支」。
 *       这一点请参考 Folia 的实测教训（ACCEPTANCE §M.2）：服务端可能在插件加载阶段就拒绝，
 *       那样 {@code onEnable} 一行都跑不到。Spigot 已知**会**加载并执行 {@code onEnable}
 *       （它没有 Folia 那种 provider 预检），但那是关于 Spigot 行为的推断，不是本测试的断言。</li>
 * </ul>
 *
 * <p>因此本类命名为 {@code Isolated} 而非「SpigotTest」，避免后来者误以为 Spigot 已实测。</p>
 */
class ServerBrandSpigotIsolatedTest {

    /** paper-api 里本插件的核心编译依赖；屏蔽它 ≈ 运行在没有 Paper API 的服务端上。 */
    private static final String PAPER_ONLY_CLASS = "org.bukkit.damage.DamageSource";

    /** 屏蔽指定类的类加载器：除目标类外全部委派父加载器。 */
    private static final class BlockingClassLoader extends URLClassLoader {
        private final String blocked;

        BlockingClassLoader(String blocked, ClassLoader parent) {
            super(new URL[0], parent);
            this.blocked = blocked;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (blocked.equals(name)) {
                throw new ClassNotFoundException(name + " (blocked to simulate Spigot)");
            }
            return super.loadClass(name, resolve);
        }
    }

    /** 与生产 {@code ServerBrand#defaultProbe()} 同逻辑，只是换一个类加载器。 */
    private static ServerBrand.ClassProbe probeUnder(ClassLoader loader) {
        return className -> {
            try {
                Class.forName(className, false, loader);
                return true;
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        };
    }

    private static BlockingClassLoader blockingLoader() {
        return new BlockingClassLoader(PAPER_ONLY_CLASS,
                ServerBrandSpigotIsolatedTest.class.getClassLoader());
    }

    @Test
    @DisplayName("屏蔽 DamageSource 后，探测契约给出 SPIGOT 且拒绝加载")
    void spigotDetectedWhenPaperClassMissing() {
        ServerBrand.ClassProbe probe = probeUnder(blockingLoader());

        // 前置断言：屏蔽确实生效，否则本用例退化成同义反复
        assertThat(probe.exists(PAPER_ONLY_CLASS))
                .as("屏蔽必须生效，否则整个用例无意义").isFalse();
        // 对照：其他 Bukkit 类仍可见 → 证明不是「整个 classpath 都断了」
        assertThat(probe.exists("org.bukkit.Bukkit"))
                .as("只应屏蔽目标类，其他 Bukkit 类必须仍可见").isTrue();

        ServerBrand.Detection d = ServerBrand.detect("Spigot", probe);

        assertThat(d.brand()).isEqualTo(ServerBrand.Brand.SPIGOT);
        assertThat(d.brand().supported()).isFalse();
        assertThat(d.brand().folia()).isFalse();
        assertThat(d.brand().refusalReason()).contains("paper-api");
        assertThat(d.source()).isEqualTo(ServerBrand.Source.SERVER_NAME);
    }

    @Test
    @DisplayName("同一屏蔽环境下，未知名分支不得被误判成 Spigot")
    void unknownForkUnderSameLoaderIsStillUnknown() {
        ServerBrand.Detection d = ServerBrand.detect("SomeFork", probeUnder(blockingLoader()));

        // 名字不含 spigot → 只能是 unknown，且 unknown 必须**放行**
        // （这是「拒绝太多分支」与「漏判」之间的分界线）
        assertThat(d.brand()).isEqualTo(ServerBrand.Brand.UNKNOWN);
        assertThat(d.brand().supported()).isTrue();
    }

    @Test
    @DisplayName("屏蔽环境下 getName 自称 Paper 也不能谎报支持")
    void selfProclaimedPaperWithoutApiIsUnknownNotPaper() {
        ServerBrand.Detection d = ServerBrand.detect("Paper", probeUnder(blockingLoader()));

        // 关键判断方向：名字说 Paper，但类探测说没有 Paper API →
        // 不报 PAPER（那会让管理员以为可用），而是报「未知分支」并放行观察。
        assertThat(d.brand()).isEqualTo(ServerBrand.Brand.UNKNOWN);
        assertThat(d.brand()).isNotEqualTo(ServerBrand.Brand.PAPER);
    }
}
