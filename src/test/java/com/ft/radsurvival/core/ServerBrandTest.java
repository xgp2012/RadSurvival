package com.ft.radsurvival.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ServerBrand} 识别与防御分支的单元测试（plan.md §7，v1.4）。
 *
 * <p>纯 Java：不启动 MockBukkit，不以 {@code Xxx.class} 引用任何 Paper/Folia 类型，
 * 探测器用假实现控制「哪些类存在」——这正是本类把 {@code ClassProbe} 抽成接口的原因。</p>
 *
 * <p><b>每个用例都钉住一条「判断方向」</b>，而不是只断言最终等于某个枚举：
 * 识别错的代价不对称——把能跑的分支判成不能跑（误拒）只是管理员多点一次确认，
 * 把 Folia/Spigot 判成 Paper（漏判）会让插件以「启动成功但完全不工作」或
 * 「onEnable 抛 NoClassDefFoundError」的形式失败。因此所有兜底都偏向「拒绝」。</p>
 */
class ServerBrandTest {

    /** 只声明「存在的类」，未列出的类一律视为不存在。 */
    private static ServerBrand.ClassProbe probing(String... present) {
        Set<String> classes = Set.of(present);
        return classes::contains;
    }

    private static ServerBrand.ClassProbe paperProbe() {
        return probing(ServerBrand.PAPER_CLASS);
    }

    // ---------------------------------------------------------------- 支持的分支

    @Test
    @DisplayName("Paper：名字 + DamageSource 类均命中 → PAPER，且判定为可运行")
    void paperIsSupported() {
        ServerBrand.Detection d = ServerBrand.detect("Paper", paperProbe());

        assertThat(d.brand()).isEqualTo(ServerBrand.Brand.PAPER);
        assertThat(d.brand().supported()).isTrue();
        assertThat(d.brand().folia()).isFalse();
        assertThat(d.brand().refusalReason()).isNull();
        assertThat(d.source()).isEqualTo(ServerBrand.Source.CLASS_PROBE);
    }

    @Test
    @DisplayName("Leaf / Purpur：名字唯一命中，且均视为可运行（Paper 分支，API 同面）")
    void paperForksAreSupported() {
        ServerBrand.Detection leaf = ServerBrand.detect("Leaf", paperProbe());
        ServerBrand.Detection purpur = ServerBrand.detect("Purpur", paperProbe());

        assertThat(leaf.brand()).isEqualTo(ServerBrand.Brand.LEAF);
        assertThat(purpur.brand()).isEqualTo(ServerBrand.Brand.PURPUR);
        assertThat(leaf.brand().supported()).isTrue();
        assertThat(purpur.brand().supported()).isTrue();
    }

    // ---------------------------------------------------------------- 拒绝的分支

    @Test
    @DisplayName("Folia：仅凭 RegionizedServer 类即判定（即使 getName 声称是 Paper）")
    void foliaDetectedByClassEvenIfNameSaysPaper() {
        // 关键场景：分支服务端可能改写 getName，但 Folia 的区域化实现必然带这个类
        ServerBrand.Detection d = ServerBrand.detect("Paper", probing(ServerBrand.FOLIA_CLASS));

        assertThat(d.brand()).isEqualTo(ServerBrand.Brand.FOLIA);
        assertThat(d.source()).isEqualTo(ServerBrand.Source.CLASS_PROBE);
        assertThat(d.brand().supported()).isFalse();
        assertThat(d.brand().folia()).isTrue();
        assertThat(d.brand().refusalReason()).contains("区域化线程");
    }

    @Test
    @DisplayName("Folia：即使同时带 DamageSource（它是 Paper 分支）也必须优先判 Folia")
    void foliaWinsOverPaperClass() {
        ServerBrand.Detection d = ServerBrand.detect("Paper",
                probing(ServerBrand.FOLIA_CLASS, ServerBrand.PAPER_CLASS));

        assertThat(d.brand()).isEqualTo(ServerBrand.Brand.FOLIA);
        assertThat(d.brand().supported()).isFalse();
    }

    @Test
    @DisplayName("Spigot：名字命中且类探测阴性（无 DamageSource）→ SPIGOT，拒绝加载")
    void spigotIsRefused() {
        ServerBrand.Detection d = ServerBrand.detect("Spigot", probing());

        assertThat(d.brand()).isEqualTo(ServerBrand.Brand.SPIGOT);
        assertThat(d.source()).isEqualTo(ServerBrand.Source.SERVER_NAME);
        assertThat(d.brand().supported()).isFalse();
        assertThat(d.brand().folia()).isFalse();
        assertThat(d.brand().refusalReason()).contains("paper-api");
    }

    @Test
    @DisplayName("反向：纯 Spigot 若真能加载 DamageSource（装了 Paper 兼容层）则不误拒")
    void spigotWithPaperClassesIsNotRefused() {
        // 识别器以类存在性为准：能加载 Paper 专属类就说明 Paper API 可用，按 PAPER 放行
        ServerBrand.Detection d = ServerBrand.detect("Spigot", paperProbe());

        assertThat(d.brand()).isEqualTo(ServerBrand.Brand.PAPER);
        assertThat(d.brand().supported()).isTrue();
    }

    // ---------------------------------------------------------------- 兜底方向

    @Test
    @DisplayName("unknown：名字无法识别 + 类探测阴性 → 按可运行处理，但标记为未知分支")
    void unknownIsTreatedAsRunnable() {
        ServerBrand.Detection d = ServerBrand.detect("SomeFork", probing());

        assertThat(d.brand()).isEqualTo(ServerBrand.Brand.UNKNOWN);
        assertThat(d.source()).isEqualTo(ServerBrand.Source.FALLBACK);
        // UNKNOWN 不拒绝加载（否则所有未实测分支一律装不上），但 supported() 为 true 不等于已实测
        assertThat(d.brand().supported()).isTrue();
        assertThat(d.rawName()).isEqualTo("SomeFork");
    }

    @Test
    @DisplayName("异常输入：getName 为 null / 空串 / 空白都不抛异常，落到兜底")
    void nullAndBlankNamesAreSafe() {
        assertThat(ServerBrand.detect(null, probing()).brand()).isEqualTo(ServerBrand.Brand.UNKNOWN);
        assertThat(ServerBrand.detect("", probing()).brand()).isEqualTo(ServerBrand.Brand.UNKNOWN);
        assertThat(ServerBrand.detect("   ", probing()).brand()).isEqualTo(ServerBrand.Brand.UNKNOWN);
        // probe 为 null 时也不能 NPE（极窄场景：类加载器已失效）
        assertThat(ServerBrand.detect("Purpur", null).brand()).isEqualTo(ServerBrand.Brand.PURPUR);
    }

    // ---------------------------------------------------------------- 展示

    @Test
    @DisplayName("describe()：带来源，且未知分支的回退名不伪造「Paper」")
    void describeExposesSource() {
        assertThat(ServerBrand.detect("Paper", paperProbe()).describe()).contains("Paper").contains("类探测");
        assertThat(ServerBrand.detect("Spigot", probing()).describe()).contains("Spigot").contains("getName");
        ServerBrand.Detection unknown = ServerBrand.detect("Mystery", probing());
        assertThat(unknown.describe()).contains("未知分支").doesNotContain("Paper");
    }

    @Test
    @DisplayName("大小写与前后空白不敏感（分支服务端 getName 拼写不统一）")
    void nameMatchingIsCaseAndSpaceInsensitive() {
        assertThat(ServerBrand.detect("  PURPUR ", probing()).brand()).isEqualTo(ServerBrand.Brand.PURPUR);
        assertThat(ServerBrand.detect("pUrPuR", probing()).brand()).isEqualTo(ServerBrand.Brand.PURPUR);
        assertThat(ServerBrand.detect("LEAF", probing()).brand()).isEqualTo(ServerBrand.Brand.LEAF);
    }

    @Test
    @DisplayName("生产探测器：真实运行时里 DamageSource 必须存在（本插件编得过就说明在 Paper 系上）")
    void defaultProbeFindsPaperClassAtTestRuntime() {
        // 测试 classpath 带 paper-api，因此这条断言是「探测器本身能工作」的活体证明；
        // 若哪天默认探测器写错返回恒 false，这里立刻变红。
        assertThat(ServerBrand.defaultProbe().exists(ServerBrand.PAPER_CLASS)).isTrue();
        assertThat(ServerBrand.defaultProbe().exists("com.ft.radsurvival.Definitely.Not.A.Class")).isFalse();
    }
}
